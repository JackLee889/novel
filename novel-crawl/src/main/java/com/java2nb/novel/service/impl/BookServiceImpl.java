package com.java2nb.novel.service.impl;

import com.java2nb.novel.core.cache.CacheKey;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookCategory;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.mapper.*;
import com.java2nb.novel.service.BookContentService;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.utils.Constants;
import com.java2nb.novel.core.crawl.CrawlBookMetadata;
import com.java2nb.novel.seo.ChapterContentReadiness;
import com.java2nb.novel.seo.SeoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.dynamic.sql.render.RenderingStrategies;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.java2nb.novel.mapper.BookDynamicSqlSupport.crawlBookId;
import static com.java2nb.novel.mapper.BookDynamicSqlSupport.crawlSourceId;
import static com.java2nb.novel.mapper.CrawlSourceDynamicSqlSupport.id;
import static org.mybatis.dynamic.sql.SqlBuilder.*;
import static org.mybatis.dynamic.sql.select.SelectDSL.select;

/**
 * @author Administrator
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookServiceImpl implements BookService {

    private final CrawlBookMapper bookMapper;

    private final BookCategoryMapper bookCategoryMapper;

    private final CrawlBookIndexMapper bookIndexMapper;

    private final Map<String, BookContentService> bookContentServiceMap;

    private final CacheService cacheService;
    private final SeoRepository seoRepository;

    @Value("${seo.enabled:false}")
    private boolean seoEnabled;
    private com.java2nb.novel.core.crawl.CrawlIndexStore indexStore;
    @org.springframework.beans.factory.annotation.Autowired
    public void setIndexStore(com.java2nb.novel.core.crawl.CrawlIndexStore store){this.indexStore=store;}
    private boolean indexedState(){return indexStore!=null&&indexStore.enabled();}

    @Value("${content.save.storage}")
    private String storageType;


    @Override
    public boolean queryIsExistByBookNameAndAuthorName(String bookName, String authorName) {

        return bookMapper.count(countFrom(BookDynamicSqlSupport.book).where(BookDynamicSqlSupport.bookName, isEqualTo(bookName))
                .and(BookDynamicSqlSupport.authorName, isEqualTo(authorName))
                .build()
                .render(RenderingStrategies.MYBATIS3)) > 0;

    }

    @Override
    public void updateCrawlProperties(Long id, Integer sourceId, String bookId) {
        bookMapper.update(update(BookDynamicSqlSupport.book)
                .set(crawlSourceId)
                .equalTo(sourceId)
                .set(crawlBookId)
                .equalTo(bookId)
                .where(BookDynamicSqlSupport.id, isEqualTo(id))
                .build()
                .render(RenderingStrategies.MYBATIS3));
    }

    @Override
    public String queryCatNameByCatId(int catId) {
        BookCategory category = queryBookCategoryByCatId(catId);
        if (category == null) {
            throw new IllegalArgumentException("Unknown book category: " + catId);
        }
        return category.getName();
    }

    @Override
    public List<BookCategory> listBookCategories() {
        return bookCategoryMapper.selectMany(select(BookCategoryDynamicSqlSupport.id,
                BookCategoryDynamicSqlSupport.name, BookCategoryDynamicSqlSupport.workDirection,
                BookCategoryDynamicSqlSupport.sort)
            .from(BookCategoryDynamicSqlSupport.bookCategory)
            .orderBy(BookCategoryDynamicSqlSupport.sort, BookCategoryDynamicSqlSupport.id)
            .build().render(RenderingStrategies.MYBATIS3));
    }

    @Override
    public BookCategory queryBookCategoryByCatId(int catId) {
        return bookCategoryMapper.selectByPrimaryKey(catId).orElse(null);
    }

    /**
     * 每批写入的章节数上限。
     * [审查15] 原来整本的目录/正文各调用一次 insertMultiple，生成单条多行 SQL；
     * 补上目录分页后一本书可达上千章，单条 SQL 容易触碰 max_allowed_packet 或参数上限，
     * 且失败时没有任何断点。现在按批提交，前面已提交的批次不会白做。
     */
    private static final int INSERT_BATCH_SIZE = 100;

    /**
     * [审查01] 章节号重排的临时偏移量：把待重排行挪到 index_num + 该值的位置。
     * 取一个远大于正常章节号的常数，保证与未参与重排的行不冲突；int 上限约 21.4 亿。
     */
    private static final int REINDEX_PARK_OFFSET = 100_000_000;

    @com.java2nb.novel.core.io.BackgroundIo
    @Transactional(rollbackFor = Exception.class, timeout=30)
    @Override
    public void saveBookAndIndexAndContent(Book book, List<BookIndex> bookIndexList, List<BookContent> bookContentList) {
        validateChapterBatch(book, bookIndexList, bookContentList);
        if(seoEnabled)seoRepository.lockMutation();
        Book previous = bookMapper.lockBookSummary(book.getId());
        boolean newBook = previous == null;
        // Publication is managed here, never by a reused crawler snapshot.
        book.setStatus(null);
        boolean seoChanged=newBook||publicMetadataChanged(book,previous)||!bookIndexList.isEmpty();
        if (newBook) {
            // An empty directory must not create a visible book without readable chapters.
            if (bookIndexList.isEmpty()) {
                return;
            }
            validateNewBookPublication(bookIndexList, bookContentList);
            if (queryIsExistByBookNameAndAuthorName(book.getBookName(), book.getAuthorName())) {
                throw new IllegalStateException("Book already exists; reload its chapter snapshot before retrying");
            }
            if (book.getCreateTime() == null) {
                book.setCreateTime(new Date());
            }
            if (book.getUpdateTime() == null) {
                book.setUpdateTime(new Date());
            }
            if (book.getWordCount() == null) {
                book.setWordCount(0);
            }
            // 源站没有提供封面时使用站内默认图，满足 pic_url 的必填约束。
            // 只在首次入库时设置，已有书的封面仍由原更新逻辑保留。
            if (book.getPicUrl() == null || book.getPicUrl().isBlank()) {
                book.setPicUrl("/images/default.gif");
            }
            // Publish a summary only after its chapter/content rows have been saved.
            book.setLastIndexId(null);
            book.setLastIndexName(null);
            book.setLastIndexUpdateTime(null);
            book.setStatus((byte) 0);
            bookMapper.insertSelective(book);
            previous = bookMapper.lockBookSummary(book.getId());
        }

        bookIndexList.forEach(bookIndex -> bookIndex.setStorageType(storageType));

        for (int from = 0; from < bookIndexList.size(); from += INSERT_BATCH_SIZE) {
            int to = Math.min(from + INSERT_BATCH_SIZE, bookIndexList.size());
            bookIndexMapper.insertMultiple(new ArrayList<>(bookIndexList.subList(from, to)));
            bookContentServiceMap.get(storageType).saveBookContent(
                new ArrayList<>(bookContentList.subList(from, to)), book.getId());
        }
        book.setStatus(null);
        boolean summaryChanged=synchronizeBookSummary(book, previous);
        if (newBook) {
            // Publish only after the first readable batch and its summary are saved.
            // Use a separate object so later batches cannot undo an administrator's unpublication.
            Book publication = new Book();
            publication.setId(book.getId());
            publication.setStatus((byte) 1);
            if (bookMapper.updateByPrimaryKeySelective(publication) != 1) {
                throw new IllegalStateException("Cannot publish the newly collected book: " + book.getId());
            }
        }
        if(seoEnabled&&(seoChanged||summaryChanged))seoRepository.markBookDirty(book.getId());
    }

    @Override
    public List<Book> queryNeedUpdateBook(Date startDate, int limit) {
        // The durable enqueue advances crawl_last_time in the same transaction as the job.
        return bookMapper.queryNeedUpdateBook(startDate, limit);
    }

    @Override
    public Book queryBookById(Long bookId) {
        return bookMapper.selectByPrimaryKey(bookId).orElse(null);
    }

    @Override
    public Book queryBookBySource(String bookName, Integer sourceId, String sourceBookId) {
        List<Book> found = bookMapper.selectMany(select(BookMapper.selectList).from(BookDynamicSqlSupport.book)
            .where(BookDynamicSqlSupport.bookName, isEqualTo(bookName))
            .and(crawlSourceId, isEqualTo(sourceId)).and(crawlBookId, isEqualTo(sourceBookId))
            .limit(2).build().render(RenderingStrategies.MYBATIS3));
        if (found.size() > 1) throw new IllegalStateException("Multiple local books share one source binding");
        return found.isEmpty() ? null : found.getFirst();
    }

    @com.java2nb.novel.core.io.BackgroundIo
    @Transactional(rollbackFor = Exception.class, timeout=30)
    @Override
    public String fillPendingAuthor(Long bookId, Integer sourceId, String sourceBookId, String author) {
        if (seoEnabled) seoRepository.lockMutation();
        if (bookMapper.lockBookSummary(bookId) == null) return null;
        Book current = queryBookById(bookId);
        if (!Objects.equals(current.getCrawlSourceId(), sourceId)
            || !Objects.equals(current.getCrawlBookId(), sourceBookId)) return current.getAuthorName();
        String candidate = CrawlBookMetadata.author(author);
        if (!CrawlBookMetadata.pendingAuthor(current.getAuthorName()) || !CrawlBookMetadata.usableAuthor(candidate))
            return current.getAuthorName();
        // The old title/author unique key remains in place. A collision needs review, never an automatic merge.
        Book occupied = queryBookByBookNameAndAuthorName(current.getBookName(), candidate);
        if (occupied != null && !Objects.equals(occupied.getId(), bookId)) {
            log.warn("作者补全存在同名同作者图书，保留本书ID及待核验标记：bookId={}", bookId);
            return current.getAuthorName();
        }
        Book patch = new Book(); patch.setId(bookId); patch.setAuthorName(candidate);
        try {
            if (bookMapper.updateByPrimaryKeySelective(patch) != 1) throw new IllegalStateException("Pending author update failed");
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            // Another book may have acquired the unique title/author key after the check above.
            log.warn("作者补全遇到并发同名图书，保留待核验标记继续采集：bookId={}", bookId);
            return current.getAuthorName();
        }
        if (seoEnabled) seoRepository.markBookDirty(bookId);
        invalidateBookCachesAfterCommit();
        return candidate;
    }

    @Override
    public Map<Integer, BookIndex> queryExistBookIndexMap(Long bookId) {
        if(indexedState()) return indexStore.snapshot(bookId);
        // [审查01] 一并取出 source_index_id：章节身份以来源章节 ID 为准，
        // 否则源站插入章节/目录重排时只能按位置匹配，会把已有章节的正文写错行。
        List<BookIndex> bookIndexs = bookIndexMapper.selectMany(select(BookIndexDynamicSqlSupport.id, BookIndexDynamicSqlSupport.indexNum, BookIndexDynamicSqlSupport.indexName, BookIndexDynamicSqlSupport.sourceIndexId, BookIndexDynamicSqlSupport.wordCount, BookIndexDynamicSqlSupport.storageType,BookIndexDynamicSqlSupport.isVip,BookIndexDynamicSqlSupport.bookPrice)
                .from(BookIndexDynamicSqlSupport.bookIndex)
                .where(BookIndexDynamicSqlSupport.bookId, isEqualTo(bookId))
                .build()
                .render(RenderingStrategies.MYBATIS3));
        if (bookIndexs.size() > 0) {
            return bookIndexs.stream().collect(Collectors.toMap(BookIndex::getIndexNum, Function.identity()));
        }
        return new HashMap<>(0);
    }

    @com.java2nb.novel.core.io.BackgroundIo
    @Transactional(rollbackFor = Exception.class, timeout=30)
    @Override
    public void updateBookAndIndexAndContent(Book book, List<BookIndex> bookIndexList, List<BookContent> bookContentList,
        Map<Integer, BookIndex> existBookIndexMap, List<BookIndex> bookIndexMetaList, List<BookIndex> reindexList) {
        updateBookAndIndexAndContent(book,bookIndexList,bookContentList,existBookIndexMap,bookIndexMetaList,reindexList,null);
    }

    @com.java2nb.novel.core.io.BackgroundIo
    @Transactional(rollbackFor = Exception.class, timeout=30)
    @Override
    public void updateBookAndIndexAndContent(Book book, List<BookIndex> bookIndexList, List<BookContent> bookContentList,
        Map<Integer, BookIndex> existBookIndexMap, List<BookIndex> bookIndexMetaList, List<BookIndex> reindexList,
        Integer catalogSize) {
        validateChapterBatch(book, bookIndexList, bookContentList);
        if(seoEnabled)seoRepository.lockMutation();
        Book previous = bookMapper.lockBookSummary(book.getId());
        if (previous == null) {
            throw new IllegalStateException("Cannot save chapters for a missing book: " + book.getId());
        }
        if(indexedState()) indexStore.beforeWrite(book.getId(),existBookIndexMap);
        book.setStatus(null);
        boolean seoChanged=publicMetadataChanged(book,previous);

        // [审查01] 已存在章节按主键 id 定位（解析器用来源章节 ID 命中的行带来的就是它的 id），
        // 不再用「目录位置上有没有章节」来决定插入还是更新。
        Map<Long, BookIndex> existById = new HashMap<>(existBookIndexMap.size());
        for (BookIndex exist : existBookIndexMap.values()) {
            if (exist.getId() != null) {
                existById.put(exist.getId(), exist);
            }
        }

        // [审查01] 目录顺序变化时先重排章节号：
        // book_index 上有唯一键 (book_id, index_num)，逐行改成新章节号会在中间态撞键，
        // 因此先把这次要动的行整体挪到高位区间（只挪这些 id，避免影响解析快照之后
        // 才写入的行），再按新顺序一次性赋回。
        if (reindexList != null && !reindexList.isEmpty()) {
            List<Long> reindexIds = new ArrayList<>(reindexList.size());
            for (BookIndex item : reindexList) {
                reindexIds.add(item.getId());
            }
            int parked = bookIndexMapper.parkBookIndexNums(book.getId(), reindexIds, REINDEX_PARK_OFFSET);
            if (parked != reindexIds.size()) {
                log.warn("章节号重排：预期挪动 {} 行，实际 {} 行（可能有并发写入或数据被删）",
                    reindexIds.size(), parked);
            }
            for (BookIndex item : reindexList) {
                BookIndex old=existById.get(item.getId());
                if(old==null||changed(item.getIndexNum(),old.getIndexNum()))seoChanged=true;
            }
            // All participating rows are parked before any final number is assigned.
            // Keep that unique-key safety while avoiding one remote round trip per chapter.
            for (int from=0;from<reindexList.size();from+=INSERT_BATCH_SIZE) {
                bookIndexMapper.updateBookIndexNums(book.getId(),
                    new ArrayList<>(reindexList.subList(from,Math.min(from+INSERT_BATCH_SIZE,reindexList.size()))));
            }
            log.info("书籍 {} 章节号已重排：挪动 {} 行，重排 {} 行", book.getId(), parked, reindexList.size());
        }

        // [审查01] 标题一致、不重新抓正文的章节：只把来源章节 ID / 章节号补写上
        if (bookIndexMetaList != null) {
            List<BookIndex> identityUpdates = new ArrayList<>();
            for (BookIndex meta : bookIndexMetaList) {
                BookIndex old=existById.get(meta.getId());
                boolean publicChange=old==null||chapterMetadataChanged(meta,old);
                if(publicChange)seoChanged=true;
                if (!publicChange && !changed(meta.getSourceIndexId(), old.getSourceIndexId())) continue;
                if (identityMetadataOnly(meta)) identityUpdates.add(meta);
                else bookIndexMapper.updateByPrimaryKeySelective(meta);
            }
            for (int from=0;from<identityUpdates.size();from+=INSERT_BATCH_SIZE) {
                bookIndexMapper.updateIdentityMetadata(book.getId(),
                    new ArrayList<>(identityUpdates.subList(from,Math.min(from+INSERT_BATCH_SIZE,identityUpdates.size()))));
            }
        }

        List<BookIndex> newIndexes = new ArrayList<>();
        List<BookContent> newContents = new ArrayList<>();
        for (int i = 0; i < bookIndexList.size(); i++) {
            BookIndex bookIndex = bookIndexList.get(i);
            BookContent bookContent = bookContentList.get(i);

            BookIndex exist = bookIndex.getId() == null ? null : existById.get(bookIndex.getId());
            if (exist == null) {
                seoChanged=true;
                //插入
                bookIndex.setStorageType(storageType);
                newIndexes.add(bookIndex);
                newContents.add(bookContent);
            } else {
                //更新
                String existStorageType = exist.getStorageType() == null ? storageType : exist.getStorageType();
                BookContentService contents=bookContentServiceMap.get(existStorageType);
                boolean metadataChanged=chapterMetadataChanged(bookIndex,exist);
                boolean bodyChanged=!contents.sameContent(bookContent,book.getId());
                if(metadataChanged||bodyChanged)seoChanged=true;
                if(metadataChanged||bodyChanged||changed(bookIndex.getSourceIndexId(),exist.getSourceIndexId()))
                    bookIndexMapper.updateByPrimaryKeySelective(bookIndex);
                if(bodyChanged)contents.updateBookContent(bookContent, book.getId());
            }


        }

        for (int from=0;from<newIndexes.size();from+=INSERT_BATCH_SIZE) {
            int to=Math.min(from+INSERT_BATCH_SIZE,newIndexes.size());
            bookIndexMapper.insertMultiple(new ArrayList<>(newIndexes.subList(from,to)));
            bookContentServiceMap.get(storageType).saveBookContent(new ArrayList<>(newContents.subList(from,to)),book.getId());
        }
        //更新小说主表
        book.setBookName(null);
        book.setAuthorName(null);
        if (Constants.VISIT_COUNT_DEFAULT.equals(book.getVisitCount())) {
            book.setVisitCount(null);
        }
        boolean summaryChanged=synchronizeBookSummary(book, previous, catalogSize);
        if(indexedState()) indexStore.afterWrite(existBookIndexMap);
        if(seoEnabled&&(seoChanged||summaryChanged))seoRepository.markBookDirty(book.getId());

    }

    private void validateChapterBatch(Book book, List<BookIndex> indexes, List<BookContent> contents) {
        Objects.requireNonNull(book.getId(), "Book ID is required");
        if (indexes == null || contents == null || indexes.size() != contents.size()) {
            throw new IllegalArgumentException("Chapter indexes and contents must have equal sizes");
        }
        for (int i = 0; i < indexes.size(); i++) {
            BookIndex index = indexes.get(i);
            if (index.getId() == null || !index.getId().equals(contents.get(i).getIndexId())
                || (index.getBookId() != null && !book.getId().equals(index.getBookId()))) {
                throw new IllegalArgumentException("Chapter identity does not match the book/content batch");
            }
        }
    }

    private void validateNewBookPublication(List<BookIndex> indexes, List<BookContent> contents) {
        long wordCount = indexes.stream()
            .mapToLong(index -> index.getWordCount() == null ? 0 : index.getWordCount()).sum();
        if (wordCount <= 0 || contents.stream()
            .anyMatch(content -> !ChapterContentReadiness.hasReadableText(content.getContent()))) {
            throw new IllegalArgumentException("A new book needs readable chapters before publication");
        }
    }

    /**
     * Book lists read the summary, while directory pages read book_index. Update both in
     * the same transaction for every batch, including empty retry/final batches.
     * Derive the summary from persisted rows, never from an unfetched source chapter.
     */
    private boolean synchronizeBookSummary(Book book, Book previous) {
        return synchronizeBookSummary(book,previous,null);
    }

    private boolean synchronizeBookSummary(Book book, Book previous, Integer catalogSize) {
        BookIndex latest = catalogSize==null ? bookIndexMapper.queryLastIndex(book.getId())
            : bookIndexMapper.queryLastCatalogIndex(book.getId(),catalogSize);
        book.setWordCount(indexedState()?indexStore.totalWords(book.getId()):bookMapper.queryTotalWordCount(book.getId()));
        book.setUpdateTime(new Date());
        if (latest == null) {
            book.setLastIndexId(null);
            book.setLastIndexName(null);
            book.setLastIndexUpdateTime(null);
        } else {
            book.setLastIndexId(latest.getId());
            // The book summary allows 50 characters; the directory title allows 100.
            String latestName = latest.getIndexName();
            if (latestName != null && latestName.codePointCount(0, latestName.length()) > 50) {
                latestName = latestName.substring(0, latestName.offsetByCodePoints(0, 49)) + "…";
            }
            book.setLastIndexName(latestName);
            // Filling an earlier gap or merely backfilling source IDs is not a new release.
            Date latestTime = previous.getLastIndexUpdateTime();
            if (!Objects.equals(previous.getLastIndexId(), latest.getId()) || latestTime == null) {
                latestTime = latest.getCreateTime() != null ? latest.getCreateTime() : latest.getUpdateTime();
                if (latestTime == null) {
                    latestTime = book.getUpdateTime();
                }
            }
            book.setLastIndexUpdateTime(latestTime);
        }
        bookMapper.updateByPrimaryKeySelective(book);
        // Selective update already includes all non-null summary fields. Only an empty
        // directory or legacy null title needs explicit-null handling to clear stale fields.
        if (latest == null || book.getLastIndexName() == null) bookMapper.updateBookSummary(book);
        invalidateBookCachesAfterCommit();
        return !Objects.equals(book.getWordCount(),previous.getWordCount())||!Objects.equals(book.getLastIndexId(),previous.getLastIndexId())||!Objects.equals(book.getLastIndexName(),previous.getLastIndexName());
    }

    private static boolean changed(Object incoming,Object previous){return incoming!=null&&!Objects.equals(incoming,previous);}
    private static boolean identityMetadataOnly(BookIndex value) {
        return value.getSourceIndexId()!=null&&value.getIndexNum()!=null&&value.getUpdateTime()!=null
            &&value.getBookId()==null&&value.getIndexName()==null&&value.getWordCount()==null
            &&value.getIsVip()==null&&value.getBookPrice()==null&&value.getStorageType()==null&&value.getCreateTime()==null;
    }
    private static boolean publicMetadataChanged(Book incoming,Book previous){
        return changed(incoming.getWorkDirection(),previous.getWorkDirection())||changed(incoming.getCatId(),previous.getCatId())||changed(incoming.getCatName(),previous.getCatName())||
            changed(incoming.getPicUrl(),previous.getPicUrl())||changed(incoming.getBookDesc(),previous.getBookDesc())||changed(incoming.getBookStatus(),previous.getBookStatus())||
            changed(incoming.getIsVip(),previous.getIsVip())||changed(incoming.getStatus(),previous.getStatus());
    }
    private static boolean chapterMetadataChanged(BookIndex incoming,BookIndex previous){
        return changed(incoming.getIndexName(),previous.getIndexName())||changed(incoming.getIndexNum(),previous.getIndexNum())||changed(incoming.getWordCount(),previous.getWordCount())||
            changed(incoming.getIsVip(),previous.getIsVip())||changed(incoming.getBookPrice(),previous.getBookPrice())||changed(incoming.getStorageType(),previous.getStorageType());
    }

    private void invalidateBookCachesAfterCommit() {
        Runnable invalidate = () -> {
            try {
                cacheService.del(CacheKey.INDEX_UPDATE_BOOK_KEY);
                cacheService.del(CacheKey.INDEX_NEW_BOOK_KEY);
                cacheService.del(CacheKey.INDEX_CLICK_BANK_BOOK_KEY);
                cacheService.del(CacheKey.INDEX_BOOK_SETTINGS_KEY);
            } catch (RuntimeException e) {
                // A committed chapter batch remains successful if Redis is temporarily unavailable.
                log.warn("Chapter batch committed, but book caches could not be refreshed", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
    }

    @Override
    public void updateCrawlLastTime(Long bookId) {
        Book book = new Book();
        book.setId(bookId);
        book.setCrawlLastTime(new Date());
        bookMapper.updateByPrimaryKeySelective(book);
    }

    @Override
    public Book queryBookByBookNameAndAuthorName(String bookName, String authorName) {
        List<Book> books = bookMapper.selectMany(select(BookDynamicSqlSupport.id,
                BookDynamicSqlSupport.crawlSourceId, BookDynamicSqlSupport.crawlBookId).from(BookDynamicSqlSupport.book)
                .where(BookDynamicSqlSupport.bookName, isEqualTo(bookName))
                .and(BookDynamicSqlSupport.authorName, isEqualTo(authorName))
                .build()
                .render(RenderingStrategies.MYBATIS3));

        if (books.size() > 0) {
            return books.get(0);
        }

        return null;

    }

}
