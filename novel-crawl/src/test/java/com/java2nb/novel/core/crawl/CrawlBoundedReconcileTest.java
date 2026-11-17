package com.java2nb.novel.core.crawl;

import com.java2nb.novel.crawl.CrawlTestStores;
import com.java2nb.novel.entity.Book;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrawlBoundedReconcileTest {
    JdbcTemplate jdbc;CrawlDiscoveryStore queue;CrawlRefreshStore refresh;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=spy(new JdbcTemplate(ds));CrawlTestStores.schema(jdbc);
        jdbc.execute("CREATE TABLE crawl_source(id INT PRIMARY KEY,source_status TINYINT)");
        jdbc.update("INSERT INTO crawl_source VALUES(1,1),(2,0)");
        jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY,crawl_source_id INT,crawl_book_id VARCHAR(128),cat_id INT,book_status TINYINT,crawl_is_stop TINYINT,crawl_last_time TIMESTAMP,last_index_update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE crawl_book_io_state(book_id BIGINT PRIMARY KEY,revision BIGINT,checked_revision BIGINT)");
        queue=new CrawlDiscoveryStore(jdbc,new DataSourceTransactionManager(ds));refresh=new CrawlRefreshStore(jdbc,queue);refresh.initialize();
    }
    void book(long id,int status) {jdbc.update("INSERT INTO book VALUES(?,1,?,1,?,0,?,?)",id,"b"+id,status,Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.EPOCH));}
    void nextSweep(String name) {ReflectionTestUtils.setField(ReflectionTestUtils.getField(refresh,name),"nextRun",0L);}
    @Test void emptyEligibleBatchDoesNotEndBootstrapOrLoseLaterBooks() {
        for(int i=1;i<=1203;i++)book(i,i<=500?1:0);
        assertEquals(0,refresh.seedSerials(500));assertTrue(refresh.seedSweepPending());
        assertEquals(500,refresh.seedSerials(500));assertTrue(refresh.seedSweepPending());
        assertEquals(203,refresh.seedSerials(500));assertFalse(refresh.seedSweepPending());
        assertEquals(703,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_book_refresh",Integer.class));
        assertEquals(703,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job",Integer.class));
        clearInvocations(jdbc);assertEquals(0,refresh.seedSerials(500));assertEquals(0,refresh.seedSerials(500));verifyNoInteractions(jdbc);
    }
    @Test void newIdentityReconcilesOnNextSweepAndExistingCheckpointsAndLeasesSurviveRestart() {
        book(1,0);book(2,0);refresh.seedSerials(500);
        jdbc.update("UPDATE crawl_book_refresh SET pending_from=37,failures=2 WHERE book_id=1");
        var job=queue.claimBackground(false,List.of(1));assertNotNull(job);
        jdbc.update("UPDATE book SET crawl_book_id='replacement' WHERE id=2");book(3,0);
        nextSweep("serialSweep");refresh.seedSerials(500);
        assertEquals(37,jdbc.queryForObject("SELECT pending_from FROM crawl_book_refresh WHERE book_id=1",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT failures FROM crawl_book_refresh WHERE book_id=1",Integer.class));
        assertEquals("replacement",jdbc.queryForObject("SELECT source_book_id FROM crawl_book_refresh WHERE book_id=2",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job WHERE source_book_id='replacement'",Integer.class));
        var restarted=new CrawlRefreshStore(jdbc,queue);restarted.seedSerials(500);
        assertEquals(job.token(),jdbc.queryForObject("SELECT lease_token FROM crawl_discovery_job WHERE id=?",String.class,job.id()));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_book_refresh",Integer.class));
    }
    @Test void completedScanSkipsVerifiedPrefixThenDrainsEveryCandidateWithoutDuplicateOrOmission() {
        for(int i=1;i<=1103;i++)book(i,i<=500?0:1);
        var cutoff=Date.from(Instant.now());assertTrue(refresh.completedCandidates(cutoff,100).isEmpty());assertTrue(refresh.completedSweepPending());
        var found=new ArrayList<Long>();
        do {found.addAll(refresh.completedCandidates(cutoff,100).stream().map(Book::getId).toList());} while(refresh.completedSweepPending());
        assertEquals(603,found.size());assertEquals(603,new HashSet<>(found).size());assertEquals(501L,found.getFirst());assertEquals(1103L,found.getLast());
        clearInvocations(jdbc);assertTrue(refresh.completedCandidates(cutoff,100).isEmpty());verifyNoInteractions(jdbc);
    }
    @Test void completionIdentityRevisionSourceAndStopGuardsStillApply() {
        for(int i=1;i<=8;i++)book(i,1);
        for(int i=1;i<=6;i++)jdbc.update("INSERT INTO crawl_collection_state(book_id,source_id,source_book_id,catalog_hash,expected_count,ready_count,missing_count,unchecked_count,failed_count,deferred_count,source_completed,collection_status,ready_bits,checked_bits,validation_started_at,updated_at) VALUES(?,1,?,'hash',10,10,0,0,0,0,1,'COMPLETE',X'FF',X'FF',?,?)",i,"b"+i,Timestamp.from(Instant.now()),Timestamp.from(Instant.now()));
        jdbc.update("UPDATE crawl_collection_state SET missing_count=1 WHERE book_id=2");
        jdbc.update("UPDATE crawl_collection_state SET source_book_id='old' WHERE book_id=3");
        jdbc.update("INSERT INTO crawl_book_io_state VALUES(4,2,1)");
        jdbc.update("UPDATE book SET crawl_source_id=2 WHERE id=5");
        jdbc.update("UPDATE book SET crawl_is_stop=1 WHERE id=6");
        jdbc.update("UPDATE book SET crawl_last_time=? WHERE id=8",Timestamp.from(Instant.now().plusSeconds(600)));
        ReflectionTestUtils.setField(refresh,"ioVersioned",true);
        assertEquals(List.of(2L,3L,4L,7L),refresh.completedCandidates(Date.from(Instant.now()),100).stream().map(Book::getId).toList());
    }
    @Test void schedulerUsesBoundedThresholdWhileDashboardRetainsExactCounts() {
        var cursor=queue.cursor(1,1,"fixture");queue.commitPage(cursor,1,java.util.stream.IntStream.range(0,105).mapToObj(i->"b"+i).toList(),"fixture",Duration.ZERO);
        jdbc.update("UPDATE crawl_discovery_job SET job_status='PARTIAL'");
        clearInvocations(jdbc);assertEquals(Duration.ofSeconds(30),refresh.scanPageDelay(1));
        assertTrue(mockingDetails(jdbc).getInvocations().stream().noneMatch(i->Arrays.stream(i.getArguments()).anyMatch(a->a instanceof String s && s.contains("COUNT("))));
        assertEquals(105,refresh.stats(1).backfillWaiting());
        assertEquals(Duration.ofSeconds(3),refresh.scanPageDelay(2));
    }
    @Test void closedSourcesPauseBothWindowsWithoutBookReadsOrWritesAndResumeTheirCursor(){
        for(int i=1;i<=6;i++)book(i,0);
        assertEquals(2,refresh.seedSerials(2));assertTrue(refresh.seedSweepPending());
        var pending=jdbc.queryForList("SELECT * FROM crawl_discovery_job ORDER BY id");
        jdbc.update("UPDATE crawl_source SET source_status=0");ReflectionTestUtils.setField(refresh,"sourcesUntil",0L);
        clearInvocations(jdbc);
        assertEquals(0,refresh.seedSerials(2));assertFalse(refresh.seedSweepPending());
        assertTrue(refresh.completedCandidates(Date.from(Instant.now()),100).isEmpty());assertFalse(refresh.completedSweepPending());
        assertTrue(mockingDetails(jdbc).getInvocations().stream().flatMap(i->Arrays.stream(i.getArguments())).filter(String.class::isInstance).map(String.class::cast)
            .allMatch(s->s.equals("SELECT id FROM crawl_source WHERE source_status=1 ORDER BY id")));
        assertEquals(pending,jdbc.queryForList("SELECT * FROM crawl_discovery_job ORDER BY id"));
        jdbc.update("UPDATE crawl_source SET source_status=1 WHERE id=1");ReflectionTestUtils.setField(refresh,"sourcesUntil",0L);
        assertEquals(2,refresh.seedSerials(2));assertEquals(2,refresh.seedSerials(2));
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_book_refresh",Integer.class));
    }
}
