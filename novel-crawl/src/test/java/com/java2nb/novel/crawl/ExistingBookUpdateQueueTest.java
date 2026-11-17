package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.CrawlCollectionStore;
import com.java2nb.novel.core.crawl.CrawlDiscoveryStore;
import com.java2nb.novel.entity.Book;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real transactions: dispatch is durable, and the scheduler cannot steal a download lease. */
class ExistingBookUpdateQueueTest {
    JdbcTemplate jdbc;
    CrawlDiscoveryStore store;
    DataSourceTransactionManager manager;
    Book book;
    final Timestamp original=Timestamp.from(Instant.parse("2026-10-17T00:00:00Z"));

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        CrawlTestStores.schema(jdbc);
        jdbc.execute("CREATE TABLE crawl_source(id INT PRIMARY KEY,source_status TINYINT)");
        jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY,crawl_source_id INT,crawl_book_id VARCHAR(128),cat_id INT,crawl_is_stop TINYINT,book_status TINYINT,crawl_last_time TIMESTAMP)");
        jdbc.update("INSERT INTO crawl_source VALUES(12,1)");
        jdbc.update("INSERT INTO book VALUES(123,12,'49611',1,0,0,?)",original);
        store=new CrawlDiscoveryStore(jdbc,manager);
        book=new Book();book.setId(123L);book.setCrawlSourceId(12);book.setCrawlBookId("49611");book.setCatId(1);book.setBookStatus((byte)0);
    }

    @Test void dispatchBindsTheExistingBookAndSurvivesRestartWithoutDuplicatingJobs() {
        assertTrue(store.enqueueExistingBook(book,"rule"));
        assertTrue(store.enqueueExistingBook(book,"rule"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job",Integer.class));
        assertEquals("UPDATE",status());
        assertTrue(claimTime().after(original));
        var resumed=new CrawlDiscoveryStore(jdbc,manager).claim();
        assertNotNull(resumed);assertEquals(123L,resumed.bookId());
    }

    @Test void anOldPendingDiscoveryBecomesAnExplicitUpdateRatherThanAnExistingBookNoOp() {
        store.commitPage(store.cursor(12,1,"rule"),1,List.of("49611"),"rule",Duration.ZERO);
        assertEquals("PENDING",status());
        assertTrue(store.enqueueExistingBook(book,"rule"));
        assertEquals("UPDATE",status());
        assertEquals(123L,store.claim().bookId());
    }

    @Test void aFailedJobWriteDoesNotAdvanceTheBookRotationTimestamp() {
        jdbc.execute("ALTER TABLE crawl_discovery_job ADD CONSTRAINT fixture_failure CHECK(source_book_id<>'49611')");
        assertThrows(Exception.class,()->store.enqueueExistingBook(book,"rule"));
        assertEquals(original,claimTime());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job",Integer.class));
    }

    @Test void activeLeasesAndRetryBackoffArePreserved() {
        store.enqueueExistingBook(book,"rule");var job=store.claim();
        var time=claimTime();var lease=jdbc.queryForMap("SELECT lease_token,lease_until,attempts FROM crawl_discovery_job");
        assertFalse(store.enqueueExistingBook(book,"rule"));
        assertEquals(time,claimTime());
        assertEquals(lease,jdbc.queryForMap("SELECT lease_token,lease_until,attempts FROM crawl_discovery_job"));
        store.finish(job,"RETRY",123L,"timeout",true);
        var retry=jdbc.queryForMap("SELECT next_attempt_at,consecutive_failures FROM crawl_discovery_job");
        assertTrue(store.enqueueExistingBook(book,"rule"));
        assertEquals("RETRY",status());
        assertEquals(retry,jdbc.queryForMap("SELECT next_attempt_at,consecutive_failures FROM crawl_discovery_job"));
        assertNull(store.claim());
    }

    @Test void existingProgressKeepsResumePriorityAheadOfNewlyScheduledUpdates() {
        store.enqueueExistingBook(book,"rule");var job=store.claim();store.finish(job,"PARTIAL",123L,null,false);
        jdbc.update("UPDATE crawl_discovery_job SET next_attempt_at=?",Timestamp.from(Instant.EPOCH));
        jdbc.update("INSERT INTO book VALUES(124,12,'other',1,0,0,?)",original);
        var next=new Book();next.setId(124L);next.setCrawlSourceId(12);next.setCrawlBookId("other");next.setCatId(1);
        store.enqueueExistingBook(next,"rule");
        assertEquals(job.id(),store.claim(true).id());
    }

    @Test void resumePreferenceCannotConsumeTheRotationTurnForExistingUpdates() {
        store.enqueueExistingBook(book,"rule");
        jdbc.update("INSERT INTO crawl_discovery_job(source_id,source_book_id,cat_id,policy_hash,book_id,job_status,next_attempt_at,created_at,updated_at) "
            + "VALUES(12,'resume',1,'rule',124,'PARTIAL',?,?,?)",original,original,original);
        store.commitPage(store.cursor(12,1,"rule"),1,List.of("new"),"rule",Duration.ZERO);
        boolean updateClaimed=false;
        for(int turn=0;turn<12;turn++) {
            var job=store.claim(turn%2==1);
            assertNotNull(job);
            updateClaimed |= Long.valueOf(123).equals(job.bookId());
            store.finish(job,job.bookId()==null?"PENDING":"PARTIAL",job.bookId(),null,false);
            jdbc.update("UPDATE crawl_discovery_job SET next_attempt_at=? WHERE id=?",original,job.id());
        }
        assertTrue(updateClaimed,"Pending discovery and resumable work must not starve an existing-book update");
    }

    @Test void serialBooksAreReopenedButVerifiedCompletedBooksStop() {
        store.enqueueExistingBook(book,"rule");var job=store.claim();store.finish(job,"COMPLETE",123L,null,false);
        assertTrue(store.enqueueExistingBook(book,"rule"));assertEquals("UPDATE",status());
        jdbc.update("UPDATE book SET book_status=1 WHERE id=123");
        jdbc.update("INSERT INTO crawl_collection_state VALUES(123,12,'49611','hash',1,1,0,0,0,0,?,?,1,'COMPLETE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",new byte[]{1},new byte[]{1});
        assertFalse(store.enqueueExistingBook(book,"rule"));assertTrue(store.isVerifiedCompleted(book));
        jdbc.update("UPDATE crawl_collection_state SET missing_count=1");
        assertFalse(store.isVerifiedCompleted(book));
    }

    @Test void stoppedOrRemappedBooksAreNotQueuedAndConflictingIdentityRollsBack() {
        jdbc.update("UPDATE book SET crawl_is_stop=1 WHERE id=123");assertFalse(store.enqueueExistingBook(book,"rule"));
        jdbc.update("UPDATE book SET crawl_is_stop=0 WHERE id=123");
        jdbc.update("UPDATE crawl_source SET source_status=0 WHERE id=12");assertFalse(store.enqueueExistingBook(book,"rule"));
        jdbc.update("UPDATE crawl_source SET source_status=1 WHERE id=12");
        jdbc.update("UPDATE book SET crawl_book_id='changed' WHERE id=123");assertFalse(store.enqueueExistingBook(book,"rule"));
        jdbc.update("UPDATE book SET crawl_book_id='49611' WHERE id=123");
        store.commitPage(store.cursor(12,1,"rule"),1,List.of("49611"),"rule",Duration.ZERO);
        jdbc.update("UPDATE crawl_discovery_job SET book_id=999");
        assertThrows(IllegalStateException.class,()->store.enqueueExistingBook(book,"rule"));
        assertEquals(original,claimTime());assertEquals(999L,jdbc.queryForObject("SELECT book_id FROM crawl_discovery_job",Long.class));
    }

    String status(){return jdbc.queryForObject("SELECT job_status FROM crawl_discovery_job WHERE source_book_id='49611'",String.class);}
    Timestamp claimTime(){return jdbc.queryForObject("SELECT crawl_last_time FROM book WHERE id=123",Timestamp.class);}
}
