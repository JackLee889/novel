package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.CrawlCollectionStore;
import com.java2nb.novel.core.crawl.CrawlDiscoveryStore;
import com.java2nb.novel.core.crawl.CrawlRefreshStore;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.core.cache.CacheKey;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.mapper.*;
import com.java2nb.novel.service.BookContentService;
import com.java2nb.novel.service.impl.BookServiceImpl;
import com.java2nb.novel.service.impl.DbBookContentServiceImpl;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.seo.SeoRepository;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual parser, MyBatis mappers and transactions; HTTP/Redis are isolated. */
class CrawlBookSummaryTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private BookServiceImpl books;
    private CrawlParser parser;
    private CrawlHttpClient http;
    private Map<String, BookContentService> contentServices;
    private RuleBean rule;
    private CacheService cache;
    private SeoRepository seo;
    private CrawlBookMapper bookMapper;
    private CrawlBookIndexMapper indexMapper;
    private final AtomicLong batchNow = new AtomicLong();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE book (
              id BIGINT PRIMARY KEY, work_direction TINYINT, cat_id INT, cat_name VARCHAR(50),
              pic_url VARCHAR(200), book_name VARCHAR(50), author_id BIGINT, author_name VARCHAR(50),
              book_desc VARCHAR(2000), score REAL, book_status TINYINT DEFAULT 0, visit_count BIGINT,
              word_count INT, comment_count INT, yesterday_buy INT, last_index_id BIGINT,
              last_index_name VARCHAR(50), last_index_update_time TIMESTAMP, is_vip TINYINT,
              status TINYINT, update_time TIMESTAMP NOT NULL, create_time TIMESTAMP,
              crawl_source_id INT, crawl_book_id VARCHAR(32), crawl_last_time TIMESTAMP,
              crawl_is_stop TINYINT, UNIQUE(book_name, author_name))
            """);
        jdbc.execute("""
            CREATE TABLE book_index (
              id BIGINT PRIMARY KEY, book_id BIGINT NOT NULL, index_num INT NOT NULL,
              index_name VARCHAR(100), source_index_id VARCHAR(64), word_count INT, is_vip TINYINT,
              book_price INT, storage_type VARCHAR(10) DEFAULT 'db', create_time TIMESTAMP,
              update_time TIMESTAMP, UNIQUE(book_id, index_num))
            """);
        jdbc.execute("CREATE TABLE book_content (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "index_id BIGINT NOT NULL UNIQUE, content TEXT)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CrawlBookMapper.class);
        configuration.addMapper(CrawlBookIndexMapper.class);
        configuration.addMapper(BookContentMapper.class);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mybatis/mapping/BookMapper.xml"),
            new ClassPathResource("mybatis/mapping/BookIndexMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE TABLE seo_hook_guard (id INT PRIMARY KEY)");jdbc.update("INSERT INTO seo_hook_guard VALUES(1)");
        jdbc.execute("CREATE TABLE seo_hook_state (book_id BIGINT PRIMARY KEY,revision BIGINT,modified_at TIMESTAMP)");
        // The hook probe writes through the same datasource/transaction as the actual MyBatis content.
        // SeoRepository itself is covered by its common control-plane tests.
        seo=mock(SeoRepository.class);
        doAnswer(call->{assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());jdbc.queryForObject("SELECT id FROM seo_hook_guard WHERE id=1 FOR UPDATE",Integer.class);return null;}).when(seo).lockMutation();
        doAnswer(call->{long id=call.getArgument(0);assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());if(jdbc.update("UPDATE seo_hook_state SET revision=revision+1,modified_at=CURRENT_TIMESTAMP WHERE book_id=?",id)==0)jdbc.update("INSERT INTO seo_hook_state VALUES(?,1,CURRENT_TIMESTAMP)",id);return null;}).when(seo).markBookDirty(anyLong());
        contentServices = new HashMap<>();
        contentServices.put("db", spy(new DbBookContentServiceImpl(sql.getMapper(BookContentMapper.class))));
        cache = mock(CacheService.class);
        bookMapper=spy(sql.getMapper(CrawlBookMapper.class));
        indexMapper=spy(sql.getMapper(CrawlBookIndexMapper.class));
        books = new BookServiceImpl(bookMapper, mock(BookCategoryMapper.class),
            indexMapper, contentServices, cache,seo);
        ReflectionTestUtils.setField(books, "storageType", "db");
        ReflectionTestUtils.setField(books,"seoEnabled",true);
        http = mock(CrawlHttpClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        CrawlTestStores.schema(jdbc);
        parser = new CrawlParser(http, redis, new CrawlCollectionStore(jdbc, System.getProperty("java.io.tmpdir"))) {
            @Override protected long batchTimeNanos() { return batchNow.get(); }
        };
        rule = new RuleBean();
        rule.setBookIndexUrl("http://fixture/index/{bookId}");
        rule.setIndexIdPatten("href=\"/chapter/(\\d+)\"");
        rule.setIndexNamePatten("href=\"/chapter/\\d+\">([^<]+)</a>");
        rule.setBookContentUrl("http://fixture/chapter/{indexId}");
        rule.setContentStart("<article>");
        rule.setContentEnd("</article>");
    }

    @Test
    void slowRequestsPublishCommittedProgressBeforeHundredChapters() throws Exception {
        fixture(3);
        when(http.get(eq("http://fixture/chapter/2"), anyString())).thenAnswer(call -> {
            batchNow.set(java.time.Duration.ofSeconds(31).toNanos());
            return "<article>Test chapter content</article>";
        });
        when(http.get(eq("http://fixture/chapter/3"), anyString())).thenAnswer(call -> {
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM book_index", Integer.class));
            assertSummary(1);
            throw new InterruptedException("Source stopped after slow batch was committed");
        });
        assertThrows(InterruptedException.class, () -> crawlNewBook(newBook()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM book_content", Integer.class));
        assertSummary(1);
    }

    @Test
    void publishesLatestChapterBeforeNextBatchIsFetched() throws Exception {
        fixture(101);
        when(http.get(eq("http://fixture/chapter/101"), anyString())).thenAnswer(invocation -> {
            assertSummary(99);
            throw new InterruptedException("Simulate shutdown after first committed batch");
        });
        assertThrows(InterruptedException.class, () -> crawlNewBook(newBook()));
        assertSummary(99);
    }

    @Test
    void keepsLastPersistedChapterVisibleWhenTailFails() throws Exception {
        fixture(3);
        when(http.get(eq("http://fixture/chapter/3"), anyString())).thenReturn(null);
        assertFalse(crawlNewBook(newBook()));
        assertSummary(1);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM book_index", Integer.class));
    }

    @Test
    void exactBatchBoundaryHasLatestSummary() throws Exception {
        fixture(100);
        assertTrue(crawlNewBook(newBook()));
        assertSummary(99);
    }

    @Test
    void longLatestTitleKeepsFullDirectoryTitleAndReadableSummaryLink() throws Exception {
        fixture(1);
        String title = ("Chapter " + "long title ".repeat(7)).trim();
        when(http.get(eq("http://fixture/index/fixture"), anyString()))
            .thenReturn("<a href=\"/chapter/1\">" + title + "</a>");
        assertTrue(crawlNewBook(newBook()));
        assertEquals(title, jdbc.queryForObject("SELECT index_name FROM book_index", String.class));
        String summaryTitle = jdbc.queryForObject("SELECT last_index_name FROM book", String.class);
        assertEquals(50, summaryTitle.length());
        assertTrue(summaryTitle.endsWith("…"));
        assertEquals(jdbc.queryForObject("SELECT id FROM book_index", Long.class),
            jdbc.queryForObject("SELECT last_index_id FROM book", Long.class));
    }

    @Test
    void repairsMissingSummaryWhenAllChaptersAlreadyExist() throws Exception {
        fixture(3);
        Book book = newBook();
        insertExistingBook(book);
        insertChapter(10L, 0);
        insertChapter(11L, 1);
        insertChapter(12L, 2);
        Map<Integer, BookIndex> existing = books.queryExistBookIndexMap(book.getId());
        assertTrue(parser.parseBookIndexAndContent("fixture", book, rule, 1, existing, chapter ->
            transaction.executeWithoutResult(tx -> books.updateBookAndIndexAndContent(book,
                chapter.getBookIndexList(), chapter.getBookContentList(), existing,
                chapter.getBookIndexMetaList(), chapter.getReindexList())), null));
        assertSummary(2);
        verify(http, never()).get(startsWith("http://fixture/chapter/"), anyString());
    }

    @Test
    void fillingEarlierGapPreservesLatestTimestamp() throws Exception {
        fixture(3);
        Book book = newBook();
        insertExistingBook(book);
        insertChapter(10L, 0);
        insertChapter(12L, 2);
        Timestamp originalTime = Timestamp.from(Instant.parse("2024-01-01T00:00:00Z"));
        jdbc.update("UPDATE book SET last_index_id=12, last_index_name='Chapter 3', "
            + "last_index_update_time=? WHERE id=1", originalTime);
        Map<Integer, BookIndex> existing = books.queryExistBookIndexMap(book.getId());
        assertTrue(parser.parseBookIndexAndContent("fixture", book, rule, 1, existing, chapter ->
            transaction.executeWithoutResult(tx -> books.updateBookAndIndexAndContent(book,
                chapter.getBookIndexList(), chapter.getBookContentList(), existing,
                chapter.getBookIndexMetaList(), chapter.getReindexList())), null));
        assertSummary(2);
        assertEquals(originalTime, jdbc.queryForObject(
            "SELECT last_index_update_time FROM book WHERE id=1", Timestamp.class));
    }

    @Test
    void summaryAndChaptersRollBackTogetherWhenContentSaveFails() {
        Book book = newBook();
        insertExistingBook(book);
        insertChapter(10L, 0);
        jdbc.update("UPDATE book SET last_index_id=10, last_index_name='Chapter 1', word_count=7 WHERE id=1");
        BookContentService failingStorage = mock(BookContentService.class);
        doThrow(new IllegalStateException("Storage unavailable")).when(failingStorage)
            .saveBookContent(anyList(), eq(1L));
        contentServices.put("db", failingStorage);
        BookIndex chapter = new BookIndex();
        chapter.setId(11L);
        chapter.setBookId(1L);
        chapter.setIndexNum(1);
        chapter.setIndexName("Chapter 2");
        chapter.setWordCount(9);
        BookContent content = new BookContent();
        content.setIndexId(11L);
        content.setContent("Test chapter");
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx ->
            books.updateBookAndIndexAndContent(book, List.of(chapter), List.of(content),
                books.queryExistBookIndexMap(1L), List.of(), null)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM book_index", Integer.class));
        assertEquals(10L, jdbc.queryForObject("SELECT last_index_id FROM book WHERE id=1", Long.class));
        assertEquals(7, jdbc.queryForObject("SELECT word_count FROM book WHERE id=1", Integer.class));
        verifyNoInteractions(cache);
    }

    @Test
    void invalidatesHomeCacheAfterCommitAndToleratesRedisFailure() throws Exception {
        fixture(3);
        doThrow(new IllegalStateException("Redis unavailable")).when(cache).del(CacheKey.INDEX_NEW_BOOK_KEY);
        Book book = newBook();
        assertTrue(parser.parseBookIndexAndContent("fixture", book, rule, 1, Map.of(), chapter -> {
            transaction.executeWithoutResult(tx -> {
                books.saveBookAndIndexAndContent(book, chapter.getBookIndexList(), chapter.getBookContentList());
                verifyNoInteractions(cache);
            });
        }, null));
        assertSummary(2);
        verify(cache).del(CacheKey.INDEX_UPDATE_BOOK_KEY);
    }

    @Test
    void emptyDirectoryDoesNotCreateVisibleBook() throws Exception {
        when(http.get(startsWith("http://fixture/index/"), anyString())).thenReturn("<html>No chapters</html>");
        assertFalse(crawlNewBook(newBook()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM book", Integer.class));
    }

    @Test
    void eachNewBatchSummaryCountsOnlyCommittedChapters() throws Exception {
        fixture(205);
        AtomicInteger commits = new AtomicInteger();
        Book book = newBook();
        assertTrue(parser.parseBookIndexAndContent("fixture", book, rule, 1, Map.of(), chapter -> {
            transaction.executeWithoutResult(tx -> books.saveBookAndIndexAndContent(book,
                chapter.getBookIndexList(), chapter.getBookContentList()));
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM book_index", Integer.class);
            assertSummary(count - 1);
            commits.incrementAndGet();
        }, null));
        assertEquals(3, commits.get());
    }

    @Test void locksSeoPublicationBeforeBookAndCreatesRevisionForNewChapter() throws Exception {
        fixture(1);assertTrue(crawlNewBook(newBook()));
        var ordered=inOrder(seo,bookMapper);ordered.verify(seo).lockMutation();ordered.verify(bookMapper).lockBookSummary(1L);
        assertEquals(1L,jdbc.queryForObject("SELECT revision FROM seo_hook_state WHERE book_id=1",Long.class));
    }

    @Test void timestampsAndSourceIdentityBackfillDoNotCreateFalseModification() throws Exception {
        fixture(1);assertTrue(crawlNewBook(newBook()));
        Timestamp before=jdbc.queryForObject("SELECT modified_at FROM seo_hook_state WHERE book_id=1",Timestamp.class);
        jdbc.update("UPDATE book_index SET source_index_id=NULL WHERE book_id=1");
        var existing=books.queryExistBookIndexMap(1L);var old=existing.values().iterator().next();
        var meta=new BookIndex();meta.setId(old.getId());meta.setSourceIndexId("1");meta.setIndexNum(old.getIndexNum());meta.setUpdateTime(new Date());
        clearInvocations(seo);
        transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),List.of(),List.of(),existing,List.of(meta),null));
        books.updateCrawlLastTime(1L);books.updateCrawlProperties(1L,2,"new-source-reference");
        assertEquals(before,jdbc.queryForObject("SELECT modified_at FROM seo_hook_state WHERE book_id=1",Timestamp.class));
        assertEquals(1L,jdbc.queryForObject("SELECT revision FROM seo_hook_state WHERE book_id=1",Long.class));verify(seo,never()).markBookDirty(anyLong());
    }

    @Test void identicalBodyRefetchDoesNotCreateRevisionButRealBodyChangeDoes() throws Exception {
        fixture(1);assertTrue(crawlNewBook(newBook()));
        var existing=books.queryExistBookIndexMap(1L);var index=existing.values().iterator().next();index.setBookId(1L);index.setUpdateTime(new Date());
        var content=new BookContent();content.setIndexId(index.getId());content.setContent(jdbc.queryForObject("SELECT content FROM book_content WHERE index_id=?",String.class,index.getId()));
        clearInvocations(seo);
        transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),List.of(index),List.of(content),existing,List.of(),null));
        verify(seo,never()).markBookDirty(anyLong());assertEquals(1L,jdbc.queryForObject("SELECT revision FROM seo_hook_state WHERE book_id=1",Long.class));
        content.setContent(content.getContent()+" This is a real revision.");
        transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),List.of(index),List.of(content),existing,List.of(),null));
        assertEquals(2L,jdbc.queryForObject("SELECT revision FROM seo_hook_state WHERE book_id=1",Long.class));verify(seo).markBookDirty(1L);
    }

    @Test void dirtyRevisionRollsBackTogetherWithChapterMutation() throws Exception {
        fixture(1);assertTrue(crawlNewBook(newBook()));
        var existing=books.queryExistBookIndexMap(1L);var index=existing.values().iterator().next();index.setBookId(1L);
        String previous=jdbc.queryForObject("SELECT content FROM book_content WHERE index_id=?",String.class,index.getId());
        var content=new BookContent();content.setIndexId(index.getId());content.setContent("Actual revised body before rollback");
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(tx->{books.updateBookAndIndexAndContent(newBook(),List.of(index),List.of(content),existing,List.of(),null);throw new IllegalStateException("Rollback after SEO enqueue");}));
        assertEquals(previous,jdbc.queryForObject("SELECT content FROM book_content WHERE index_id=?",String.class,index.getId()));
        assertEquals(1L,jdbc.queryForObject("SELECT revision FROM seo_hook_state WHERE book_id=1",Long.class));
    }

    @Test
    void boundedUpdateCommitsItsCheckpointAndResumesWithoutRefetchingSavedChapters() throws Exception {
        fixture(5);
        insertExistingBook(newBook());
        for (int pass = 0; pass < 3; pass++) {
            assertTrue(parser.beginBookCrawl(1L, 2));
            boolean completed;
            try {
                var existing = books.queryExistBookIndexMap(1L);
                Book book = newBook();
                completed = parser.parseBookIndexAndContent("fixture", book, rule, 1, existing, chapter ->
                    transaction.executeWithoutResult(tx -> books.updateBookAndIndexAndContent(book,
                        chapter.getBookIndexList(), chapter.getBookContentList(), existing,
                        chapter.getBookIndexMetaList(), chapter.getReindexList())), null);
            } finally { parser.endBookCrawl(1L); }
            int saved = Math.min(5, (pass + 1) * 2);
            assertEquals(saved, jdbc.queryForObject("SELECT COUNT(*) FROM book_content", Integer.class));
            assertSummary(saved - 1);
            assertEquals(pass == 2, completed, "A checkpoint must not be reported as complete");
        }
        for (int chapter = 1; chapter <= 5; chapter++) {
            verify(http, times(1)).get(eq("http://fixture/chapter/" + chapter), anyString());
        }
    }

    @Test
    void failedChapterRequestsAlsoConsumeTheAutomaticVisitBudget() throws Exception {
        fixture(5);
        insertExistingBook(newBook());
        when(http.get(eq("http://fixture/chapter/1"), anyString())).thenReturn(null);
        assertTrue(parser.beginBookCrawl(1L, 2));
        try {
            var existing = books.queryExistBookIndexMap(1L);
            Book book = newBook();
            assertFalse(parser.parseBookIndexAndContent("fixture", book, rule, 1, existing, chapter ->
                transaction.executeWithoutResult(tx -> books.updateBookAndIndexAndContent(book,
                    chapter.getBookIndexList(), chapter.getBookContentList(), existing,
                    chapter.getBookIndexMetaList(), chapter.getReindexList())), null));
        } finally { parser.endBookCrawl(1L); }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM book_content", Integer.class));
        assertSummary(1);
        verify(http, never()).get(eq("http://fixture/chapter/3"), anyString());
    }

    @Test void legacyNullLatestTitleClearsStaleSummaryNameWithoutLosingItsChapterPointer() throws Exception {
        fixture(1);assertTrue(crawlNewBook(newBook()));
        Long latest=jdbc.queryForObject("SELECT last_index_id FROM book WHERE id=1",Long.class);
        jdbc.update("UPDATE book_index SET index_name=NULL WHERE id=?",latest);
        jdbc.update("UPDATE book SET last_index_name='Stale summary title' WHERE id=1");
        var existing=books.queryExistBookIndexMap(1L);
        transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),List.of(),List.of(),existing,List.of(),null));
        assertNull(jdbc.queryForObject("SELECT last_index_name FROM book WHERE id=1",String.class));
        assertEquals(latest,jdbc.queryForObject("SELECT last_index_id FROM book WHERE id=1",Long.class));
        assertEquals(jdbc.queryForObject("SELECT SUM(word_count) FROM book_index",Integer.class),jdbc.queryForObject("SELECT word_count FROM book WHERE id=1",Integer.class));
    }

    private boolean crawlNewBook(Book book) throws InterruptedException {
        return parser.parseBookIndexAndContent("fixture", book, rule, 1, Map.of(), chapter ->
            transaction.executeWithoutResult(tx -> books.saveBookAndIndexAndContent(book,
                chapter.getBookIndexList(), chapter.getBookContentList())), null);
    }

    @Test void appending125ChaptersUsesTwoBatchesAndOneSummaryWrite() throws Exception {
        fixture(1);assertTrue(crawlNewBook(newBook()));
        var existing=books.queryExistBookIndexMap(1L);
        List<BookIndex> indexes=new ArrayList<>();List<BookContent> contents=new ArrayList<>();
        for(int i=1;i<=125;i++){
            BookIndex index=new BookIndex();index.setId(1000L+i);index.setBookId(1L);index.setIndexNum(i);
            index.setIndexName("Append "+i);index.setSourceIndexId("append-"+i);index.setWordCount(10);index.setCreateTime(new Date());
            BookContent body=new BookContent();body.setIndexId(index.getId());body.setContent("Appended readable fixture body "+i);
            indexes.add(index);contents.add(body);
        }
        clearInvocations(indexMapper,bookMapper,contentServices.get("db"));
        transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),indexes,contents,existing,List.of(),null));
        assertEquals(126,jdbc.queryForObject("SELECT COUNT(*) FROM book_content",Integer.class));assertSummary(125);
        verify(indexMapper,times(2)).insertMultiple(anyList());verify(indexMapper,never()).insertSelective(any());
        verify(contentServices.get("db"),times(2)).saveBookContent(anyList(),eq(1L));
        verify(contentServices.get("db"),never()).saveBookContent(any(BookContent.class),anyLong());
        verify(bookMapper,times(1)).updateByPrimaryKeySelective(any());verify(bookMapper,never()).updateBookSummary(any());
    }

    @Test void identityBackfill205RowsUsesThreeUpdatesAndPreservesBodiesAndSeoRevision() throws Exception {
        fixture(205);assertTrue(crawlNewBook(newBook()));
        jdbc.update("UPDATE book_index SET source_index_id=NULL WHERE book_id=1");
        var existing=books.queryExistBookIndexMap(1L);List<BookIndex> metas=new ArrayList<>();
        for(var old:existing.values()){
            var meta=new BookIndex();meta.setId(old.getId());meta.setSourceIndexId(Integer.toString(old.getIndexNum()+1));
            meta.setIndexNum(old.getIndexNum());meta.setUpdateTime(new Date());metas.add(meta);
        }
        long revision=jdbc.queryForObject("SELECT revision FROM seo_hook_state WHERE book_id=1",Long.class);
        var bodies=jdbc.queryForList("SELECT index_id,content FROM book_content ORDER BY index_id");
        clearInvocations(indexMapper,contentServices.get("db"),seo);
        transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),List.of(),List.of(),existing,metas,null));
        assertEquals(205,jdbc.queryForObject("SELECT COUNT(*) FROM book_index WHERE source_index_id IS NOT NULL",Integer.class));
        assertEquals(bodies,jdbc.queryForList("SELECT index_id,content FROM book_content ORDER BY index_id"));assertSummary(204);
        verify(indexMapper,times(3)).updateIdentityMetadata(eq(1L),anyList());verify(indexMapper,never()).updateByPrimaryKeySelective(any());
        verifyNoInteractions(contentServices.get("db"));verify(seo,never()).markBookDirty(anyLong());
        assertEquals(revision,jdbc.queryForObject("SELECT revision FROM seo_hook_state WHERE book_id=1",Long.class));
    }

    @Test void identicalReplaySkipsIndexAndBodyWritesButRealRevisionStillPersists() throws Exception {
        fixture(1);assertTrue(crawlNewBook(newBook()));
        var existing=books.queryExistBookIndexMap(1L);var index=existing.values().iterator().next();index.setBookId(1L);
        var body=new BookContent();body.setIndexId(index.getId());body.setContent(jdbc.queryForObject("SELECT content FROM book_content",String.class));
        clearInvocations(indexMapper,contentServices.get("db"));
        transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),List.of(index),List.of(body),existing,List.of(),null));
        verify(indexMapper,never()).updateByPrimaryKeySelective(any());
        verify(contentServices.get("db"),never()).updateBookContent(any(),anyLong());
        body.setContent(body.getContent()+" Actual new text.");
        transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),List.of(index),List.of(body),existing,List.of(),null));
        verify(contentServices.get("db")).updateBookContent(body,1L);
        assertEquals(body.getContent(),jdbc.queryForObject("SELECT content FROM book_content",String.class));
    }

    @Test void failedSecondAppendBatchRollsBackEarlierBatchAndSummary() throws Exception {
        fixture(1);assertTrue(crawlNewBook(newBook()));
        var existing=books.queryExistBookIndexMap(1L);
        var indexes=new ArrayList<BookIndex>();var contents=new ArrayList<BookContent>();
        for(int i=1;i<=125;i++){
            var index=new BookIndex();index.setId(2000L+i);index.setBookId(1L);index.setIndexNum(i);index.setIndexName("Rollback "+i);index.setWordCount(10);
            var body=new BookContent();body.setIndexId(index.getId());body.setContent("Rollback fixture body");indexes.add(index);contents.add(body);
        }
        doThrow(new IllegalStateException("Synthetic second batch storage failure"))
            .when(contentServices.get("db")).saveBookContent(argThat((List<BookContent> rows)->rows.size()==25),eq(1L));
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),indexes,contents,existing,List.of(),null)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM book_index",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM book_content",Integer.class));assertSummary(0);
    }

    @Test void reindex205ChaptersUsesThreeFinalUpdatesAndPreservesIdentityBodiesAndUnplannedRows() throws Exception {
        fixture(205);assertTrue(crawlNewBook(newBook()));
        var existing=books.queryExistBookIndexMap(1L);
        var before=jdbc.queryForList("SELECT id,index_name,source_index_id,word_count FROM book_index ORDER BY id");
        var bodies=jdbc.queryForList("SELECT index_id,content FROM book_content ORDER BY index_id");
        var plan=new ArrayList<BookIndex>();
        for(var old:existing.values()){var item=new BookIndex();item.setId(old.getId());item.setIndexNum(204-old.getIndexNum());plan.add(item);}
        jdbc.update("INSERT INTO book_index(id,book_id,index_num,index_name,word_count) VALUES(999999,1,205,'Concurrent append',5)");
        clearInvocations(indexMapper,contentServices.get("db"));
        transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),List.of(),List.of(),existing,List.of(),plan));
        for(var item:plan)assertEquals(item.getIndexNum(),jdbc.queryForObject("SELECT index_num FROM book_index WHERE id=?",Integer.class,item.getId()));
        assertEquals(205,jdbc.queryForObject("SELECT index_num FROM book_index WHERE id=999999",Integer.class));
        assertEquals(before,jdbc.queryForList("SELECT id,index_name,source_index_id,word_count FROM book_index WHERE id<>999999 ORDER BY id"));
        assertEquals(bodies,jdbc.queryForList("SELECT index_id,content FROM book_content ORDER BY index_id"));
        verify(indexMapper,times(3)).updateBookIndexNums(eq(1L),anyList());verify(indexMapper,never()).updateBookIndexNum(anyLong(),anyInt());
        verifyNoInteractions(contentServices.get("db"));
        var lookup=books.queryBookByBookNameAndAuthorName(newBook().getBookName(),newBook().getAuthorName());
        assertEquals(1,lookup.getCrawlSourceId());assertEquals("fixture",lookup.getCrawlBookId());
    }

    @Test void failureInFinalReindexBatchRestoresAllOriginalNumbersAndSummary() throws Exception {
        fixture(205);assertTrue(crawlNewBook(newBook()));var existing=books.queryExistBookIndexMap(1L);var plan=new ArrayList<BookIndex>();
        var before=jdbc.queryForList("SELECT id,index_num FROM book_index ORDER BY id");
        for(var old:existing.values()){var item=new BookIndex();item.setId(old.getId());item.setIndexNum(204-old.getIndexNum());plan.add(item);}
        doThrow(new IllegalStateException("Controlled final batch failure")).when(indexMapper).updateBookIndexNums(eq(1L),argThat(rows->rows.size()==5));
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(newBook(),List.of(),List.of(),existing,List.of(),plan)));
        assertEquals(before,jdbc.queryForList("SELECT id,index_num FROM book_index ORDER BY id"));assertSummary(204);
    }

    @Test void completedSourceWithDeferredOrHtmlOnlyBodiesIsNotCollectedInFullAndResumesOnlyHoles() throws Exception {
        fixture(3);
        when(http.get(eq("http://fixture/chapter/2"),anyString())).thenReturn("<article>正在手打中</article>");
        when(http.get(eq("http://fixture/chapter/3"),anyString())).thenReturn("<article><p>&nbsp;</p><script>bad()</script></article>");
        Book book=newBook();book.setBookStatus((byte)1);
        assertFalse(crawlNewBook(book));
        assertEquals(1,jdbc.queryForObject("SELECT ready_count FROM crawl_collection_state",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT missing_count FROM crawl_collection_state",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT deferred_count FROM crawl_collection_state",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT source_completed FROM crawl_collection_state",Integer.class));
        assertEquals("INCOMPLETE",jdbc.queryForObject("SELECT collection_status FROM crawl_collection_state",String.class));
        when(http.get(eq("http://fixture/chapter/2"),anyString())).thenReturn("<article>Recovered chapter two.</article>");
        when(http.get(eq("http://fixture/chapter/3"),anyString())).thenReturn("<article>Recovered chapter three.</article>");
        clearInvocations(http);
        assertTrue(crawlExisting());
        verify(http,never()).get(eq("http://fixture/chapter/1"),anyString());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM book_content",Integer.class));
        assertEquals("COMPLETE",jdbc.queryForObject("SELECT collection_status FROM crawl_collection_state",String.class));
        assertSummary(2);
    }

    @Test void repairsMissingBodyRowsAndEmptySameTitleBodiesWithoutReplacingChapterIdentities() throws Exception {
        fixture(3);assertTrue(crawlNewBook(newBook()));
        var identity=jdbc.queryForList("SELECT id,index_num,source_index_id FROM book_index ORDER BY id");
        long missing=jdbc.queryForObject("SELECT id FROM book_index WHERE index_num=1",Long.class);
        long empty=jdbc.queryForObject("SELECT id FROM book_index WHERE index_num=2",Long.class);
        jdbc.update("DELETE FROM book_content WHERE index_id=?",missing);
        jdbc.update("UPDATE book_content SET content='<div>&#160;</div>' WHERE index_id=?",empty);
        jdbc.update("UPDATE book_index SET word_count=NULL WHERE id=?",missing);
        jdbc.update("UPDATE crawl_collection_state SET validation_started_at=?",Timestamp.from(Instant.now().minusSeconds(90000)));
        clearInvocations(http);
        assertTrue(crawlExisting());
        verify(http,never()).get(eq("http://fixture/chapter/1"),anyString());
        verify(http).get(eq("http://fixture/chapter/2"),anyString());verify(http).get(eq("http://fixture/chapter/3"),anyString());
        assertEquals(identity,jdbc.queryForList("SELECT id,index_num,source_index_id FROM book_index ORDER BY id"));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM book_content",Integer.class));
        assertTrue(CrawlCollectionStore.readable(jdbc.queryForObject("SELECT content FROM book_content WHERE index_id=?",String.class,missing)));
        assertSummary(2);
    }

    @Test void legacyVerificationIsBoundedAndItsCheckpointSurvivesParserRestart() throws Exception {
        fixture(205);assertTrue(crawlNewBook(newBook()));
        jdbc.update("DELETE FROM crawl_collection_state");
        var store=spy(new CrawlCollectionStore(jdbc,System.getProperty("java.io.tmpdir")));
        parser=new CrawlParser(http,mockedRedis(),store);
        clearInvocations(http);
        assertFalse(crawlExisting());
        assertEquals(100,jdbc.queryForObject("SELECT ready_count FROM crawl_collection_state",Integer.class));
        assertEquals(105,jdbc.queryForObject("SELECT unchecked_count FROM crawl_collection_state",Integer.class));
        assertTrue(parser.lastRunIsResumable());
        parser=new CrawlParser(http,mockedRedis(),store);
        assertFalse(crawlExisting());assertTrue(crawlExisting());
        assertEquals(205,jdbc.queryForObject("SELECT ready_count FROM crawl_collection_state",Integer.class));
        verify(http,times(3)).get(eq("http://fixture/index/fixture"),anyString());
        verify(http,times(3)).get(anyString(),anyString());
        verify(store,times(2)).readyChapters(eq(1L),argThat(rows->rows.size()==100));
        verify(store).readyChapters(eq(1L),argThat(rows->rows.size()==5));
        clearInvocations(store);assertTrue(crawlExisting());
        verify(store).readyChapters(eq(1L),argThat(List::isEmpty));
    }

    @Test void boundedDownloadPersistsTwentyFiveThenResumesTheRemainingFive() throws Exception {
        fixture(30);assertTrue(parser.beginBookCrawl(1L,25));
        try { assertFalse(crawlNewBook(newBook())); } finally { parser.endBookCrawl(1L); }
        assertEquals(25,jdbc.queryForObject("SELECT COUNT(*) FROM book_content",Integer.class));
        assertEquals(25,jdbc.queryForObject("SELECT ready_count FROM crawl_collection_state",Integer.class));
        clearInvocations(http);
        parser=new CrawlParser(http,mockedRedis(),new CrawlCollectionStore(jdbc,System.getProperty("java.io.tmpdir")));
        assertTrue(crawlExisting());assertEquals(30,jdbc.queryForObject("SELECT COUNT(*) FROM book_content",Integer.class));
        verify(http,times(6)).get(anyString(),anyString());assertSummary(29);
    }

    @Test void failedStorageCallbackCannotPublishAFalseReadyCheckpoint() throws Exception {
        fixture(1);doThrow(new IllegalStateException("Synthetic storage failure"))
            .when(contentServices.get("db")).saveBookContent(anyList(),eq(1L));
        assertThrows(IllegalStateException.class,()->crawlNewBook(newBook()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_collection_state",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM book_content",Integer.class));
    }

    @Test void appendedChaptersReuseTheVerifiedCatalogPrefixInsteadOfRescanningOldBodies() throws Exception {
        fixture(205);assertTrue(crawlNewBook(newBook()));
        fixture(207);
        var store=spy(new CrawlCollectionStore(jdbc,System.getProperty("java.io.tmpdir")));
        parser=new CrawlParser(http,mockedRedis(),store);clearInvocations(http);
        assertTrue(crawlExisting());
        verify(store).readyChapters(eq(1L),argThat(List::isEmpty));
        verify(http,times(3)).get(anyString(),anyString());
        assertEquals(207,jdbc.queryForObject("SELECT ready_count FROM crawl_collection_state",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT unchecked_count FROM crawl_collection_state",Integer.class));
        assertSummary(206);
    }

    @Test void failedCatalogPageOrContentPageCannotCommitATruncatedChapterAsComplete() throws Exception {
        fixture(1);rule.setBookIndexPageUrl("http://fixture/index/{bookId}/{page}");
        rule.setBookIndexTotalPagePatten("pages=(\\d+)");
        when(http.get(eq("http://fixture/index/fixture/1"),anyString())).thenReturn("pages=2 <a href=\"/chapter/1\">Chapter 1</a>");
        when(http.get(eq("http://fixture/index/fixture/2"),anyString())).thenReturn(null);
        assertFalse(crawlNewBook(newBook()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM book_content",Integer.class));
        rule.setBookIndexPageUrl(null);rule.setContentNextPagePatten("next=\"([^\"]+)\"");
        when(http.get(eq("http://fixture/chapter/1"),anyString())).thenReturn("<article>Only first half</article> next=\"/chapter/1_2.html\"");
        when(http.get(eq("http://fixture/chapter/1_2.html"),anyString())).thenReturn(null);
        assertFalse(crawlNewBook(newBook()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM book_content",Integer.class));
    }

    @Test void missingConfiguredContentEndMarkerCannotTurnATruncatedBodyIntoCompletion() throws Exception {
        fixture(1);
        when(http.get(eq("http://fixture/chapter/1"),anyString())).thenReturn("<article>Readable but truncated before the required closing marker.");
        assertFalse(crawlNewBook(newBook()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM book_content",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_collection_state WHERE collection_status='COMPLETE'",Integer.class));
    }

    @Test void refreshFreesAnOccupiedTailWithoutDeletingHistoryOrPublishingItAsLatest() throws Exception {
        fixture(3);insertExistingBook(newBook());insertChapter(10,0);insertChapter(11,1);
        insertChapter(90,2);insertChapter(91,8);
        jdbc.update("UPDATE book_index SET source_index_id='old-opening' WHERE id=90");
        jdbc.update("UPDATE book_index SET source_index_id='other-history' WHERE id=91");
        var oldBodies=jdbc.queryForList("SELECT index_id,content FROM book_content ORDER BY index_id");
        runFastRefresh(2,2);
        assertEquals(9,jdbc.queryForObject("SELECT index_num FROM book_index WHERE id=90",Integer.class));
        assertEquals(8,jdbc.queryForObject("SELECT index_num FROM book_index WHERE id=91",Integer.class));
        long tail=jdbc.queryForObject("SELECT id FROM book_index WHERE source_index_id='3'",Long.class);
        assertEquals(tail,jdbc.queryForObject("SELECT last_index_id FROM book WHERE id=1",Long.class));
        assertEquals("Chapter 3",jdbc.queryForObject("SELECT last_index_name FROM book WHERE id=1",String.class));
        assertEquals(oldBodies,jdbc.queryForList("SELECT index_id,content FROM book_content WHERE index_id IN(10,11,90,91) ORDER BY index_id"));
        verify(indexMapper).parkBookIndexNums(eq(1L),eq(List.of(90L)),anyInt());
        verify(http,never()).get("http://fixture/chapter/1",null);
        verify(http,never()).get("http://fixture/chapter/2",null);
        fixture(4);runFastRefresh(3,3);
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM book_index",Integer.class));
        assertEquals("Chapter 4",jdbc.queryForObject("SELECT last_index_name FROM book WHERE id=1",String.class));
        assertEquals(9,jdbc.queryForObject("SELECT index_num FROM book_index WHERE id=90",Integer.class));
    }

    @Test void refreshResolvesAChainOfOccupiedLiveSlotsAndReusesItsReadableBody() throws Exception {
        fixture(3);insertExistingBook(newBook());insertChapter(10,0);insertChapter(11,1);
        insertChapter(90,2);insertChapter(12,4);
        jdbc.update("UPDATE book_index SET source_index_id='old-opening' WHERE id=90");
        jdbc.update("UPDATE book_index SET source_index_id='3',index_name='Chapter 3' WHERE id=12");
        var bodies=jdbc.queryForList("SELECT index_id,content FROM book_content ORDER BY index_id");
        runFastRefresh(2,2);
        assertEquals(2,jdbc.queryForObject("SELECT index_num FROM book_index WHERE id=12",Integer.class));
        assertEquals(5,jdbc.queryForObject("SELECT index_num FROM book_index WHERE id=90",Integer.class));
        assertEquals(12L,jdbc.queryForObject("SELECT last_index_id FROM book WHERE id=1",Long.class));
        assertEquals(bodies,jdbc.queryForList("SELECT index_id,content FROM book_content ORDER BY index_id"));
        verify(http,never()).get(startsWith("http://fixture/chapter/"),any());
    }

    @Test void aFailedTailSaveRollsBackRelocationAndLeavesOriginalBodiesAndNumbersIntact() throws Exception {
        fixture(3);insertExistingBook(newBook());insertChapter(10,0);insertChapter(11,1);insertChapter(90,2);
        jdbc.update("UPDATE book_index SET source_index_id='old-opening' WHERE id=90");
        var rows=jdbc.queryForList("SELECT * FROM book_index ORDER BY id");
        var bodies=jdbc.queryForList("SELECT * FROM book_content ORDER BY index_id");
        doThrow(new IllegalStateException("Storage failed after relocation")).when(contentServices.get("db")).saveBookContent(anyList(),eq(1L));
        assertThrows(IllegalStateException.class,()->runFastRefresh(2,2));
        assertEquals(rows,jdbc.queryForList("SELECT * FROM book_index ORDER BY id"));
        assertEquals(bodies,jdbc.queryForList("SELECT * FROM book_content ORDER BY index_id"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM book_index WHERE index_num>=100000000",Integer.class));
    }

    @Test void backgroundReconciliationKeepsHistoryButPublishesTheCurrentCatalogTail() throws Exception {
        fixture(3);insertExistingBook(newBook());insertChapter(10,0);insertChapter(11,1);insertChapter(90,2);
        jdbc.update("UPDATE book_index SET source_index_id='old-opening' WHERE id=90");
        assertTrue(crawlExisting());
        assertEquals(3,jdbc.queryForObject("SELECT index_num FROM book_index WHERE id=90",Integer.class));
        assertEquals("Original fixture body",jdbc.queryForObject("SELECT content FROM book_content WHERE index_id=90",String.class));
        assertEquals("Chapter 3",jdbc.queryForObject("SELECT last_index_name FROM book WHERE id=1",String.class));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM book_index",Integer.class));
    }

    private void runFastRefresh(int observed,int pending) throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS crawl_source(id INT PRIMARY KEY,source_status TINYINT)");
        jdbc.update("MERGE INTO crawl_source KEY(id) VALUES(1,1)");
        jdbc.update("UPDATE book SET crawl_source_id=1,crawl_book_id='fixture',book_status=0,crawl_is_stop=0 WHERE id=1");
        var queue=new CrawlDiscoveryStore(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()));
        var refresh=new CrawlRefreshStore(jdbc,queue);refresh.initialize();refresh.seedSerials(50);
        StringBuilder signature=new StringBuilder(new tools.jackson.databind.ObjectMapper().writeValueAsString(rule));
        for(int p=1;p<=observed;p++) {String id=""+p,name="Chapter "+p;signature.append('\n').append(id.length()).append(':').append(id).append(':').append(name.length()).append(':').append(name);}
        jdbc.update("UPDATE crawl_book_refresh SET observed_count=?,catalog_hash=?,pending_from=?,next_check_at=? WHERE book_id=1",
            observed,CrawlCollectionStore.hash(signature.toString()),pending,Timestamp.from(Instant.EPOCH));
        var job=refresh.claimForSource(1);assertNotNull(job);
        Book book=newBook();book.setBookStatus((byte)0);var existing=books.queryExistBookIndexMap(1L);
        assertTrue(parser.beginBookCrawl(1L,3));
        try(var scope=parser.refreshScope(refresh.session(job))) {
            parser.parseBookIndexAndContent("fixture",book,rule,1,existing,chapter->transaction.executeWithoutResult(tx->
                books.updateBookAndIndexAndContent(book,chapter.getBookIndexList(),chapter.getBookContentList(),existing,
                    chapter.getBookIndexMetaList(),chapter.getReindexList(),chapter.getCatalogSize())),null);
        } finally {parser.endBookCrawl(1L);}
        refresh.finish(job,false,null);
    }

    private StringRedisTemplate mockedRedis() {
        var redis=mock(StringRedisTemplate.class);when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));return redis;
    }
    private boolean crawlExisting() throws InterruptedException {
        var existing=books.queryExistBookIndexMap(1L);Book book=newBook();
        return parser.parseBookIndexAndContent("fixture",book,rule,1,existing,chapter ->
            transaction.executeWithoutResult(tx->books.updateBookAndIndexAndContent(book,chapter.getBookIndexList(),
                chapter.getBookContentList(),existing,chapter.getBookIndexMetaList(),chapter.getReindexList(),chapter.getCatalogSize())),null);
    }

    private void fixture(int chapters) throws InterruptedException {
        StringBuilder directory = new StringBuilder();
        for (int i = 1; i <= chapters; i++) {
            directory.append("<a href=\"/chapter/").append(i).append("\">Chapter ")
                .append(i).append("</a>\n");
            when(http.get(eq("http://fixture/chapter/" + i), anyString()))
                .thenReturn("<article>Original fixture chapter " + i + "</article>");
        }
        when(http.get(eq("http://fixture/index/fixture"), anyString())).thenReturn(directory.toString());
    }

    private Book newBook() {
        Book book = new Book();
        book.setId(1L);
        book.setBookName("Fixture novel");
        book.setAuthorName("Fixture author");
        book.setBookDesc("Original test fixture");
        book.setPicUrl("/images/cover.png");
        book.setScore(6.5f);
        book.setCrawlSourceId(1);
        book.setCrawlBookId("fixture");
        book.setUpdateTime(new Date());
        return book;
    }

    private void insertExistingBook(Book book) {
        jdbc.update("INSERT INTO book (id,book_name,author_name,word_count,update_time) VALUES(?,?,?,?,?)",
            book.getId(), book.getBookName(), book.getAuthorName(), 0, book.getUpdateTime());
    }

    private void insertChapter(long id, int number) {
        jdbc.update("INSERT INTO book_index "
            + "(id,book_id,index_num,index_name,source_index_id,word_count,storage_type,update_time) "
            + "VALUES(?,1,?,?,?,7,'db',?)", id, number, "Chapter " + (number + 1),
            Integer.toString(number + 1), new Date());
        jdbc.update("INSERT INTO book_content(index_id,content) VALUES(?,?)", id, "Original fixture body");
    }

    private void assertSummary(int expectedIndex) {
        Map<String, Object> latest = jdbc.queryForMap(
            "SELECT id,index_name FROM book_index WHERE book_id=1 ORDER BY index_num DESC LIMIT 1");
        assertEquals(expectedIndex, jdbc.queryForObject(
            "SELECT index_num FROM book_index WHERE id=?", Integer.class, latest.get("ID")));
        assertEquals(latest.get("ID"), jdbc.queryForObject(
            "SELECT last_index_id FROM book WHERE id=1", Long.class));
        assertEquals(latest.get("INDEX_NAME"), jdbc.queryForObject(
            "SELECT last_index_name FROM book WHERE id=1", String.class));
        assertNotNull(jdbc.queryForObject("SELECT last_index_update_time FROM book WHERE id=1", Timestamp.class));
        assertEquals(jdbc.queryForObject("SELECT SUM(word_count) FROM book_index WHERE book_id=1", Integer.class),
            jdbc.queryForObject("SELECT word_count FROM book WHERE id=1", Integer.class));
    }
}
