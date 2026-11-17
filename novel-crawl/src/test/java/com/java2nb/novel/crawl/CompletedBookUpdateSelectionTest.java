package com.java2nb.novel.crawl;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.mapper.CrawlBookMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the real update mapper against an isolated database, including source identity and holes. */
class CompletedBookUpdateSelectionTest {
    private JdbcTemplate jdbc;
    private CrawlBookMapper mapper;
    private final Date cutoff = Date.from(Instant.parse("2026-10-18T00:30:00Z"));

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE crawl_source(id INT PRIMARY KEY,source_status TINYINT)");
        jdbc.execute("""
            CREATE TABLE book(id BIGINT PRIMARY KEY,book_status TINYINT,crawl_source_id INT,
              crawl_book_id VARCHAR(128),crawl_last_time TIMESTAMP,pic_url VARCHAR(200),
              word_count INT,crawl_is_stop TINYINT)
            """);
        CrawlTestStores.schema(jdbc);
        jdbc.update("INSERT INTO crawl_source VALUES(12,1),(13,1),(14,0)");
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CrawlBookMapper.class);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mybatis/mapping/BookMapper.xml"));
        mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(CrawlBookMapper.class);
    }

    @Test
    void completedAndVerifiedBooksLeaveTheQueueWhileSerializingAndIncompleteBooksContinue() {
        book(1, 1); completeState(1);
        book(2, 1); completeState(2);
        jdbc.update("UPDATE crawl_collection_state SET collection_status='INCOMPLETE',ready_count=34,missing_count=1,deferred_count=1 WHERE book_id=2");
        book(3, 1); completeState(3);
        jdbc.update("UPDATE crawl_collection_state SET collection_status='INCOMPLETE',ready_count=0,unchecked_count=35 WHERE book_id=3");
        book(4, 0); completeState(4);
        jdbc.update("UPDATE crawl_collection_state SET source_completed=0 WHERE book_id=4");
        book(5, 1); // Legacy completed book: no evidence that its bodies have been checked.
        assertEquals(List.of(2L, 3L, 4L, 5L), selected());
        assertEquals(0, jdbc.queryForObject("SELECT crawl_is_stop FROM book WHERE id=1", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "collection_status='INCOMPLETE'", "collection_status='UNVERIFIED'", "source_completed=0",
        "expected_count=0,ready_count=0", "ready_count=34", "missing_count=1", "unchecked_count=1",
        "failed_count=1", "deferred_count=1", "source_id=13", "source_book_id='another-book'"
    })
    void uncertainOrMismatchedCompletionNeverSuppressesUpdates(String mutation) {
        book(1, 1); completeState(1);
        jdbc.update("UPDATE crawl_collection_state SET " + mutation + " WHERE book_id=1");
        assertEquals(List.of(1L), selected());
    }

    @Test
    void aSerializingOrUnknownBookIsEligibleEvenIfItsOldStateSaysCompleted() {
        book(1, 0); completeState(1);
        book(2, 1); completeState(2);
        jdbc.update("UPDATE book SET book_status=NULL WHERE id=2");
        assertEquals(List.of(1L, 2L), selected());
    }

    @Test
    void fillingTheLastHoleRemovesOnlyTheCompletedBookFromTheNextClaim() {
        book(1, 1); completeState(1);
        jdbc.update("UPDATE crawl_collection_state SET collection_status='INCOMPLETE',ready_count=34,missing_count=1 WHERE book_id=1");
        book(2, 0); completeState(2);
        assertEquals(List.of(1L, 2L), selected());
        jdbc.update("UPDATE crawl_collection_state SET collection_status='COMPLETE',ready_count=35,missing_count=0 WHERE book_id=1");
        assertEquals(List.of(2L), selected());
    }

    @Test
    void sourceStopBookStopAndRotationStillApplyAndSkippedBooksDoNotConsumeTheLimit() {
        book(1, 1); completeState(1);
        book(2, 0); jdbc.update("UPDATE book SET crawl_source_id=14 WHERE id=2");
        book(3, 1); jdbc.update("UPDATE book SET crawl_is_stop=1 WHERE id=3");
        book(4, 0); jdbc.update("UPDATE book SET crawl_last_time=? WHERE id=4", new Timestamp(cutoff.getTime() + 1));
        book(5, 1);
        book(6, 0);
        assertEquals(List.of(5L), mapper.queryNeedUpdateBook(cutoff, 1).stream().map(Book::getId).toList());
        assertEquals(List.of(5L, 6L), selected());
    }

    @Test
    void completionDoesNotExpireBackIntoAutomaticCrawlingAfterOneDay() {
        book(1, 1); completeState(1);
        jdbc.update("UPDATE crawl_collection_state SET validation_started_at=TIMESTAMP '2020-01-01 00:00:00',updated_at=TIMESTAMP '2020-01-01 00:00:00'");
        assertEquals(List.of(), selected());
    }

    private void book(long id, int status) {
        jdbc.update("INSERT INTO book VALUES(?,?,12,?,?,NULL,1000,0)", id, status, "source-" + id,
            Timestamp.from(Instant.parse("2026-10-18T00:00:00Z").plusSeconds(id)));
    }

    private void completeState(long id) {
        jdbc.update("""
            INSERT INTO crawl_collection_state(book_id,source_id,source_book_id,catalog_hash,expected_count,
              ready_count,missing_count,unchecked_count,failed_count,deferred_count,ready_bits,checked_bits,
              source_completed,collection_status,validation_started_at,updated_at)
            VALUES(?,12,?,'fixture',35,35,0,0,0,0,?,?,1,'COMPLETE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, id, "source-" + id, new byte[] {1}, new byte[] {1});
    }

    private List<Long> selected() {
        return mapper.queryNeedUpdateBook(cutoff, 100).stream().map(Book::getId).toList();
    }
}
