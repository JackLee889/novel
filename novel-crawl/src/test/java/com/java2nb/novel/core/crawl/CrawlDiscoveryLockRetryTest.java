package com.java2nb.novel.core.crawl;

import com.java2nb.novel.crawl.CrawlTestStores;
import com.java2nb.novel.entity.Book;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Faults happen after real writes so assertions cover rollback, not just mocked retries. */
@Timeout(15)
class CrawlDiscoveryLockRetryTest {
    JdbcTemplate jdbc;
    TestStore store;
    final String policy="fixture-policy";

    final class TestStore extends CrawlDiscoveryStore {
        final List<Long> pauses=new ArrayList<>();
        Runnable duringPause=()->{};
        boolean interruptPause;
        TestStore(JdbcTemplate template,DataSourceTransactionManager manager) { super(template,manager); }
        @Override protected void pauseBeforeRetry(long millis) throws InterruptedException {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Failed queue transaction must be closed before backoff");
            pauses.add(millis);duringPause.run();
            if(interruptPause) throw new InterruptedException("fixture shutdown");
        }
    }

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=spy(new JdbcTemplate(ds));
        CrawlTestStores.schema(jdbc);
        jdbc.execute("CREATE TABLE crawl_source(id INT PRIMARY KEY,source_status TINYINT)");
        jdbc.update("INSERT INTO crawl_source VALUES(1,1),(2,0)");
        store=new TestStore(jdbc,new DataSourceTransactionManager(ds));
    }
    @AfterEach void clearInterrupt() { Thread.interrupted(); }

    AtomicInteger failAfterWrite(String prefix,int failures) {
        AtomicInteger attempts=new AtomicInteger();
        doAnswer(call->{
            Object result=call.callRealMethod();
            if(attempts.incrementAndGet()<=failures) throw new CannotAcquireLockException("fixture rolled-back lock conflict");
            return result;
        }).when(jdbc).update(startsWith(prefix),any(Object[].class));
        return attempts;
    }
    int jobCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job",Integer.class); }
    int page() { return jdbc.queryForObject("SELECT next_page FROM crawl_discovery_cursor",Integer.class); }

    @Test void retryRollsBackEveryBatchAndCursorBeforeReplayingTheSamePage() {
        var cursor=store.cursor(1,1,policy);
        var attempts=failAfterWrite("UPDATE crawl_discovery_cursor",2);
        store.duringPause=()->{assertEquals(0,jobCount());assertEquals(1,page());};
        List<String> ids=IntStream.range(0,205).mapToObj(n->String.format("%04d",n)).toList();
        assertTrue(store.commitPage(cursor,3,ids,policy,Duration.ZERO));
        assertEquals(3,attempts.get());assertEquals(2,store.pauses.size());
        assertEquals(205,jobCount());assertEquals(2,page());
        assertFalse(store.commitPage(cursor,3,List.of("stale"),policy,Duration.ZERO));
        assertEquals(205,jobCount());
    }

    @Test void exhaustedRetriesKeepAllJobsAndThePageUncommittedAndBoundTheBackoff() {
        var cursor=store.cursor(1,1,policy);
        var attempts=failAfterWrite("UPDATE crawl_discovery_cursor",Integer.MAX_VALUE);
        assertThrows(CannotAcquireLockException.class,()->store.commitPage(cursor,2,List.of("b","a"),policy,Duration.ZERO));
        assertEquals(5,attempts.get());assertEquals(4,store.pauses.size());
        for(int i=0;i<store.pauses.size();i++) {
            long base=50L << i;assertTrue(store.pauses.get(i)>=base && store.pauses.get(i)<=base*2);
        }
        assertEquals(0,jobCount());assertEquals(1,page());
    }

    @Test void invalidDataIsRolledBackWithoutRetry() {
        var cursor=store.cursor(1,1,policy);
        jdbc.execute("ALTER TABLE crawl_discovery_job ADD CONSTRAINT fixture_bad CHECK(source_book_id<>'bad')");
        assertThrows(DataIntegrityViolationException.class,()->store.commitPage(cursor,2,List.of("good","bad"),policy,Duration.ZERO));
        assertTrue(store.pauses.isEmpty());assertEquals(0,jobCount());assertEquals(1,page());
    }

    @Test void shutdownDuringBackoffDoesNotStartAnotherTransaction() {
        var cursor=store.cursor(1,1,policy);
        var attempts=failAfterWrite("UPDATE crawl_discovery_cursor",Integer.MAX_VALUE);
        store.interruptPause=true;
        assertThrows(CannotAcquireLockException.class,()->store.commitPage(cursor,2,List.of("a"),policy,Duration.ZERO));
        assertTrue(Thread.currentThread().isInterrupted());Thread.interrupted();
        assertEquals(1,attempts.get());assertEquals(1,store.pauses.size());assertEquals(0,jobCount());assertEquals(1,page());
    }

    @Test void unchangedRescansDoNotUpsertJobsOrAlterRunningLeasesAndRetryDates() {
        store.commitPage(store.cursor(1,1,policy),1,List.of("a","b"),policy,Duration.ZERO);
        var owned=store.claim();
        jdbc.update("UPDATE crawl_discovery_job SET job_status='RETRY',next_attempt_at=?,consecutive_failures=3 WHERE id<>?",Timestamp.from(Instant.now().plusSeconds(3600)),owned.id());
        var before=jdbc.queryForList("SELECT * FROM crawl_discovery_job ORDER BY id");
        var cursor=store.cursor(1,1,policy);clearInvocations(jdbc);
        assertTrue(store.commitPage(cursor,1,List.of("b","a","a"),policy,Duration.ZERO));
        verify(jdbc,never()).update(startsWith("INSERT INTO crawl_discovery_job"),any(Object[].class));
        assertEquals(before,jdbc.queryForList("SELECT * FROM crawl_discovery_job ORDER BY id"));
    }

    @Test void aChangedFilterReconsidersFilteredJobsWhileTerminalJobsRemainTerminal() {
        store.commitPage(store.cursor(1,1,policy),1,List.of("a","b"),policy,Duration.ZERO);
        jdbc.update("UPDATE crawl_discovery_job SET job_status='FILTERED',next_attempt_at=? WHERE source_book_id='a'",Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("UPDATE crawl_discovery_job SET job_status='COMPLETE' WHERE source_book_id='b'");
        assertTrue(store.commitPage(store.cursor(1,1,policy),1,List.of("b","a"),"new-policy",Duration.ZERO));
        assertEquals("COMPLETE",jdbc.queryForObject("SELECT job_status FROM crawl_discovery_job WHERE source_book_id='b'",String.class));
        assertTrue(jdbc.queryForObject("SELECT next_attempt_at FROM crawl_discovery_job WHERE source_book_id='a'",Timestamp.class).before(Timestamp.from(Instant.now().plusSeconds(1))));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job WHERE policy_hash='new-policy'",Integer.class));
    }

    @Test void newIdsUseConsistentOrderAndStillDeduplicate() {
        store.commitPage(store.cursor(1,1,policy),1,List.of("c","a","b","a"),policy,Duration.ZERO);
        assertEquals(List.of("a","b","c"),jdbc.queryForList("SELECT source_book_id FROM crawl_discovery_job ORDER BY id",String.class));
    }

    @Test void claimRetriesDoNotConsumeLeasesOrAttemptsFromRolledBackWork() {
        store.commitPage(store.cursor(1,1,policy),1,List.of("a"),policy,Duration.ZERO);
        var attempts=failAfterWrite("UPDATE crawl_discovery_job SET job_status='RUNNING'",1);
        store.duringPause=()->assertEquals("PENDING",jdbc.queryForObject("SELECT job_status FROM crawl_discovery_job",String.class));
        var owned=store.claim();assertNotNull(owned);assertEquals(2,attempts.get());
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM crawl_discovery_job",Integer.class));
        assertEquals(owned.token(),jdbc.queryForObject("SELECT lease_token FROM crawl_discovery_job",String.class));
        assertNull(store.claim());
    }

    @Test void existingBookRetryKeepsJobAndRotationTimestampAtomic() {
        jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY,crawl_source_id INT,crawl_book_id VARCHAR(128),cat_id INT,crawl_is_stop TINYINT,book_status TINYINT,crawl_last_time TIMESTAMP)");
        Timestamp original=Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
        jdbc.update("INSERT INTO book VALUES(123,1,'existing',1,0,0,?)",original);
        var book=new Book();book.setId(123L);book.setCrawlSourceId(1);book.setCrawlBookId("existing");
        var attempts=failAfterWrite("UPDATE book SET crawl_last_time",1);
        store.duringPause=()->{assertEquals(0,jobCount());assertEquals(original,jdbc.queryForObject("SELECT crawl_last_time FROM book",Timestamp.class));};
        assertTrue(store.enqueueExistingBook(book,policy));
        assertEquals(2,attempts.get());assertEquals(1,jobCount());
        assertEquals(123L,store.claim().bookId());
    }

    @Test void queueWritesUseReadCommittedIsolation() {
        var cursor=store.cursor(1,1,policy);
        doAnswer(call->{
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return call.callRealMethod();
        }).when(jdbc).update(startsWith("UPDATE crawl_discovery_cursor"),any(Object[].class));
        assertTrue(store.commitPage(cursor,2,List.of("a"),policy,Duration.ZERO));
        assertNull(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
    }
}
