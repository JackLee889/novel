package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.CrawlCollectionStore;
import com.java2nb.novel.core.crawl.CrawlDiscoveryStore;
import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.mapper.CrawlSingleTaskMapper;
import com.java2nb.novel.mapper.CrawlSourceMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import com.java2nb.novel.utils.CrawlHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transactional queue/cursor storage, including restart, rollback and competing leases. */
@Timeout(10)
class CrawlDiscoveryPersistenceTest {
    JdbcTemplate jdbc;
    CrawlDiscoveryStore store;
    DataSourceTransactionManager manager;
    String hash=CrawlCollectionStore.hash("fixture rule");

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds); manager=new DataSourceTransactionManager(ds);
        CrawlTestStores.schema(jdbc);
        jdbc.execute("CREATE TABLE crawl_source(id INT PRIMARY KEY,source_status TINYINT)");
        jdbc.update("INSERT INTO crawl_source VALUES(1,1),(2,0)");
        store=new CrawlDiscoveryStore(jdbc,manager);
    }
    @AfterEach void resetInterrupt() { Thread.interrupted(); }

    @Test void restartResumesAtCommittedPageAndDuplicatesDoNotMultiplyJobs() {
        var first=store.cursor(1,8,hash);
        assertTrue(store.commitPage(first,3,List.of("a","b","a"),hash,Duration.ofMinutes(1)));
        var restarted=new CrawlDiscoveryStore(jdbc,manager);
        var second=restarted.cursor(1,8,hash); assertEquals(2,second.page());
        assertTrue(restarted.commitPage(second,3,List.of("b","c"),hash,Duration.ofMinutes(1)));
        assertEquals(3,store.cursor(1,8,hash).page());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job",Integer.class));
        assertFalse(store.commitPage(first,3,List.of("stale"),hash,Duration.ofMinutes(1)));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job",Integer.class));
    }

    @Test void failedPageTransactionRollsBackBothItsJobsAndItsCursor() {
        jdbc.execute("ALTER TABLE crawl_discovery_job ADD CONSTRAINT synthetic_failure CHECK(source_book_id<>'bad')");
        var first=store.cursor(1,1,hash);
        assertThrows(Exception.class,()->store.commitPage(first,2,List.of("good","bad"),hash,Duration.ofMinutes(1)));
        assertEquals(1,store.cursor(1,1,hash).page());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job",Integer.class));
    }

    @Test void leaseOwnerIsExclusiveAndExpiredWorkCanResumeWithoutStaleCompletion() {
        enqueue(1,"a"); var first=store.claim(); assertNotNull(first); assertNull(store.claim());
        jdbc.update("UPDATE crawl_discovery_job SET lease_until=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),first.id());
        var replacement=new CrawlDiscoveryStore(jdbc,manager).claim(); assertEquals(first.id(),replacement.id());
        assertNotEquals(first.token(),replacement.token()); assertFalse(store.renew(first));
        store.finish(first,"COMPLETE",123L,null,false);
        assertEquals("RUNNING",status(first.id()));
        store.finish(replacement,"PARTIAL",123L,null,false); assertEquals("PARTIAL",status(first.id()));
        assertEquals(2,jdbc.queryForObject("SELECT attempts FROM crawl_discovery_job",Integer.class));
    }

    @Test void simultaneousClaimsReturnDifferentJobs() throws Exception {
        enqueue(1,"a","b");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            Callable<CrawlDiscoveryStore.Job> task=()->{gate.await();return new CrawlDiscoveryStore(jdbc,manager).claim();};
            var a=executor.submit(task);var b=executor.submit(task);gate.countDown();
            var first=a.get(3,TimeUnit.SECONDS);var second=b.get(3,TimeUnit.SECONDS);
            // SKIP LOCKED may yield an empty poll while another transaction owns a candidate.
            if(first==null) first=store.claim();if(second==null) second=store.claim();
            assertNotNull(first);assertNotNull(second);assertNotEquals(first.id(),second.id());
        }
    }

    @Test void stoppedSourcesAreNotClaimedAndFilteredBooksAreReconsideredAfterPolicyChanges() {
        enqueue(2,"stopped");assertNull(store.claim());
        enqueue(1,"a");var job=store.claim();store.finish(job,"FILTERED",null,null,false);assertNull(store.claim());
        var cursor=store.cursor(1,1,hash);
        store.commitPage(cursor,1,List.of("a"),hash,Duration.ZERO);assertNull(store.claim());
        cursor=store.cursor(1,1,hash);
        store.commitPage(cursor,1,List.of("a"),CrawlCollectionStore.hash("ALL now"),Duration.ZERO);
        assertEquals(job.id(),store.claim().id());
        assertEquals(0,jdbc.queryForObject("SELECT source_status FROM crawl_source WHERE id=2",Integer.class));
    }

    @Test void failuresBackOffAndTerminalJobsAreNotReopenedByCategoryRescans() {
        enqueue(1,"a");var job=store.claim();store.finish(job,"RETRY",null,"FixtureFailure",true);
        assertNull(store.claim());
        assertEquals(1,jdbc.queryForObject("SELECT consecutive_failures FROM crawl_discovery_job",Integer.class));
        jdbc.update("UPDATE crawl_discovery_job SET next_attempt_at=?",Timestamp.from(Instant.EPOCH));
        var retry=store.claim();store.finish(retry,"COMPLETE",42L,null,false);
        store.commitPage(store.cursor(1,1,hash),1,List.of("a"),hash,Duration.ZERO);
        assertNull(store.claim());assertEquals("COMPLETE",status(job.id()));
    }

    @Test void bookCheckpointSurvivesInterruptionAndResumePollsDoNotStarveBehindNewDiscoveries() {
        enqueue(1,"a","b","c");var first=store.claim();
        store.checkpointBook(first,123L);
        store.finish(first,"PARTIAL",null,"Interrupted",false);
        jdbc.update("UPDATE crawl_discovery_job SET next_attempt_at=? WHERE id=?",Timestamp.from(Instant.now().minusMillis(1)),first.id());
        var resumed=store.claim(true);assertEquals(first.id(),resumed.id());assertEquals(123L,resumed.bookId());
        assertNotEquals(first.id(),store.claim(false).id());
        assertThrows(IllegalStateException.class,()->store.checkpointBook(first,456L));
    }

    @Test void interruptedCommittedBookKeepsResumePriorityEvenWhenTheClaimStartedWithoutABookId() {
        enqueue(1,"a","b");var first=store.claim();assertNull(first.bookId());
        store.checkpointBook(first,123L);store.finish(first,"PENDING",null,"Interrupted",false);
        assertEquals("PARTIAL",status(first.id()));
        assertEquals(0,jdbc.queryForObject("SELECT consecutive_failures FROM crawl_discovery_job WHERE id=?",Integer.class,first.id()));
        jdbc.update("UPDATE crawl_discovery_job SET next_attempt_at=? WHERE id=?",Timestamp.from(Instant.EPOCH),first.id());
        assertEquals(123L,store.claim(true).bookId());
    }

    @Test void dueRetryIsNotStarvedByAnOlderPendingBacklog() {
        enqueue(1,"retry","a","b","c","d","e","f","g","h","i");
        var retry=store.claim();store.finish(retry,"RETRY",null,"FixtureFailure",true);
        jdbc.update("UPDATE crawl_discovery_job SET next_attempt_at=? WHERE id=?",Timestamp.from(Instant.EPOCH),retry.id());
        boolean recovered=false;
        for(int i=0;i<5;i++) if(store.claim(false).id()==retry.id()) { recovered=true;break; }
        assertTrue(recovered,"Indexed status rotation gives retries a turn before draining all pending books");
    }

    @Test void listPaginationEnqueuesWithoutCallingTheBodyParserAndRestartsOnTheNextPage() throws Exception {
        var http=mock(CrawlHttpClient.class);var parser=mock(CrawlParser.class);
        var service=new CrawlServiceImpl(parser,mock(CrawlSourceMapper.class),mock(CrawlSingleTaskMapper.class),mock(BookService.class),http);
        service.setDiscoveryStore(store);
        var rule=new RuleBean();rule.setCharset("UTF-8");rule.setBookListUrl("https://fixture/{catId}/{page}");
        rule.setCatIdRule(Map.of("catId1","all"));rule.setBookIdPatten("book=(\\w+)");rule.setTotalPagePatten("pages=(\\d+)");
        when(http.get("https://fixture/all/1","UTF-8")).thenReturn("pages=9 book=first book=second");
        when(http.get("https://fixture/all/2","UTF-8")).thenThrow(new InterruptedException("Restart fixture"));
        service.parseBookList(1,rule,1);Thread.interrupted();
        verifyNoInteractions(parser);
        assertEquals(2,jdbc.queryForObject("SELECT next_page FROM crawl_discovery_cursor",Integer.class));
        clearInvocations(http);
        doReturn("pages=9 book=third").when(http).get("https://fixture/all/2","UTF-8");
        when(http.get("https://fixture/all/3","UTF-8")).thenThrow(new InterruptedException("End fixture"));
        var restarted=new CrawlServiceImpl(parser,mock(CrawlSourceMapper.class),mock(CrawlSingleTaskMapper.class),mock(BookService.class),http);
        restarted.setDiscoveryStore(new CrawlDiscoveryStore(jdbc,manager));restarted.parseBookList(1,rule,1);
        verify(http,never()).get("https://fixture/all/1","UTF-8");
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job",Integer.class));
    }

    private String status(long id) { return jdbc.queryForObject("SELECT job_status FROM crawl_discovery_job WHERE id=?",String.class,id); }
    private void enqueue(int source,String... books) { store.commitPage(store.cursor(source,1,hash),1,List.of(books),hash,Duration.ZERO); }
}
