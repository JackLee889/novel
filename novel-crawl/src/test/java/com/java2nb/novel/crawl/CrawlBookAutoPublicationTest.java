package com.java2nb.novel.crawl;

import com.java2nb.novel.core.cache.CacheKey;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.mapper.BookCategoryMapper;
import com.java2nb.novel.mapper.BookContentMapper;
import com.java2nb.novel.mapper.CrawlBookIndexMapper;
import com.java2nb.novel.mapper.CrawlBookMapper;
import com.java2nb.novel.seo.SeoRepository;
import com.java2nb.novel.service.BookContentService;
import com.java2nb.novel.service.impl.BookServiceImpl;
import com.java2nb.novel.service.impl.DbBookContentServiceImpl;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 上架状态与实际章节/正文事务一起验证，仅使用独立内存库。 */
class CrawlBookAutoPublicationTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private BookServiceImpl books;
    private Map<String, BookContentService> storage;
    private CacheService cache;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE book (
              id BIGINT PRIMARY KEY, work_direction TINYINT, cat_id INT, cat_name VARCHAR(50),
              pic_url VARCHAR(200) NOT NULL, book_name VARCHAR(50), author_id BIGINT, author_name VARCHAR(50),
              book_desc VARCHAR(2000), score REAL, book_status TINYINT DEFAULT 0, visit_count BIGINT,
              word_count INT, comment_count INT, yesterday_buy INT, last_index_id BIGINT,
              last_index_name VARCHAR(50), last_index_update_time TIMESTAMP, is_vip TINYINT,
              status TINYINT DEFAULT 0, update_time TIMESTAMP NOT NULL, create_time TIMESTAMP,
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
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CrawlBookMapper.class);
        configuration.addMapper(CrawlBookIndexMapper.class);
        configuration.addMapper(BookContentMapper.class);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mybatis/mapping/BookMapper.xml"),
            new ClassPathResource("mybatis/mapping/BookIndexMapper.xml"));
        var sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        storage = new HashMap<>();
        storage.put("db", new DbBookContentServiceImpl(sql.getMapper(BookContentMapper.class)));
        cache = mock(CacheService.class);
        books = new BookServiceImpl(sql.getMapper(CrawlBookMapper.class), mock(BookCategoryMapper.class),
            sql.getMapper(CrawlBookIndexMapper.class), storage, cache, mock(SeoRepository.class));
        ReflectionTestUtils.setField(books, "storageType", "db");
    }

    @Test
    void pendingAuthorBooksPublishTheirChaptersAndEnrichmentPreservesEveryOtherField() {
        Book book = newBook();
        book.setAuthorName("作者待核验"); book.setCrawlSourceId(12); book.setCrawlBookId("766342");
        save(book, chapter(101L, 0), content(101L));
        assertEquals(1, status());
        assertEquals(1L, books.queryBookBySource(book.getBookName(), 12, "766342").getId());
        assertNull(books.queryBookBySource(book.getBookName(), 14, "766342"));
        assertNull(books.queryBookBySource(book.getBookName(), 12, "another"));
        var original = new HashMap<>(jdbc.queryForMap("SELECT * FROM book WHERE id=1"));
        var chapters = jdbc.queryForList("SELECT * FROM book_index");
        var bodies = jdbc.queryForList("SELECT * FROM book_content");
        assertEquals("真实作者", transaction.execute(tx -> books.fillPendingAuthor(1L, 12, "766342", "作者：真实作者")));
        original.put("AUTHOR_NAME", "真实作者");
        assertEquals(original, jdbc.queryForMap("SELECT * FROM book WHERE id=1"));
        assertEquals(chapters, jdbc.queryForList("SELECT * FROM book_index"));
        assertEquals(bodies, jdbc.queryForList("SELECT * FROM book_content"));
        assertEquals(1L, books.queryBookBySource(book.getBookName(), 12, "766342").getId());
        assertEquals("真实作者", transaction.execute(tx -> books.fillPendingAuthor(1L, 12, "766342", "其他作者")));
        assertEquals(original, jdbc.queryForMap("SELECT * FROM book WHERE id=1"));
    }

    @Test
    void authorEnrichmentNeverRebindsOrMergesAConflictingBook() {
        Book pending = newBook(); pending.setAuthorName("作者待核验");
        pending.setCrawlSourceId(12); pending.setCrawlBookId("a"); save(pending, chapter(101L, 0), content(101L));
        Book occupied = newBook(); occupied.setId(2L); occupied.setAuthorName("真实作者");
        BookIndex otherChapter = chapter(201L, 0); otherChapter.setBookId(2L);
        save(occupied, otherChapter, content(201L));
        var rows = jdbc.queryForList("SELECT * FROM book ORDER BY id");
        assertEquals("作者待核验", transaction.execute(tx -> books.fillPendingAuthor(1L, 14, "a", "真实作者")));
        assertEquals("作者待核验", transaction.execute(tx -> books.fillPendingAuthor(1L, 12, "b", "真实作者")));
        assertEquals("作者待核验", transaction.execute(tx -> books.fillPendingAuthor(1L, 12, "a", "佚名")));
        assertEquals("作者待核验", transaction.execute(tx -> books.fillPendingAuthor(1L, 12, "a", "真实作者")));
        assertEquals(rows, jdbc.queryForList("SELECT * FROM book ORDER BY id"));
        assertEquals(List.of(101L, 201L), jdbc.queryForList("SELECT id FROM book_index ORDER BY id", Long.class));
    }

    @Test
    void wideningDescriptionPreservesTheWholeExistingBookAndItsChapter() {
        Book book = newBook();
        book.setBookDesc("原简介😀<br/>完整保留 &amp; 末尾");
        save(book, chapter(101L, 0), content(101L));
        var row = jdbc.queryForMap("SELECT * FROM book WHERE id=1");
        var chapterRows = jdbc.queryForList("SELECT * FROM book_index");
        var bodyRows = jdbc.queryForList("SELECT * FROM book_content");
        jdbc.execute("ALTER TABLE book MODIFY COLUMN book_desc TEXT");
        assertEquals(row, jdbc.queryForMap("SELECT * FROM book WHERE id=1"));
        assertEquals(chapterRows, jdbc.queryForList("SELECT * FROM book_index"));
        assertEquals(bodyRows, jdbc.queryForList("SELECT * FROM book_content"));
    }

    @Test
    void widenedDescriptionSavesLongHtmlAndUnicodeWithoutTruncationThroughRealMappers() {
        jdbc.execute("ALTER TABLE book MODIFY COLUMN book_desc TEXT");
        Book book = newBook();
        String description = "完整简介😀<br/>".repeat(400) + "结尾标记";
        book.setBookDesc(description);
        save(book, chapter(101L, 0), content(101L));
        assertEquals(description, books.queryBookById(1L).getBookDesc());
        assertEquals(1, status());
        assertEquals(101L, jdbc.queryForObject("SELECT last_index_id FROM book WHERE id=1", Long.class));
        String updated = description + "<br/>更新后的完整结尾😀";
        book.setBookDesc(updated);
        var existing = books.queryExistBookIndexMap(1L);
        transaction.executeWithoutResult(tx -> books.updateBookAndIndexAndContent(book,
            List.of(chapter(102L, 1)), List.of(content(102L)), existing, List.of(), null));
        assertEquals(updated, books.queryBookById(1L).getBookDesc());
        assertEquals(List.of(101L, 102L), jdbc.queryForList("SELECT id FROM book_index ORDER BY index_num", Long.class));
        assertEquals(content(101L).getContent(), jdbc.queryForObject("SELECT content FROM book_content WHERE index_id=101", String.class));
    }

    @Test
    void missingCoverUsesDefaultImageAtFirstInsert() {
        Book book = newBook();
        save(book, chapter(101L, 0), content(101L));
        assertEquals("/images/default.gif", jdbc.queryForObject("SELECT pic_url FROM book WHERE id=1", String.class));
        assertEquals(1, status());
    }

    @Test
    void aProvidedCoverIsPreservedAndMissingCoverOnUpdateDoesNotReplaceIt() {
        Book book = newBook();
        book.setPicUrl("https://fixture/cover.jpg");
        save(book, chapter(101L, 0), content(101L));
        book.setPicUrl(null);
        save(book, chapter(102L, 1), content(102L));
        assertEquals("https://fixture/cover.jpg", jdbc.queryForObject("SELECT pic_url FROM book WHERE id=1", String.class));
    }

    @Test
    void newCollectedBookPublishesWithItsCommittedReadableChapter() {
        Book book = newBook();
        save(book, chapter(101L, 0), content(101L));
        assertEquals(1, status());
        assertEquals(101L, jdbc.queryForObject("SELECT last_index_id FROM book WHERE id=1", Long.class));
        assertEquals(1, count("book_index"));
        assertEquals(1, count("book_content"));
        verify(cache).del(CacheKey.INDEX_UPDATE_BOOK_KEY);
    }

    @Test
    void emptyFirstBatchDoesNotCreateOrPublishABook() {
        transaction.executeWithoutResult(tx -> books.saveBookAndIndexAndContent(newBook(), List.of(), List.of()));
        assertEquals(0, count("book"));
        assertEquals(0, count("book_index"));
        assertEquals(0, count("book_content"));
        verifyNoInteractions(cache);
    }

    @Test
    void failedFirstContentSaveRollsBackPublicationAndAllNewRows() {
        BookContentService failing = mock(BookContentService.class);
        doThrow(new IllegalStateException("Synthetic content storage failure"))
            .when(failing).saveBookContent(anyList(), eq(1L));
        storage.put("db", failing);
        assertThrows(IllegalStateException.class, () -> save(newBook(), chapter(101L, 0), content(101L)));
        assertEquals(0, count("book"));
        assertEquals(0, count("book_index"));
        assertEquals(0, count("book_content"));
        verifyNoInteractions(cache);
    }

    @Test
    void blankFirstChapterContentDoesNotCreateOrPublishABook() {
        BookContent blank = content(101L);
        blank.setContent("<p> \t&nbsp;&#160; </p>\n");
        assertThrows(IllegalArgumentException.class, () -> save(newBook(), chapter(101L, 0), blank));
        assertEquals(0, count("book"));
        assertEquals(0, count("book_index"));
        assertEquals(0, count("book_content"));
        verifyNoInteractions(cache);
    }

    @Test
    void firstBatchWithNoPositiveWordCountDoesNotCreateOrPublishABook() {
        BookIndex empty = chapter(101L, 0);
        empty.setWordCount(0);
        assertThrows(IllegalArgumentException.class, () -> save(newBook(), empty, content(101L)));
        assertEquals(0, count("book"));
        assertEquals(0, count("book_index"));
        assertEquals(0, count("book_content"));
        verifyNoInteractions(cache);
    }

    @Test
    void normalUpdatePreservesAnExistingManuallyUnpublishedBook() {
        Book book = newBook();
        save(book, chapter(101L, 0), content(101L));
        jdbc.update("UPDATE book SET status=0 WHERE id=1");
        book.setStatus((byte) 1); // 模拟采集任务持有人工下架前的旧对象。
        var existing = books.queryExistBookIndexMap(1L);
        transaction.executeWithoutResult(tx -> books.updateBookAndIndexAndContent(book,
            List.of(chapter(102L, 1)), List.of(content(102L)), existing, List.of(), null));
        assertEquals(0, status());
        assertEquals(102L, jdbc.queryForObject("SELECT last_index_id FROM book WHERE id=1", Long.class));
        assertEquals(2, count("book_content"));
    }

    @Test
    void laterNewBookBatchesAndEmptyFinalBatchDoNotUndoManualUnpublication() {
        Book book = newBook();
        save(book, chapter(101L, 0), content(101L));
        assertEquals(1, status());
        jdbc.update("UPDATE book SET status=0 WHERE id=1");
        book.setStatus((byte) 1);
        save(book, chapter(102L, 1), content(102L));
        assertEquals(0, status());
        // 新书采集的续批及收尾仍走 save 路径，不能重新上架。
        book.setStatus((byte) 1);
        transaction.executeWithoutResult(tx -> books.saveBookAndIndexAndContent(book, List.of(), List.of()));
        assertEquals(0, status());
        assertEquals(2, count("book_index"));
        assertEquals(2, count("book_content"));
    }

    private void save(Book book, BookIndex chapter, BookContent content) {
        transaction.executeWithoutResult(tx ->
            books.saveBookAndIndexAndContent(book, List.of(chapter), List.of(content)));
    }

    private Book newBook() {
        Book book = new Book();
        book.setId(1L);
        book.setBookName("Auto publication fixture");
        book.setAuthorName("Fixture author");
        book.setCrawlSourceId(4);
        book.setCrawlBookId("fixture");
        return book;
    }

    private BookIndex chapter(long id, int indexNum) {
        BookIndex chapter = new BookIndex();
        chapter.setId(id);
        chapter.setBookId(1L);
        chapter.setIndexNum(indexNum);
        chapter.setIndexName("Chapter " + (indexNum + 1));
        chapter.setWordCount(10);
        chapter.setCreateTime(new Date());
        return chapter;
    }

    private BookContent content(long chapterId) {
        BookContent content = new BookContent();
        content.setIndexId(chapterId);
        content.setContent("<p>这是独立测试的正常章节正文。</p>");
        return content;
    }

    private int status() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT status FROM book WHERE id=1", Integer.class));
    }

    private int count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }
}
