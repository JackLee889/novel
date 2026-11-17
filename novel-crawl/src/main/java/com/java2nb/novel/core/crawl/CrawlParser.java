package com.java2nb.novel.core.crawl;

import com.java2nb.novel.core.utils.RandomBookInfoUtil;
import com.java2nb.novel.core.utils.StringUtil;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.entity.CrawlSingleTask;
import com.java2nb.novel.utils.Constants;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.utils.CrawlMetadataException;
import io.github.xxyopen.util.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 爬虫解析器
 *
 * @author Administrator
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlParser {

    private final IdWorker ID_WORKER = IdWorker.INSTANCE;

    private final CrawlHttpClient crawlHttpClient;

    private final StringRedisTemplate stringRedisTemplate;

    private final CrawlCollectionStore collectionStore;
    private CrawlIndexStore indexStore;
    @org.springframework.beans.factory.annotation.Autowired public void setIndexStore(CrawlIndexStore value){indexStore=value;}
    private boolean unchangedRevision(long bookId,int source,String sourceBook,String hash,int count,java.util.function.IntFunction<String> prefix){
        return indexStore==null||!indexStore.enabled()||indexStore.snapshot(bookId).canUseSparse(source,sourceBook,hash,count,prefix);
    }
    private CrawlRuntimeMonitor runtimeMonitor = CrawlRuntimeMonitor.disabled();

    @org.springframework.beans.factory.annotation.Autowired
    public void setRuntimeMonitor(CrawlRuntimeMonitor monitor) { this.runtimeMonitor = Objects.requireNonNull(monitor); }
    /** The current task loads historical indexes only when its catalog actually needs reconciliation. */
    public static Map<Integer,BookIndex> deferIndexes(java.util.function.Supplier<Map<Integer,BookIndex>> supplier) {
        return new DeferredIndexes(supplier);
    }
    public static Map<Integer,BookIndex> resolveIndexes(Map<Integer,BookIndex> indexes) {
        return indexes instanceof DeferredIndexes d?resolveIndexes(d.loaded()):indexes;
    }
    public static Map<Integer,BookIndex> prepareIndexes(Map<Integer,BookIndex> indexes) {
        var value=resolveIndexes(indexes);
        if(value instanceof CrawlIndexStore.Snapshot snapshot)snapshot.prepareWrite();
        return value;
    }
    private void saveAudit(CrawlCollectionStore.Audit audit, Map<Integer,BookIndex> indexes) {
        if(indexes instanceof CrawlIndexStore.Snapshot snapshot)snapshot.checkpoint(audit,()->collectionStore.save(audit));
        else collectionStore.save(audit);
    }
    private static final class DeferredIndexes extends AbstractMap<Integer,BookIndex> {
        private java.util.function.Supplier<Map<Integer,BookIndex>> supplier;
        private Map<Integer,BookIndex> loaded;
        DeferredIndexes(java.util.function.Supplier<Map<Integer,BookIndex>> supplier) {this.supplier=Objects.requireNonNull(supplier);}
        private Map<Integer,BookIndex> loaded() {
            if(loaded==null) {loaded=Objects.requireNonNull(supplier.get());supplier=null;}
            return loaded;
        }
        @Override public Set<Entry<Integer,BookIndex>> entrySet() {return loaded().entrySet();}
        @Override public BookIndex get(Object key) {return loaded().get(key);}
        @Override public int size() {return loaded().size();}
        @Override public boolean isEmpty() {return loaded().isEmpty();}
        @Override public boolean containsKey(Object key) {return loaded().containsKey(key);}
        @Override public Collection<BookIndex> values() {return loaded().values();}
        @Override public Set<Integer> keySet() {return loaded().keySet();}
    }
    private record RunProgress(boolean progressed, boolean resumable) { }
    private final ThreadLocal<RunProgress> lastRun = new ThreadLocal<>();
    private final ThreadLocal<CrawlRefreshStore.Session> refreshRun = new ThreadLocal<>();
    public final class RefreshScope implements AutoCloseable {
        private RefreshScope(CrawlRefreshStore.Session session) {refreshRun.set(session);}
        @Override public void close() {refreshRun.remove();}
    }
    public RefreshScope refreshScope(CrawlRefreshStore.Session session) {return new RefreshScope(session);}
    public void markRefreshSaved() {var s=refreshRun.get();if(s!=null)s.saved();}
    public boolean lastRunMadeProgress() { return lastRun.get() != null && lastRun.get().progressed(); }
    public boolean lastRunIsResumable() { return lastRun.get() != null && lastRun.get().resumable(); }
    public void resetRunProgress() { lastRun.remove(); }

    /**
     * 爬虫源采集章节数量缓存key
     */
    private static final String CRAWL_SOURCE_CHAPTER_COUNT_CACHE_KEY = "crawlSource:chapterCount:";
    private static final String PENDING_NEW_BOOK_KEY = "crawlSource:pendingNewBook:";

    private record BookCrawlOwner(Thread thread, int maxContentAttempts) { }
    private final ConcurrentMap<Long, BookCrawlOwner> activeBookCrawls = new ConcurrentHashMap<>();

    /** Own the book before reading its existing chapter map, so that map cannot become stale. */
    public boolean beginBookCrawl(Long bookId) {
        return beginBookCrawl(bookId, Integer.MAX_VALUE);
    }

    public boolean beginBookCrawl(Long bookId, int maxContentAttempts) {
        if (maxContentAttempts < 1) throw new IllegalArgumentException("Chapter attempt limit must be positive");
        return bookId == null || activeBookCrawls.putIfAbsent(bookId,
            new BookCrawlOwner(Thread.currentThread(), maxContentAttempts)) == null;
    }

    public boolean isBookCrawlActive(Long bookId) {
        return bookId != null && activeBookCrawls.containsKey(bookId);
    }

    public void endBookCrawl(Long bookId) {
        if (bookId == null) return;
        BookCrawlOwner owner = activeBookCrawls.get(bookId);
        if (owner != null && owner.thread() == Thread.currentThread()) activeBookCrawls.remove(bookId, owner);
    }

    /** 首批已入库但整本尚未采完；重启后分类扫描仍须补齐这本新书。 */
    public void markPendingNewBook(Integer sourceId, Long bookId) {
        stringRedisTemplate.opsForSet().add(PENDING_NEW_BOOK_KEY + sourceId, bookId.toString());
    }

    public boolean isPendingNewBook(Integer sourceId, Long bookId) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
            .isMember(PENDING_NEW_BOOK_KEY + sourceId, bookId.toString()));
    }

    public void completePendingNewBook(Integer sourceId, Long bookId) {
        stringRedisTemplate.opsForSet().remove(PENDING_NEW_BOOK_KEY + sourceId, bookId.toString());
    }

    /**
     * 爬虫任务进度
     */
    private final Map<Long, Integer> crawlTaskProgress = new HashMap<>();

    /**
     * 获取爬虫任务进度
     */
    public Integer getCrawlTaskProgress(Long taskId) {
        return crawlTaskProgress.get(taskId);
    }

    /**
     * 移除爬虫任务进度
     */
    public void removeCrawlTaskProgress(Long taskId) {
        crawlTaskProgress.remove(taskId);
    }

    /**
     * 获取爬虫源采集的章节数量
     */
    public Long getCrawlSourceChapterCount(Integer sourceId) {
        return Optional.ofNullable(
            stringRedisTemplate.opsForValue().get(CRAWL_SOURCE_CHAPTER_COUNT_CACHE_KEY + sourceId)).map(v -> {
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    public void parseBook(RuleBean ruleBean, String bookId, CrawlBookHandler handler)
        throws InterruptedException {
        Book book = new Book();
        String bookDetailUrl = ruleBean.getBookDetailUrl().replace("{bookId}", bookId);
        String bookDetailHtml = crawlHttpClient.get(bookDetailUrl, ruleBean.getCharset());
        CrawlBookMetadata.Detail metadata = CrawlBookMetadata.read(ruleBean, bookId, bookDetailUrl, bookDetailHtml);
        String bookName = metadata.name();
        book.setBookName(bookName);
        book.setAuthorName(metadata.author());
        if (UukanshuSourceAdapter.supports(bookDetailUrl)) {
            book.setCatId(UukanshuSourceAdapter.categoryId(bookDetailHtml));
        }
        if (StringUtils.isNotBlank(ruleBean.getPicUrlPatten())) {
            Pattern picUrlPatten = PatternFactory.getPattern(ruleBean.getPicUrlPatten());
            Matcher picUrlMatch = picUrlPatten.matcher(bookDetailHtml);
            boolean isFindPicUrl = picUrlMatch.find();
            if (isFindPicUrl) {
                String picUrl = picUrlMatch.group(1);
                if (StringUtils.isNotBlank(picUrl) && StringUtils.isNotBlank(ruleBean.getPicUrlPrefix())) {
                    picUrl = ruleBean.getPicUrlPrefix() + picUrl;
                }
                //设置封面图片路径
                book.setPicUrl(picUrl);
            }
        }
        if (StringUtils.isNotBlank(ruleBean.getScorePatten())) {
            Pattern scorePatten = PatternFactory.getPattern(ruleBean.getScorePatten());
            Matcher scoreMatch = scorePatten.matcher(bookDetailHtml);
            boolean isFindScore = scoreMatch.find();
            if (isFindScore) {
                String score = scoreMatch.group(1);
                //设置评分
                book.setScore(Float.parseFloat(score));
            }
        }
        if (StringUtils.isNotBlank(ruleBean.getVisitCountPatten())) {
            Pattern visitCountPatten = PatternFactory.getPattern(ruleBean.getVisitCountPatten());
            Matcher visitCountMatch = visitCountPatten.matcher(bookDetailHtml);
            boolean isFindVisitCount = visitCountMatch.find();
            if (isFindVisitCount) {
                String visitCount = visitCountMatch.group(1);
                //设置访问次数
                book.setVisitCount(Long.parseLong(visitCount));
            }
        }

        // [审查12] 原来直接 substring(indexOf(...))，简介标记对不上就抛
        // StringIndexOutOfBoundsException 中断整本书解析。改为容错截取。
        String desc = extractDesc(bookDetailHtml, ruleBean);
        //过滤掉简介中的特殊标签
        desc = desc.replaceAll("<a[^<]+</a>", "")
            .replaceAll("<font[^<]+</font>", "")
            .replaceAll("<p>\\s*</p>", "")
            .replaceAll("<p>", "")
            .replaceAll("</p>", "<br/>");
        // 小说简介过滤
        String filterDesc = ruleBean.getFilterDesc();
        if (StringUtils.isNotBlank(filterDesc)) {
            String[] filterRules = filterDesc.replace("\r\n", "\n").split("\n");
            for (String filterRule : filterRules) {
                if (StringUtils.isNotBlank(filterRule)) {
                    desc = desc.replaceAll(filterRule, "");
                }
            }
        }
        // 去除小说简介前后空格
        desc = desc.trim();
        // 去除小说简介末尾冗余的小说名
        if (desc.endsWith(bookName)) {
            desc = desc.substring(0, desc.length() - bookName.length());
        }
        //设置书籍简介
        book.setBookDesc(desc);
        if (StringUtils.isNotBlank(ruleBean.getStatusPatten())) {
            Pattern bookStatusPatten = PatternFactory.getPattern(ruleBean.getStatusPatten());
            Matcher bookStatusMatch = bookStatusPatten.matcher(bookDetailHtml);
            boolean isFindBookStatus = bookStatusMatch.find();
            if (isFindBookStatus) {
                String bookStatus = bookStatusMatch.group(1);
                if (ruleBean.getBookStatusRule().get(bookStatus) != null) {
                    //设置更新状态
                    book.setBookStatus(ruleBean.getBookStatusRule().get(bookStatus));
                }
            }
        }

        if (StringUtils.isNotBlank(ruleBean.getUpadateTimePatten()) && StringUtils.isNotBlank(
            ruleBean.getUpadateTimeFormatPatten())) {
            Pattern updateTimePatten = PatternFactory.getPattern(ruleBean.getUpadateTimePatten());
            Matcher updateTimeMatch = updateTimePatten.matcher(bookDetailHtml);
            boolean isFindUpdateTime = updateTimeMatch.find();
            if (isFindUpdateTime) {
                String updateTime = updateTimeMatch.group(1);
                //设置更新时间
                try {
                    book.setLastIndexUpdateTime(
                        new SimpleDateFormat(ruleBean.getUpadateTimeFormatPatten()).parse(updateTime));
                } catch (ParseException e) {
                    log.error("解析最新章节更新时间出错", e);
                }

            }
        }

        if (book.getVisitCount() == null && book.getScore() != null) {
            //随机根据评分生成访问次数
            book.setVisitCount(RandomBookInfoUtil.getVisitCountByScore(book.getScore()));
        } else if (book.getVisitCount() != null && book.getScore() == null) {
            //随机根据访问次数生成评分
            book.setScore(RandomBookInfoUtil.getScoreByVisitCount(book.getVisitCount()));
        } else if (book.getVisitCount() == null) {
            //都没有，设置成固定值
            book.setVisitCount(Constants.VISIT_COUNT_DEFAULT);
            book.setScore(6.5f);
        }
        handler.handle(book);
    }

    public boolean parseBookIndexAndContent(String sourceBookId, Book book, RuleBean ruleBean, Integer sourceId,
        Map<Integer, BookIndex> existBookIndexMap, CrawlBookChapterHandler handler, CrawlSingleTask task)
        throws InterruptedException {

        Long bookId = book.getId();
        lastRun.remove();
        BookCrawlOwner owner = bookId == null ? null : activeBookCrawls.get(bookId);
        boolean ownScope = bookId != null && (owner == null || owner.thread() != Thread.currentThread());
        if (ownScope && !beginBookCrawl(bookId)) throw new CrawlBookBusyException(bookId);
        try {
            int maxContentAttempts = bookId == null ? Integer.MAX_VALUE : activeBookCrawls.get(bookId).maxContentAttempts();
            return parseOwnedBookIndexAndContent(sourceBookId, book, ruleBean, sourceId, existBookIndexMap,
                handler, task, maxContentAttempts);
        } finally {
            if (ownScope) endBookCrawl(bookId);
        }
    }

    private boolean parseOwnedBookIndexAndContent(String sourceBookId, Book book, RuleBean ruleBean, Integer sourceId,
        Map<Integer, BookIndex> existBookIndexMap, CrawlBookChapterHandler handler, CrawlSingleTask task,
        int maxContentAttempts)
        throws InterruptedException {

        if (task != null) {
            // 开始采集
            crawlTaskProgress.put(task.getId(), 0);
        }

        Date currentDate = new Date();

        List<BookIndex> indexList = new ArrayList<>();
        List<BookContent> contentList = new ArrayList<>();

        //读取目录：bookIndexPageUrl 非空时按模板逐页抓取并合并，否则维持单页抓取
        boolean indexSlicedPerPage = false;
        String indexListHtml;
        String indexListUrl = StringUtils.defaultString(StringUtils.isNotBlank(ruleBean.getBookIndexPageUrl())
            ? ruleBean.getBookIndexPageUrl().replace("{page}", "1") : ruleBean.getBookIndexUrl())
            .replace("{bookId}", sourceBookId);
        if (StringUtils.isNotBlank(ruleBean.getBookIndexPageUrl())) {
            indexListHtml = getPagedBookIndexHtml(sourceBookId, ruleBean);
            indexSlicedPerPage = true;
        }
        else {
            indexListHtml = crawlHttpClient.get(indexListUrl, ruleBean.getCharset());
        }

        if (indexListHtml == null) {
            runtimeMonitor.failure(sourceId, new CrawlMetadataException(CrawlMetadataException.Reason.CATALOG_UNAVAILABLE,
                indexListUrl, sourceBookId, book.getBookName()));
            log.error("目录页获取失败，放弃本次采集：bookId={}", sourceBookId);
            handler.handle(emptyChapterBean());
            collectionStore.catalogUnavailable(book.getId());
            return false;
        }

        if (StringUtils.isNotBlank(ruleBean.getBookIndexStart()) && !indexSlicedPerPage) {
            indexListHtml = sliceAfterMarker(indexListHtml, ruleBean.getBookIndexStart());
        }

        // [审查02] 目录 ID 与标题必须来自同一节点并按位置严格配对，禁止两个正则各自推进
        ParsedCatalog catalog = parseCatalog(indexListHtml, ruleBean, sourceBookId);
        List<ChapterRef> chapterRefs = catalog.chapters();
        if (chapterRefs.isEmpty()) {
            runtimeMonitor.failure(sourceId, new CrawlMetadataException(CrawlMetadataException.Reason.CATALOG_PAIR_MISMATCH,
                indexListUrl, sourceBookId, book.getBookName()).getMessage()
                + "；ID匹配=" + catalog.idMatches() + "；标题匹配=" + catalog.titleMatches());
            log.error("目录解析为空或配对失败，放弃本次采集：bookId={}", sourceBookId);
            handler.handle(emptyChapterBean());
            collectionStore.catalogUnavailable(book.getId());
            return false;
        }

        // [审查01] 章节身份以来源章节 ID 为准：先在整本范围内完成「目录条目 → 已存在章节」的
        // 一次性匹配（来源 ID 优先、目录位置仅在无来源 ID 时兜底），再进入抓取循环。
        // 这样源站开头插入章节或目录重排时，已有章节不会被按位置错配改写。
        runtimeMonitor.catalog(chapterRefs.size());
        StringBuilder signature = new StringBuilder(new tools.jackson.databind.ObjectMapper().writeValueAsString(ruleBean));
        int[] prefixEnds = new int[chapterRefs.size()];
        for (int p = 0; p < chapterRefs.size(); p++) {
            ChapterRef ref = chapterRefs.get(p);
            signature.append('\n').append(ref.id.length()).append(':').append(ref.id).append(':').append(ref.name.length()).append(':').append(ref.name);
            prefixEnds[p] = signature.length();
        }
        String signatureText = signature.toString();
        CrawlRefreshStore.Session refresh=refreshRun.get();
        String catalogHash=CrawlCollectionStore.hash(signatureText);
        if(refresh!=null && refresh.pendingFrom==null && refresh.hasCatalogHistory()
            && refresh.observed==chapterRefs.size() && Objects.equals(refresh.hash,catalogHash)
            && !Byte.valueOf((byte)1).equals(book.getBookStatus())
            && unchangedRevision(book.getId(),sourceId==null?0:sourceId,sourceBookId,catalogHash,chapterRefs.size(),n->CrawlCollectionStore.hash(signatureText.substring(0,prefixEnds[n-1])))) {
            refresh.observe(chapterRefs.size(),catalogHash,n->CrawlCollectionStore.hash(signatureText.substring(0,prefixEnds[n-1])));
            var unchangedAudit=collectionStore.open(book.getId(),sourceId==null?0:sourceId,sourceBookId,catalogHash,
                chapterRefs.size(),n->CrawlCollectionStore.hash(signatureText.substring(0,prefixEnds[n-1])));
            refresh.catalogFinished(unchangedAudit);
            lastRun.set(new RunProgress(false,false));
            return unchangedAudit.complete();
        }
        existBookIndexMap=resolveIndexes(existBookIndexMap);
        CrawlIndexStore.Snapshot snapshot=existBookIndexMap instanceof CrawlIndexStore.Snapshot value?value:null;
        java.util.function.IntFunction<String> prefix=n->CrawlCollectionStore.hash(signatureText.substring(0,prefixEnds[n-1]));
        CrawlCollectionStore.Audit audit=collectionStore.open(book.getId(),sourceId==null?0:sourceId,sourceBookId,catalogHash,chapterRefs.size(),prefix);
        Set<Integer> selected=null;
        int refreshFrom=0;
        boolean refreshObserved=false;
        boolean uniqueIds=chapterRefs.stream().map(ref->ref.id).distinct().count()==chapterRefs.size();
        if(snapshot!=null&&snapshot.canUseSparse(sourceId==null?0:sourceId,sourceBookId,catalogHash,chapterRefs.size(),prefix)&&uniqueIds) {
            if(refresh!=null) {refreshFrom=refresh.observe(chapterRefs.size(),catalogHash,prefix,snapshot.baselineCount());refreshObserved=true;}
            List<Integer> positions=new ArrayList<>();
            // Persisted bitsets choose a bounded slice. Unselected positions are never interpreted as missing rows.
            int unknown=0,missing=0;
            for(int pos=refreshFrom;pos<chapterRefs.size();pos++) {
                if(audit.ready.get(pos))continue;
                if(!audit.checked.get(pos)) {if(unknown>=(refresh==null?CrawlCollectionStore.VERIFY_BATCH:Math.min(100,maxContentAttempts)))continue;unknown++;}
                else {if(missing>=Math.min(25,maxContentAttempts))continue;missing++;}
                positions.add(pos);
            }
            if(snapshot.loadSlice(positions,positions.stream().map(pos->chapterRefs.get(pos).id).toList()))selected=new HashSet<>(positions);
        }
        if(snapshot!=null&&snapshot.invalidAudit()){audit.ready.clear();audit.checked.clear();audit.validationStarted=java.time.Instant.now();}
        boolean sparse=selected!=null;
        BookIndex[] matchedIndexes;
        if(sparse) {matchedIndexes=new BookIndex[chapterRefs.size()];for(int pos:selected)matchedIndexes[pos]=existBookIndexMap.get(pos);}
        else matchedIndexes=matchExistBookIndex(chapterRefs,existBookIndexMap);
        int bootstrapFrom=chapterRefs.size(),localPrefix=sparse?snapshot.baselineCount():existBookIndexMap.size();
        if(!sparse&&refresh!=null&&!refresh.hasCatalogHistory()&&localPrefix>0&&localPrefix<chapterRefs.size()&&chapterRefs.size()-localPrefix<=50) {
            boolean aligned=true;
            for(int pos=0;pos<localPrefix;pos++) {
                BookIndex old=existBookIndexMap.get(pos),matched=matchedIndexes[pos];
                if(old==null||matched==null||!Objects.equals(old.getId(),matched.getId())
                    ||!StringUtils.equals(StringUtils.deleteWhitespace(old.getIndexName()),StringUtils.deleteWhitespace(chapterRefs.get(pos).name))){aligned=false;break;}
            }
            if(aligned)bootstrapFrom=localPrefix;
        }
        if(refresh!=null&&!refreshObserved)refreshFrom=refresh.observe(chapterRefs.size(),catalogHash,prefix,bootstrapFrom);
        if(snapshot!=null&&!sparse)snapshot.certify(chapterRefs.stream().map(ref->ref.id).toList());
        audit.sourceCompleted = Byte.valueOf((byte) 1).equals(book.getBookStatus());
        int previousReady = audit.ready.cardinality(), previousChecked = audit.checked.cardinality();
        // Check a bounded slice of old bodies. Unknown chapters are never counted as ready.
        List<BookIndex> verify = new ArrayList<>();
        List<Integer> verifyPositions = new ArrayList<>();
        for (int p = 0; p < matchedIndexes.length; p++) {
            if(sparse&&!selected.contains(p))continue;
            if(refresh!=null && p<refreshFrom) continue;
            BookIndex old = matchedIndexes[p];
            if (old == null) { audit.mark(p, false); continue; }
            if (!StringUtils.equals(StringUtils.deleteWhitespace(old.getIndexName()),
                    StringUtils.deleteWhitespace(chapterRefs.get(p).name))) { audit.mark(p, false); continue; }
            if (!audit.checked.get(p) && verify.size() < (refresh==null?CrawlCollectionStore.VERIFY_BATCH:maxContentAttempts)) {
                verify.add(old); verifyPositions.add(p);
            }
        }
        Set<Long> readableIds = snapshot==null?collectionStore.readyChapters(book.getId(), verify):snapshot.read(()->collectionStore.readyChapters(book.getId(),verify));
        for (int n = 0; n < verify.size(); n++) audit.mark(verifyPositions.get(n), readableIds.contains(verify.get(n).getId()));
        // 匹配结果与原章节号不一致时，需要整本重排章节号；重排唯一键安全性由服务端保证
        List<BookIndex> reindexPlan = sparse?null:refresh==null?buildReindexPlan(sourceBookId, chapterRefs, existBookIndexMap,
            matchedIndexes):buildRefreshReindexPlan(chapterRefs,existBookIndexMap,matchedIndexes,refreshFrom);
        if(snapshot!=null&&reindexPlan!=null&&!reindexPlan.isEmpty())snapshot.invalidateAlignment();
        boolean reindexPlanSent = false;
        // [审查01] 标题一致、不重新抓正文，但身份信息（来源章节 ID / 章节号）需要补写的章节
        List<BookIndex> metaIndexList = new ArrayList<>();
        // [审查01] 老数据（source_index_id 为空）只能按目录位置兜底匹配。若本次目录条数与库中
        // 章节数不一致，说明源站插入/删除了章节、位置已经位移，此时按位置推断出的身份可能对错行；
        // 一旦写入就会永久绑定错配（之后按身份匹配且标题一致，再也不会纠正），所以只有结构一致
        // （条数相同）时才允许回填身份。
        boolean identityBackfillSafe = !sparse&&chapterRefs.size() == existBookIndexMap.size();
        int identityBackfillSkipped = 0;
        // [审查01] 目录末条对应的本地章节：最新章节指针必须指向「目录最后一条」，
        // 而不是「本次最后一次抓取成功的章节」——头部插入新章时后者会指向序章。
        int lastCatalogPos = chapterRefs.size() - 1;
        Long lastCatalogIndexId = null;
        String lastCatalogIndexName = null;
        boolean lastCatalogIndexKnown = false;

        //总字数：book.wordCount 为空时（例如单本任务给已存在的书补章时只查了 id），
        //改用已存在章节的字数之和作为基准，否则增量汇总会算出负值。
        int totalWordCount = sparse?0:book.getWordCount() == null
            ? existBookIndexMap.values().stream()
                .mapToInt(idx -> idx.getWordCount() == null ? 0 : idx.getWordCount()).sum()
            : book.getWordCount();

        int successCount = 0;
        int failedCount = 0;
        int contentAttempts = 0;
        // 本次是否为新书入库：新书在首次写库时就会 insert 主表，必须带上 update_time，
        // 否则 book.update_time 无默认值会直接抛 SQLException。
        boolean newBook = !sparse&&existBookIndexMap.isEmpty();
        long nextBatchFlushNanos = batchTimeNanos() + CRAWL_SAVE_MAX_WAIT_NANOS;
        for (int indexNum = 0; indexNum < chapterRefs.size(); indexNum++) {
            if(sparse&&!selected.contains(indexNum))continue;
            // Fast refresh checks the catalog, then only a newly appended tail. Historical
            // verification and missing bodies stay on the background worker. Only rows
            // blocking the new tail are relocated by the fast path.
            if(refresh!=null && indexNum<refreshFrom) continue;
            ChapterRef ref = chapterRefs.get(indexNum);
            BookIndex hasIndex = matchedIndexes[indexNum];
            String indexName = ref.name;
            runtimeMonitor.chapter(indexNum + 1, indexName);

            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Book crawl interrupted");
            // A same-title catalog row is insufficient: only verified readable content may be reused.
            // deleteWhitespace(null) 返回 null，用 StringUtils.equals 避免 index_name 为空时 NPE 中断整本
            if (hasIndex != null && audit.ready.get(indexNum) && StringUtils.equals(StringUtils.deleteWhitespace(hasIndex.getIndexName()),
                StringUtils.deleteWhitespace(indexName))) {
                // [审查01] 即使跳过抓取，也要把来源章节 ID / 章节号补写到已有行上，
                // 否则老数据永远只有位置没有身份，下次目录重排仍会按位置错配。
                if (refresh==null && needIndexMetaUpdate(hasIndex, ref.id, indexNum)) {
                    if (StringUtils.isNotBlank(hasIndex.getSourceIndexId()) || identityBackfillSafe) {
                        metaIndexList.add(buildIndexMeta(hasIndex.getId(), ref.id, indexNum, currentDate));
                    }
                    else {
                        // 位置兜底推断出的身份不可信（目录条数与库中章节数不一致），本次不回填
                        identityBackfillSkipped++;
                    }
                }
                lastCatalogIndexId = hasIndex.getId();
                lastCatalogIndexName = hasIndex.getIndexName();
                lastCatalogIndexKnown = true;
                successCount++;
                continue;
            }

            // Keep scanning metadata after the download budget: later valid chapters remain reusable,
            // while a legacy book's next unchecked slice resumes from the persisted bitsets.
            if ((hasIndex != null && !audit.checked.get(indexNum)) || contentAttempts >= maxContentAttempts) continue;
            contentAttempts++;
            String contentUrl = buildContentUrl(ruleBean.getBookContentUrl(), sourceBookId, ref.id);
            if (contentUrl == null) {
                runtimeMonitor.failure(sourceId, "章节地址生成失败");
                log.error("内容页URL生成失败：bookId={} indexId={}", sourceBookId, ref.id);
                failedCount++;
                audit.failed++;
                lastCatalogIndexKnown = false;
                continue;
            }

            // [审查12] 单章失败不再抛异常中断整本
            ChapterFetchResult fetched = fetchChapterContent(contentUrl, ruleBean);
            if (fetched.failed) {
                var failure=fetched.failure==null?new com.java2nb.novel.utils.CrawlFetchFailure(
                    com.java2nb.novel.utils.CrawlFetchFailure.Reason.INVALID_PAGE,0,contentUrl,0,false):fetched.failure;
                // Cached missing pages use no network capacity and must not consume the slice's download budget.
                if(failure.deferred()) contentAttempts--;
                runtimeMonitor.failure(sourceId, failure.describe(sourceBookId,book.getBookName(),ref.id,indexName));
                log.error("章节抓取或解析失败：{}", contentUrl);
                failedCount++;
                audit.failed++;
                lastCatalogIndexKnown = false;
                continue;
            }
            if (fetched.skipped) {
                // Deferred/empty bodies are holes, never successful completion.
                audit.deferred++;
                lastCatalogIndexKnown = false;
                continue;
            }

            String content = fetched.content;

            BookIndex bookIndex = new BookIndex();
            bookIndex.setIndexName(indexName);
            bookIndex.setIndexNum(indexNum);
            // [审查01] 记录来源章节 ID，作为下次采集时的章节身份
            bookIndex.setSourceIndexId(ref.id);
            int wordCount = StringUtil.getStrValidWordCount(content);
            bookIndex.setWordCount(wordCount);

            BookContent bookContent = new BookContent();
            bookContent.setContent(content);

            if (hasIndex != null) {
                //章节更新
                bookIndex.setId(hasIndex.getId());
                bookContent.setIndexId(hasIndex.getId());
                //计算总字数
                totalWordCount = totalWordCount + wordCount - (hasIndex.getWordCount() == null ? 0 : hasIndex.getWordCount());
            }
            else {
                //章节插入
                Long indexId = ID_WORKER.nextId();
                bookIndex.setId(indexId);
                bookIndex.setBookId(book.getId());
                bookIndex.setCreateTime(currentDate);
                bookContent.setIndexId(indexId);
                //计算总字数
                totalWordCount += wordCount;
            }
            bookIndex.setUpdateTime(currentDate);

            indexList.add(bookIndex);
            contentList.add(bookContent);
            successCount++;
            audit.mark(indexNum, true);
            lastCatalogIndexId = bookIndex.getId();
            lastCatalogIndexName = bookIndex.getIndexName();
            lastCatalogIndexKnown = true;

            // [审查15] 分批提交，避免整本生成一条大 INSERT。
            // 提交前先把书籍级字段写全：新书首批就会插入主表，缺 update_time 会直接失败。
            // 元数据（身份补写）条目也要计入批量上限，避免长时间只累积内存。
            if (indexList.size() + metaIndexList.size() >= CRAWL_SAVE_BATCH_SIZE
                || batchTimeNanos() - nextBatchFlushNanos >= 0) {
                book.setWordCount(totalWordCount);
                if (newBook && book.getUpdateTime() == null) {
                    book.setUpdateTime(currentDate);
                }
                // [审查01] 重排表只在第一次真正提交时下发一次
                List<BookIndex> planToSend = (!reindexPlanSent && reindexPlan != null) ? reindexPlan : null;
                if (flushBatch(book, indexList, contentList, metaIndexList, planToSend, chapterRefs.size(), handler, sourceId, task,
                    false) && planToSend != null) {
                    reindexPlanSent = true;
                }
                saveAudit(audit,existBookIndexMap);
                nextBatchFlushNanos = batchTimeNanos() + CRAWL_SAVE_MAX_WAIT_NANOS;
            }
        }

        if (identityBackfillSkipped > 0) {
            log.warn("目录条数 {} 与已存在章节数 {} 不一致（源站增删过章节），为免身份错配，"
                    + "本次跳过 {} 章的来源章节 ID 回填", chapterRefs.size(), existBookIndexMap.size(),
                identityBackfillSkipped);
        }

        // [审查04] 最新章节指针只在「目录最后一条相对库中已有内容发生了新增/位移」时前进，
        // 补旧章不会让指针回退。
        // [审查01] 但指针取值必须用「目录最后一条」对应的本地章节：原来取「本次最后一次抓取
        // 成功的章节」，在目录头部插入新章、其余章节按身份跳过抓取时会指向第一条（序章）。
        int previousMaxNum = sparse?snapshot.baselineCount()-1:existBookIndexMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        BookIndex lastCatalogMatch = matchedIndexes[lastCatalogPos];
        boolean lastCatalogIsNew = lastCatalogMatch == null;
        boolean lastCatalogMoved = lastCatalogMatch != null
            && !Integer.valueOf(lastCatalogPos).equals(lastCatalogMatch.getIndexNum());
        boolean lastIndexAdvanced = (lastCatalogPos > previousMaxNum) || lastCatalogIsNew || lastCatalogMoved;
        if (lastIndexAdvanced && lastCatalogIndexKnown && lastCatalogIndexId != null) {
            book.setLastIndexId(lastCatalogIndexId);
            book.setLastIndexName(lastCatalogIndexName);
            book.setLastIndexUpdateTime(currentDate);
            book.setUpdateTime(currentDate);
        }
        else if (newBook && book.getUpdateTime() == null) {
            // 新书即使目录没有增长也必须补齐 update_time
            book.setUpdateTime(currentDate);
        }

        // 收尾：书籍级字段定稿后必须再提交一次（force=true），
        // 否则批次刚好在边界结束时最新章节信息不会落库。
        book.setWordCount(totalWordCount);
        List<BookIndex> finalPlan = (!reindexPlanSent && reindexPlan != null) ? reindexPlan : null;
        boolean completedAtSource=Byte.valueOf((byte)1).equals(book.getBookStatus());
        if(refresh==null || !indexList.isEmpty() || !metaIndexList.isEmpty() || finalPlan!=null || completedAtSource)
            flushBatch(book, indexList, contentList, metaIndexList, finalPlan, chapterRefs.size(), handler, sourceId, task, true);
        // An unchanged fast catalog check has no new body evidence to persist. Keep
        // historical verification intact and avoid rewriting the book/audit every poll.
        if((refresh==null || contentAttempts>0 || !verify.isEmpty() || completedAtSource)
            && (sparse || !existBookIndexMap.isEmpty() || successCount>0)) saveAudit(audit,existBookIndexMap);
        if(refresh!=null) refresh.catalogFinished(audit);
        boolean madeProgress = successCount > 0 && (audit.ready.cardinality() > previousReady || audit.checked.cardinality() > previousChecked);
        lastRun.set(new RunProgress(madeProgress, !audit.complete() && madeProgress && audit.failed == 0 && audit.deferred == 0));

        // [审查03] 只要有章节失败，就不能判定成功；重试时已入库章节会按标题跳过
        if (failedCount > 0) {
            log.error("本次采集存在失败章节：bookId={} 目录总数={} 成功={} 失败={}",
                sourceBookId, chapterRefs.size(), successCount, failedCount);
            return false;
        }

        if (successCount == 0 && refresh==null && !audit.complete()) {
            log.error("本次采集没有取到任何章节：bookId={} 目录总数={}", sourceBookId, chapterRefs.size());
            return false;
        }

        log.info("采集完整性：bookId={}, expected={}, ready={}, missing={}, unchecked={}, failed={}, deferred={}",
            book.getId(), audit.expected, audit.ready.cardinality(), audit.missing(), audit.unchecked(), audit.failed, audit.deferred);
        return audit.complete();

    }

    /**
     * [审查15] 把一批章节交给回调落库，成功之后再累计统计。
     * 传出去的是副本并立即清空累积列表，保证每条章节只提交一次。
     *
     * @param metaIndexList [审查01] 只回写身份信息（来源章节 ID / 章节号）的章节，不计入采集统计
     * @param reindexPlan   [审查01] 章节号重排表，仅需下发一次（由调用方保证）
     * @param force 即使没有待写章节也调用一次回调。收尾时必须为 true：
     *              分页采集的章节数若刚好是批次边界（100、200…），最后一次提交会是空的，
     *              此时最新章节/更新时间只存在于这次调用里，直接 return 会让书页「最新章节」永远为空。
     * @return 是否真正调用了回调（调用方据此判断重排表是否已下发）
     */
    private boolean flushBatch(Book book, List<BookIndex> indexList, List<BookContent> contentList,
        List<BookIndex> metaIndexList, List<BookIndex> reindexPlan, int sourceCatalogSize, CrawlBookChapterHandler handler,
        Integer sourceId, CrawlSingleTask task, boolean force)
        throws InterruptedException {
        if (indexList.isEmpty() && metaIndexList.isEmpty() && !force) {
            return false;
        }
        List<BookIndex> indexes = new ArrayList<>(indexList);
        List<BookContent> contents = new ArrayList<>(contentList);
        List<BookIndex> metas = new ArrayList<>(metaIndexList);
        indexList.clear();
        contentList.clear();
        metaIndexList.clear();

        handler.handle(new ChapterBean() {{
            setBookIndexList(indexes);
            setBookContentList(contents);
            setBookIndexMetaList(metas);
            setReindexList(reindexPlan);
            setCatalogSize(sourceCatalogSize);
        }});

        // [审查14] 统计与进度在提交成功之后才累加，表达「已入库」而不是「已解析」。
        runtimeMonitor.saved(sourceId, indexes.size());
        // 只回写身份的章节不计入采集数量。
        if (indexes.isEmpty()) {
            return true;
        }
        if (sourceId != null) {
            stringRedisTemplate.opsForValue().increment(CRAWL_SOURCE_CHAPTER_COUNT_CACHE_KEY + sourceId,
                indexes.size());
        }
        if (task != null) {
            Integer done = crawlTaskProgress.get(task.getId());
            crawlTaskProgress.put(task.getId(), (done == null ? 0 : done) + indexes.size());
        }
        return true;
    }

    /**
     * [审查01] 章节身份匹配：来源章节 ID 优先，目录位置兜底。
     *
     * <p>规则：
     * <ol>
     *   <li>先按 source_index_id 找未被占用过的已存在章节，命中即认为身份相同（不管章节号是否变化）；</li>
     *   <li>来源 ID 为空（老数据）或站点本身存在重复章节 ID 时，退回按目录位置匹配，
     *       但只接受「该位置上尚未被占用、且其身份要么为空要么与当前条目相同」的行，
     *       绝不覆盖属于其它章节的行；</li>
     *   <li>两者都不命中即视为新章节。</li>
     * </ol>
     *
     * @return 与 chapterRefs 等长的数组，元素为命中的已存在章节或 null
     */
    private BookIndex[] matchExistBookIndex(List<ChapterRef> chapterRefs,
        Map<Integer, BookIndex> existBookIndexMap) {
        BookIndex[] matched = new BookIndex[chapterRefs.size()];
        if (existBookIndexMap.isEmpty()) {
            return matched;
        }
        // 同一来源 ID 可能对应多个目录条目（本站实测存在重复章节 ID），按章节号顺序排队，先到先得
        List<BookIndex> exists = new ArrayList<>(existBookIndexMap.values());
        exists.sort(Comparator.comparingInt(idx -> idx.getIndexNum() == null ? Integer.MAX_VALUE
            : idx.getIndexNum()));
        Map<String, Deque<BookIndex>> bySourceId = new HashMap<>();
        for (BookIndex exist : exists) {
            if (StringUtils.isNotBlank(exist.getSourceIndexId())) {
                bySourceId.computeIfAbsent(exist.getSourceIndexId(), k -> new ArrayDeque<>()).addLast(exist);
            }
        }

        Set<Long> usedIds = new HashSet<>();
        int sourceHit = 0;
        int positionFallback = 0;
        for (int i = 0; i < chapterRefs.size(); i++) {
            String sourceIndexId = chapterRefs.get(i).id;
            BookIndex hit = null;

            Deque<BookIndex> candidates = bySourceId.get(sourceIndexId);
            while (candidates != null && !candidates.isEmpty() && usedIds.contains(candidates.peekFirst().getId())) {
                candidates.pollFirst();
            }
            if (candidates != null && !candidates.isEmpty()) {
                hit = candidates.pollFirst();
                sourceHit++;
            } else {
                BookIndex byPosition = existBookIndexMap.get(i);
                if (byPosition != null && !usedIds.contains(byPosition.getId())
                    && (StringUtils.isBlank(byPosition.getSourceIndexId())
                    || byPosition.getSourceIndexId().equals(sourceIndexId))) {
                    hit = byPosition;
                    positionFallback++;
                }
            }

            if (hit != null) {
                matched[i] = hit;
                usedIds.add(hit.getId());
            }
        }
        log.info("章节身份匹配完成：目录 {} 条，来源ID命中 {}，位置兜底 {}，视为新增 {}",
            chapterRefs.size(), sourceHit, positionFallback, chapterRefs.size() - sourceHit - positionFallback);
        return matched;
    }

    /** Relocate only rows obstructing an appended tail; preserve all historical IDs and bodies. */
    private List<BookIndex> buildRefreshReindexPlan(List<ChapterRef> refs, Map<Integer,BookIndex> existing,
        BookIndex[] matched, int from) {
        if(from>=refs.size() || existing.isEmpty()) return null;
        Map<Long,Integer> livePositions=new HashMap<>();
        for(int p=0;p<matched.length;p++) if(matched[p]!=null) livePositions.put(matched[p].getId(),p);
        Map<Long,Integer> moves=new LinkedHashMap<>();
        Deque<Integer> slots=new ArrayDeque<>();
        for(int p=from;p<refs.size();p++) {
            slots.addLast(p);
            if(matched[p]!=null && !Integer.valueOf(p).equals(matched[p].getIndexNum()))
                moves.put(matched[p].getId(),p);
        }
        int archive=Math.max(refs.size(),Math.addExact(existing.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1),1));
        Set<Integer> visited=new HashSet<>();
        while(!slots.isEmpty()) {
            int slot=slots.removeFirst();
            if(!visited.add(slot)) continue;
            BookIndex blocker=existing.get(slot);
            if(blocker==null) continue;
            Integer target=livePositions.get(blocker.getId());
            if(target==null) {
                // Retain historical identity and body beyond both the live catalog and
                // every occupied local number. Moving one blocker must not shift all history.
                moves.put(blocker.getId(),archive);
                archive=Math.addExact(archive,1);
            } else if(target!=slot) {
                moves.put(blocker.getId(),target);
                slots.addLast(target);
            }
        }
        if(moves.isEmpty()) return null;
        List<BookIndex> plan=new ArrayList<>(moves.size());
        moves.forEach((id,number)->{var item=new BookIndex();item.setId(id);item.setIndexNum(number);plan.add(item);});
        return plan;
    }

    /** Reconcile the complete catalog on background crawls, retaining unmatched history after it. */
    private List<BookIndex> buildReindexPlan(String sourceBookId, List<ChapterRef> chapterRefs,
        Map<Integer, BookIndex> existBookIndexMap, BookIndex[] matchedIndexes) {
        if (existBookIndexMap.isEmpty()) {
            return null;
        }
        List<BookIndex> rows = new ArrayList<>(existBookIndexMap.values());
        rows.sort(Comparator.comparingInt(idx -> idx.getIndexNum() == null ? Integer.MAX_VALUE
            : idx.getIndexNum()));

        Map<Long, Integer> targetNum = new HashMap<>();
        for (int i = 0; i < matchedIndexes.length; i++) {
            if (matchedIndexes[i] != null) {
                targetNum.put(matchedIndexes[i].getId(), i);
            }
        }
        int nextNum = chapterRefs.size();
        boolean changed = false;
        for (BookIndex row : rows) {
            Integer target = targetNum.get(row.getId());
            if (target == null) {
                target = nextNum++;
                targetNum.put(row.getId(), target);
            }
            if (!target.equals(row.getIndexNum())) {
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }

        List<BookIndex> plan = new ArrayList<>(rows.size());
        for (BookIndex row : rows) {
            BookIndex item = new BookIndex();
            item.setId(row.getId());
            item.setIndexNum(targetNum.get(row.getId()));
            plan.add(item);
        }
        log.warn("目录顺序与库中章节号不一致，本次将重排章节号：bookId={} 目录 {} 条，重排 {} 行",
            sourceBookId, chapterRefs.size(), plan.size());
        return plan;
    }

    /**
     * [审查01] 判断已存在章节是否需要在跳过正文抓取的情况下回写身份信息。
     */
    private boolean needIndexMetaUpdate(BookIndex hasIndex, String sourceIndexId, int indexNum) {
        return !Integer.valueOf(indexNum).equals(hasIndex.getIndexNum())
            || !StringUtils.equals(sourceIndexId, hasIndex.getSourceIndexId());
    }

    /**
     * [审查01] 构造只带身份信息的章节对象，交给服务端按主键做选择性更新。
     */
    private BookIndex buildIndexMeta(Long id, String sourceIndexId, int indexNum, Date currentDate) {
        BookIndex meta = new BookIndex();
        meta.setId(id);
        meta.setSourceIndexId(sourceIndexId);
        meta.setIndexNum(indexNum);
        meta.setUpdateTime(currentDate);
        return meta;
    }

    private ChapterBean emptyChapterBean() {
        return new ChapterBean() {{
            setBookIndexList(new ArrayList<>(0));
            setBookContentList(new ArrayList<>(0));
        }};
    }

    /**
     * [审查02] 目录 ID 与标题从同一节点配对提取。
     * 先取出两组匹配的区间，再按位置把「ID 匹配」与「落在该 ID 与下一个 ID 之间」的「标题匹配」配对。
     * 数量不一致、配对失败或 ID 重复时返回空列表，由调用方放弃本次采集，绝不把错配结果写库。
     */
    private record ParsedCatalog(List<ChapterRef> chapters, int idMatches, int titleMatches, int recoveredTitles) {}

    private ParsedCatalog parseCatalog(String indexListHtml, RuleBean ruleBean, String sourceBookId) {
        List<int[]> idSpans = new ArrayList<>();
        List<String> idValues = new ArrayList<>();
        Matcher idMatcher = PatternFactory.getPattern(ruleBean.getIndexIdPatten()).matcher(indexListHtml);
        while (idMatcher.find()) {
            idSpans.add(new int[] {idMatcher.start(), idMatcher.end()});
            idValues.add(idMatcher.group(1));
        }

        List<int[]> nameSpans = new ArrayList<>();
        List<String> nameValues = new ArrayList<>();
        Matcher nameMatcher = PatternFactory.getPattern(ruleBean.getIndexNamePatten()).matcher(indexListHtml);
        while (nameMatcher.find()) {
            nameSpans.add(new int[] {nameMatcher.start(), nameMatcher.end()});
            nameValues.add(nameMatcher.group(1));
        }

        boolean recovery = biquge365Catalog(ruleBean, sourceBookId);
        if (idValues.isEmpty() || nameValues.size() > idValues.size() || !recovery && idValues.size() != nameValues.size()) {
            log.error("目录 ID 与标题匹配数量不一致：id={} name={}", idValues.size(), nameValues.size());
            return new ParsedCatalog(List.of(), idValues.size(), nameValues.size(), 0);
        }

        List<ChapterRef> pairs = new ArrayList<>(idValues.size());
        Set<String> seenIds = new HashSet<>();
        int nameCursor = 0;
        int duplicateCount = 0;
        int recovered = 0;
        for (int i = 0; i < idValues.size(); i++) {
            int idStart = idSpans.get(i)[0];
            int nextIdStart = (i + 1 < idSpans.size()) ? idSpans.get(i + 1)[0] : Integer.MAX_VALUE;

            while (nameCursor < nameSpans.size() && nameSpans.get(nameCursor)[0] < idStart) {
                nameCursor++;
            }
            String name = null;
            String fragment=recovery?indexListHtml.substring(idStart,Math.min(indexListHtml.length(),nextIdStart)):null;
            if(recovery && requiresBiquge365TitleRecovery(fragment)) {
                name=recoverBiquge365Title(fragment,sourceBookId,idValues.get(i));
                if(nameCursor<nameSpans.size() && nameSpans.get(nameCursor)[0]<nextIdStart) nameCursor++;
                if(name!=null) recovered++;
            } else if (nameCursor < nameSpans.size() && nameSpans.get(nameCursor)[0] < nextIdStart) {
                name = nameValues.get(nameCursor++);
            } else if (recovery) {
                name = recoverBiquge365Title(indexListHtml.substring(idStart, Math.min(indexListHtml.length(), nextIdStart)),
                    sourceBookId, idValues.get(i));
                if (name != null) recovered++;
            }
            if (name == null) {
                log.error("目录第 {} 条无法配对标题，放弃本次解析", i + 1);
                return new ParsedCatalog(List.of(), idValues.size(), nameValues.size(), recovered);
            }
            String id = idValues.get(i);
            if (!seenIds.add(id)) {
                // 部分站点同一章节 URL 会对应多个章节名（香书小说即如此：第四章与第五章指向同一
                // /read/{bookId}/{indexId}.html），这是站点自身的数据特点，不能当作解析错误放弃整本。
                duplicateCount++;
            }
            pairs.add(new ChapterRef(id, name));
        }
        if (duplicateCount > 0) {
            log.warn("目录存在 {} 个重复章节 ID（站点自身如此，已继续处理），目录共 {} 条",
                duplicateCount, pairs.size());
        }
        return new ParsedCatalog(pairs, idValues.size(), nameValues.size(), recovered);
    }

    private static boolean biquge365Catalog(RuleBean rule, String sourceBookId) {
        if (sourceBookId == null || !sourceBookId.matches("[0-9]{1,32}")) return false;
        try {
            java.net.URI uri = java.net.URI.create(rule.getBookIndexUrl().replace("{bookId}", sourceBookId));
            return "https".equalsIgnoreCase(uri.getScheme()) && "www.biquge365.net".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawQuery() == null && uri.getRawFragment() == null
                && uri.getPath().equals("/newbook/" + sourceBookId + "/");
        } catch (RuntimeException invalid) { return false; }
    }

    /** The source injects this comment into some title attributes, breaking its display label. */
    private static boolean requiresBiquge365TitleRecovery(String fragment) {
        int end=fragment.indexOf("</li>");
        if(end<0) return false;
        var title=PatternFactory.getPattern("\\btitle=\"([^\"]*)\"").matcher(fragment.substring(0,end+5));
        return title.find() && (title.group(1).contains("<") || title.group(1).contains(">"));
    }

    private static String recoverBiquge365Title(String fragment, String bookId, String chapterId) {
        int end = fragment.indexOf("</li>");
        if (end < 0) return null;
        var anchor = org.jsoup.Jsoup.parseBodyFragment(fragment.substring(0, end + 5)).selectFirst("li > a[href]");
        if (anchor == null || !anchor.attr("href").equals("/chapter/" + bookId + "/" + chapterId + ".html")) return null;
        String title = anchor.attr("title"), marker = "<!--名称：-->";
        int start = title.lastIndexOf(marker);
        String name;
        if(start>=0) name=CrawlBookMetadata.text(title.substring(start+marker.length()));
        else {
            // Literal angle brackets in Chinese titles are not HTML elements. Read the verified
            // anchor's own title attribute, and require its visible label to agree exactly.
            if(!title.contains("<") && !title.contains(">")) return null;
            name=org.springframework.web.util.HtmlUtils.htmlUnescape(title).replaceAll("\\s+"," ").strip();
            if(!name.equals(anchor.text().replaceAll("\\s+"," ").strip())) return null;
        }
        return name.isBlank() || name.codePointCount(0, name.length()) > 100 ? null : name;
    }

    /**
     * 按 descStart / descEnd 字面量截取简介；标记缺失返回空串而不是抛异常。
     * [审查12] 原来直接用 substring(indexOf(...))，标记对不上会抛
     * StringIndexOutOfBoundsException，导致整本书解析中断。
     */
    private String extractDesc(String bookDetailHtml, RuleBean ruleBean) {
        if (StringUtils.isBlank(ruleBean.getDescStart()) || StringUtils.isBlank(ruleBean.getDescEnd())) {
            return "";
        }
        int start = bookDetailHtml.indexOf(ruleBean.getDescStart());
        if (start < 0) {
            log.warn("简介开始标记未找到，简介置空：{}", ruleBean.getDescStart());
            return "";
        }
        String rest = bookDetailHtml.substring(start + ruleBean.getDescStart().length());
        int end = rest.indexOf(ruleBean.getDescEnd());
        return end >= 0 ? rest.substring(0, end) : rest;
    }

    /**
     * 生成内容页 URL，并处理 {cal_...} 计算规则。
     * [审查17] 截断时使用被截取那个 ID 自身的长度，而不是书籍 ID 的长度。
     */
    private String buildContentUrl(String contentUrlTpl, String sourceBookId, String sourceIndexId) {
        String bookContentUrl = contentUrlTpl;
        int calStart = bookContentUrl.indexOf("{cal_");
        if (calStart != -1) {
            int calEnd = bookContentUrl.indexOf('}', calStart);
            if (calEnd == -1) {
                log.error("内容页URL中 {{cal_ 缺少右括号：{}", bookContentUrl);
                return null;
            }
            String calStr = bookContentUrl.substring(calStart, calEnd);
            String[] calArr = calStr.split("_");
            if (calArr.length < 4) {
                log.error("内容页URL计算规则参数不足：{}", calStr);
                return null;
            }
            int calType = Integer.parseInt(calArr[1]);
            if (calType == 1) {
                int x = Integer.parseInt(calArr[2]);
                int y = Integer.parseInt(calArr[3]);
                String source = (x == 1) ? sourceBookId : sourceIndexId;
                if (y < 0 || y > source.length()) {
                    log.error("内容页URL计算规则 y={} 超出 {} 的长度 {}", y, source, source.length());
                    return null;
                }
                String calResult = source.substring(0, source.length() - y);
                if (calResult.isEmpty()) {
                    calResult = "0";
                }
                bookContentUrl = bookContentUrl.replace(calStr + "}", calResult);
            }
        }
        return bookContentUrl.replace("{bookId}", sourceBookId).replace("{indexId}", sourceIndexId);
    }

    /**
     * 抓取并解析单章正文。
     * [审查12] 标记缺失返回失败结果而不是抛 StringIndexOutOfBoundsException；
     * 站点提示「正在手打中」或正文为空时返回 skipped，既不算成功也不算失败，留待下次采集。
     */
    private ChapterFetchResult fetchChapterContent(String contentUrl, RuleBean ruleBean)
        throws InterruptedException {
        String contentHtml = crawlHttpClient.get(contentUrl, ruleBean.getCharset());
        if (contentHtml == null) {
            return ChapterFetchResult.fail(crawlHttpClient.lastFailure());
        }
        if (contentHtml.contains("正在手打中")) {
            log.warn("站点提示正在手打中，跳过该章：{}", contentUrl);
            return ChapterFetchResult.skip();
        }
        String content = getPagedContent(contentUrl, contentHtml, ruleBean);
        String filterContent = ruleBean.getFilterContent();
        if (StringUtils.isNotBlank(filterContent)) {
            String[] filterRules = filterContent.replace("\r\n", "\n").split("\n");
            for (String filterRule : filterRules) {
                if (StringUtils.isNotBlank(filterRule)) {
                    content = content.replaceAll(filterRule, "");
                }
            }
        }
        content = removeTrailingBrTags(content);
        if (!CrawlCollectionStore.readable(content)) {
            log.warn("章节正文为空，跳过该章：{}", contentUrl);
            return ChapterFetchResult.skip();
        }
        return ChapterFetchResult.ok(content);
    }

    /**
     * 删除字符串末尾的所有 <br> 类似标签（允许各种空格）
     */
    public static String removeTrailingBrTags(String str) {
        return str.replaceAll("(?i)(?:\\s*<\\s*br\\s*/?\\s*>)++(?:\\s|\\u3000)*$", "");
    }

    /**
     * 目录页最大翻页数保护，避免规则异常导致无限翻页
     */
    private static final int MAX_BOOK_INDEX_PAGE = 200;

    /**
     * 章节内容页最大翻页数保护
     */
    private static final int MAX_CONTENT_PAGE = 30;

    /**
     * 按 bookIndexPageUrl 模板逐页抓取目录并合并。
     * 每一页都会先按 bookIndexStart 截断，避免把每页顶部的「最新章节」区块重复计入
     * 章节列表 —— 那些 &lt;li&gt; 与正文章节列表结构相同，会污染 indexId/indexName 正则。
     */
    private String getPagedBookIndexHtml(String sourceBookId, RuleBean ruleBean) throws InterruptedException {
        String pageUrlTpl = ruleBean.getBookIndexPageUrl();

        String firstUrl = pageUrlTpl.replace("{bookId}", sourceBookId).replace("{page}", "1");
        String firstHtml = crawlHttpClient.get(firstUrl, ruleBean.getCharset());
        if (firstHtml == null) {
            return null;
        }

        int totalPage = 1;
        if (StringUtils.isNotBlank(ruleBean.getBookIndexTotalPagePatten())) {
            Matcher pageMatcher = PatternFactory.getPattern(ruleBean.getBookIndexTotalPagePatten())
                .matcher(firstHtml);
            while (pageMatcher.find()) {
                try {
                    int page = Integer.parseInt(pageMatcher.group(1));
                    if (page > totalPage) {
                        totalPage = page;
                    }
                }
                catch (NumberFormatException ignored) {
                    // 忽略无法解析的页码
                }
            }
        }
        if (totalPage > MAX_BOOK_INDEX_PAGE) {
            log.warn("目录总页数 {} 超过保护上限 {}，保留原内容并等待修正规则", totalPage, MAX_BOOK_INDEX_PAGE);
            return null;
        }

        StringBuilder merged = new StringBuilder(sliceAfterMarker(firstHtml, ruleBean.getBookIndexStart()));
        for (int page = 2; page <= totalPage; page++) {
            String url = pageUrlTpl.replace("{bookId}", sourceBookId).replace("{page}", String.valueOf(page));
            String html = crawlHttpClient.get(url, ruleBean.getCharset());
            if (html == null) {
                return null;
            }
            merged.append('\n').append(sliceAfterMarker(html, ruleBean.getBookIndexStart()));
        }
        log.info("目录分页抓取完成：sourceBookId={} 共 {} 页", sourceBookId, totalPage);
        return merged.toString();
    }

    /**
     * 取章节正文，支持章内分页。
     * 按 contentNextPagePatten 找「下一页」，但仅当该链接仍属于同一章
     * （形如 {indexId}_N.html）时才继续；否则说明它指向下一章，立即停止。
     */
    private String getPagedContent(String firstUrl, String firstHtml, RuleBean ruleBean)
        throws InterruptedException {
        String baseUrl = chapterBase(firstUrl);
        StringBuilder content = new StringBuilder();

        String html = firstHtml;
        String url = firstUrl;
        Set<String> visited = new HashSet<>();
        visited.add(firstUrl);
        for (int page = 1; page <= MAX_CONTENT_PAGE; page++) {
            String part = extractContent(html, ruleBean);
            if (!CrawlCollectionStore.readable(part)) return "";
            content.append(part);

            if (StringUtils.isBlank(ruleBean.getContentNextPagePatten())) {
                break;
            }
            Matcher nextMatcher = PatternFactory.getPattern(ruleBean.getContentNextPagePatten()).matcher(html);
            if (!nextMatcher.find()) {
                break;
            }
            String nextUrl = resolveUrl(nextMatcher.group(1), url);
            if (nextUrl == null || !chapterBase(nextUrl).equals(baseUrl)) {
                break;
            }
            if (page == MAX_CONTENT_PAGE || !visited.add(nextUrl)) return "";
            String nextHtml = crawlHttpClient.get(nextUrl, ruleBean.getCharset());
            if (nextHtml == null) {
                return "";
            }
            html = nextHtml;
            url = nextUrl;
        }
        return content.toString();
    }

    /**
     * 按 contentStart / contentEnd 字面量截取正文；标记缺失时返回空串而不是抛异常。
     */
    private String extractContent(String html, RuleBean ruleBean) {
        if (html == null) {
            return "";
        }
        int start = html.indexOf(ruleBean.getContentStart());
        if (start < 0) {
            return "";
        }
        String rest = html.substring(start + ruleBean.getContentStart().length());
        if (StringUtils.isBlank(ruleBean.getContentEnd())) return rest;
        int end = rest.indexOf(ruleBean.getContentEnd());
        return end >= 0 ? rest.substring(0, end) : "";
    }

    /**
     * 从标记之后截取；标记不存在时原样返回。
     */
    private String sliceAfterMarker(String html, String marker) {
        if (StringUtils.isBlank(marker)) {
            return html;
        }
        int idx = html.indexOf(marker);
        return idx >= 0 ? html.substring(idx + marker.length()) : html;
    }

    /**
     * 章节基准地址：去掉形如 _2.html 的分页后缀与 .html，用于判断两个链接是否同一章。
     */
    private String chapterBase(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("_\\d+\\.html$", "").replaceAll("\\.html$", "");
    }

    /**
     * 把根相对（/x/y）或相对（x/y）链接补全为绝对地址。
     */
    private String resolveUrl(String link, String currentUrl) {
        if (StringUtils.isBlank(link)) {
            return null;
        }
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return link;
        }
        int schemeEnd = currentUrl.indexOf(':');
        int hostEnd = currentUrl.indexOf('/', currentUrl.indexOf("//") + 2);
        if (hostEnd < 0) {
            return null;
        }
        if (link.startsWith("//")) {
            return schemeEnd > 0 ? currentUrl.substring(0, schemeEnd) + ":" + link : null;
        }
        if (link.startsWith("/")) {
            return currentUrl.substring(0, hostEnd) + link;
        }
        return currentUrl.substring(0, currentUrl.lastIndexOf('/') + 1) + link;
    }


    /**
     * [审查15] 每批提交的章节数上限，避免整本生成一条超大 INSERT
     */
    private static final int CRAWL_SAVE_BATCH_SIZE = 100;
    private static final long CRAWL_SAVE_MAX_WAIT_NANOS = java.time.Duration.ofSeconds(30).toNanos();

    protected long batchTimeNanos() {
        return System.nanoTime();
    }

    /**
     * 目录条目：来源章节 ID + 章节标题
     */
    private static class ChapterRef {
        private final String id;

        private final String name;

        ChapterRef(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * 单章抓取结果：正常 / 失败（需重试）/ 跳过（站点未完结等，留待下次）
     */
    private static class ChapterFetchResult {
        private final String content;

        private final boolean failed;

        private final boolean skipped;
        private final com.java2nb.novel.utils.CrawlFetchFailure failure;

        private ChapterFetchResult(String content, boolean failed, boolean skipped) {
            this(content,failed,skipped,null);
        }
        private ChapterFetchResult(String content, boolean failed, boolean skipped,
                                   com.java2nb.novel.utils.CrawlFetchFailure failure) {
            this.content = content;
            this.failed = failed;
            this.skipped = skipped;
            this.failure = failure;
        }

        static ChapterFetchResult ok(String content) {
            return new ChapterFetchResult(content, false, false);
        }

        static ChapterFetchResult fail(com.java2nb.novel.utils.CrawlFetchFailure failure) {
            return new ChapterFetchResult(null, true, false, failure);
        }

        static ChapterFetchResult skip() {
            return new ChapterFetchResult(null, false, true);
        }
    }
}
