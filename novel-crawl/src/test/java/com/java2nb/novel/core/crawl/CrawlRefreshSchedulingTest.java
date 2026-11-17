package com.java2nb.novel.core.crawl;

import com.java2nb.novel.crawl.CrawlTestStores;
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

import static org.junit.jupiter.api.Assertions.*;

/** Real transactions and shared queue leases; no production connections. */
@Timeout(15)
class CrawlRefreshSchedulingTest {
    JdbcTemplate jdbc;
    CrawlDiscoveryStore queue;
    CrawlRefreshStore refresh;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);CrawlTestStores.schema(jdbc);
        jdbc.execute("CREATE TABLE crawl_source(id INT PRIMARY KEY,source_status TINYINT)");
        jdbc.update("INSERT INTO crawl_source VALUES(1,1),(2,0),(3,1)");
        jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY,crawl_source_id INT,crawl_book_id VARCHAR(128),cat_id INT,book_status TINYINT,crawl_is_stop TINYINT,crawl_last_time TIMESTAMP,last_index_update_time TIMESTAMP)");
        queue=new CrawlDiscoveryStore(jdbc,new DataSourceTransactionManager(ds));
        refresh=new CrawlRefreshStore(jdbc,queue);refresh.initialize();
    }
    void book(long id,int source,int status,int stopped) {
        jdbc.update("INSERT INTO book VALUES(?,?,?,1,?,?,?,?)",id,source,"b"+id,status,stopped,Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.now()));
    }
    void due() {jdbc.update("UPDATE crawl_book_refresh SET next_check_at=?",Timestamp.from(Instant.EPOCH));}
    @Test void additiveInitializationIsSafeToRepeatAfterRestart() {
        refresh.initialize();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_book_refresh",Integer.class));
    }
    @Test void seedingIsIdempotentPreservesOriginalTimestampAndExcludesStoppedOrCompletedBooks() {
        book(1,1,0,0);book(2,2,0,0);book(3,1,1,0);book(4,1,0,1);
        assertEquals(1,refresh.seedSerials(500));assertEquals(0,refresh.seedSerials(500));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_book_refresh",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job",Integer.class));
        assertEquals(Instant.EPOCH,jdbc.queryForObject("SELECT crawl_last_time FROM book WHERE id=1",Timestamp.class).toInstant());
        assertEquals(0,jdbc.queryForObject("SELECT source_status FROM crawl_source WHERE id=2",Integer.class));
    }
    @Test void refreshAndBackgroundCannotOwnTheSameBookAndStaleCompletionsAreIgnored() {
        book(1,1,0,0);refresh.seedSerials(500);due();
        var first=refresh.claim();assertNotNull(first);assertNull(queue.claimBackground(true,List.of(1)));
        jdbc.update("UPDATE crawl_discovery_job SET lease_until=? WHERE id=?",Timestamp.from(Instant.EPOCH),first.id());
        var newer=queue.claimBackground(false,List.of(1));assertNotNull(newer);assertNotEquals(first.token(),newer.token());
        refresh.finish(first,false,null);
        assertEquals("RUNNING",jdbc.queryForObject("SELECT job_status FROM crawl_discovery_job WHERE id=?",String.class,newer.id()));
        assertNull(jdbc.queryForObject("SELECT last_checked_at FROM crawl_book_refresh",Timestamp.class));
        queue.finish(newer,"PARTIAL",1L,null,false);
    }
    @Test void oneSlowSourceCannotReserveEveryRefreshWorker() {
        book(1,1,0,0);book(2,1,0,0);book(3,3,0,0);refresh.seedSerials(500);due();
        var a=refresh.claim();var b=refresh.claim();
        assertNotNull(a);assertNotNull(b);assertNotEquals(a.sourceId(),b.sourceId());assertNull(refresh.claim());
        refresh.finish(a,false,null);refresh.finish(b,false,null);
    }
    @Test void aDedicatedWorkerClaimsOnlyItsOwnSourceEvenWhenAnotherSourceHasDueBooks() {
        book(1,1,0,0);book(3,3,0,0);refresh.seedSerials(500);due();
        var source3=refresh.claimForSource(3);assertNotNull(source3);assertEquals(3,source3.sourceId());
        assertNull(refresh.claimForSource(3));
        refresh.finish(source3,false,null);
        assertNull(refresh.claimForSource(3),"An idle dedicated worker cannot borrow source 1's task");
        assertEquals("UPDATE",jdbc.queryForObject("SELECT job_status FROM crawl_discovery_job WHERE source_id=1",String.class));
        var source1=refresh.claimForSource(1);assertNotNull(source1);assertEquals(1,source1.sourceId());
        assertNull(queue.claimBackground(false,List.of(1)),"The original lease still excludes background writers");
        refresh.finish(source1,false,null);
    }
    @Test void dedicatedClaimsStillRespectAStopAfterTheEnabledSourceListWasCached() {
        book(1,1,0,0);refresh.seedSerials(500);due();refresh.enabledSources();
        jdbc.update("UPDATE crawl_source SET source_status=0 WHERE id=1");
        assertNull(refresh.claimForSource(1));assertNull(refresh.claimForSource(2));
        assertEquals("UPDATE",jdbc.queryForObject("SELECT job_status FROM crawl_discovery_job",String.class));
        assertNull(jdbc.queryForObject("SELECT last_attempted_at FROM crawl_book_refresh",Timestamp.class));
    }
    @Test void aLargePendingBacklogDoesNotBlockRefreshOrSourceFairness() {
        book(1,1,0,0);book(2,3,0,0);refresh.seedSerials(500);due();
        var cursor=queue.cursor(1,1,"fixture");
        queue.commitPage(cursor,1,java.util.stream.IntStream.range(0,500).mapToObj(i->"new"+i).toList(),"fixture",Duration.ZERO);
        var a=refresh.claim();var b=refresh.claim();assertNotEquals(a.sourceId(),b.sourceId());
        assertEquals(500,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job WHERE job_status='PENDING'",Integer.class));
        refresh.finish(a,false,null);refresh.finish(b,false,null);
    }
    @Test void fourBusySourcesEachReceiveEveryTaskTypeDespiteGlobalResumePreference() {
        jdbc.update("INSERT INTO crawl_source VALUES(12,1),(13,1),(14,1)");
        var sources=List.of(1,12,13,14);
        var states=List.of("PENDING","UPDATE","RETRY","FILTERED","RUNNING","PARTIAL");
        for(int source:sources) {
            var cursor=queue.cursor(source,1,"fair");
            queue.commitPage(cursor,1,states,"fair",Duration.ZERO);
            for(String state:states) jdbc.update("UPDATE crawl_discovery_job SET job_status=?,next_attempt_at=?,lease_until=? "
                + "WHERE source_id=? AND source_book_id=?",state,Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.EPOCH),source,state);
        }
        Map<Integer,Set<String>> seen=new HashMap<>();
        for(int turn=0;turn<24;turn++) {
            var job=queue.claimBackground(turn%2!=0,sources);assertNotNull(job);
            assertEquals(sources.get(turn%4),job.sourceId());
            seen.computeIfAbsent(job.sourceId(),ignored->new HashSet<>()).add(job.sourceBookId());
            // Keep all six queues continuously busy, including recoverable expired leases.
            jdbc.update("UPDATE crawl_discovery_job SET job_status=?,lease_token=NULL,lease_until=?,next_attempt_at=? WHERE id=?",
                job.sourceBookId(),Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.EPOCH),job.id());
        }
        for(int source:sources) assertEquals(new HashSet<>(states),seen.get(source),"Task type starved for source "+source);
    }

    @Test void dedicatedDownloadClaimsNeverBorrowFromAnotherSourceAndRetainExclusiveLeases() {
        queue.commitPage(queue.cursor(1,1,"dedicated"),1,List.of("one"),"dedicated",Duration.ZERO);
        queue.commitPage(queue.cursor(3,1,"dedicated"),1,List.of("three"),"dedicated",Duration.ZERO);
        queue.commitPage(queue.cursor(2,1,"stopped"),1,List.of("off"),"stopped",Duration.ZERO);
        var one=queue.claimBackground(false,List.of(1));assertNotNull(one);assertEquals(1,one.sourceId());
        assertNull(queue.claimBackground(true,List.of(1)),"An idle source worker cannot take source 3's waiting book");
        var three=queue.claimBackground(false,List.of(3));assertNotNull(three);assertEquals(3,three.sourceId());
        assertNotEquals(one.id(),three.id());assertNull(queue.claimBackground(false,List.of(2)));
        assertTrue(queue.renew(one));assertTrue(queue.renew(three));
        assertNull(queue.claimBackground(false,List.of(1)));assertNull(queue.claimBackground(true,List.of(3)));
    }

    @Test void backgroundFairnessSurvivesAnIdleOrStoppedSourceAndRetainsLeaseExclusion() {
        var sources=List.of(1,2,3);
        queue.commitPage(queue.cursor(1,1,"fair"),1,List.of("new","partial"),"fair",Duration.ZERO);
        jdbc.update("UPDATE crawl_discovery_job SET job_status='PARTIAL' WHERE source_book_id='partial'");
        queue.commitPage(queue.cursor(2,1,"off"),1,List.of("stopped"),"off",Duration.ZERO);
        var first=queue.claimBackground(true,sources);assertNotNull(first);assertEquals("new",first.sourceBookId());
        var second=queue.claimBackground(false,sources);assertNotNull(second);assertEquals("partial",second.sourceBookId());
        assertNotEquals(first.id(),second.id());assertNull(queue.claimBackground(true,sources));
        assertEquals("PENDING",jdbc.queryForObject("SELECT job_status FROM crawl_discovery_job WHERE source_id=2",String.class));
    }
    @Test void enqueueAndAttemptDoNotForgeAnActualCatalogCheckOrSuccessfulSave() {
        book(1,1,0,0);refresh.seedSerials(500);due();var job=refresh.claim();refresh.finish(job,true,null);
        assertNull(jdbc.queryForObject("SELECT last_checked_at FROM crawl_book_refresh",Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT last_saved_at FROM crawl_book_refresh",Timestamp.class));
        assertEquals(1,jdbc.queryForObject("SELECT failures FROM crawl_book_refresh",Integer.class));
    }
    @Test void fastCatalogChecksDoNotPostponeAnAlreadyWaitingBackfill() {
        book(1,1,0,0);refresh.seedSerials(500);due();
        jdbc.update("UPDATE crawl_discovery_job SET job_status='PARTIAL',next_attempt_at=?",Timestamp.from(Instant.EPOCH));
        var job=refresh.claim();var session=refresh.session(job);session.observe(100,"catalog",n->"catalog");
        session.catalogFinished(new CrawlCollectionStore.Audit(1,1,"b1","catalog",100));refresh.finish(job,false,null);
        assertEquals(Instant.EPOCH,jdbc.queryForObject("SELECT next_attempt_at FROM crawl_discovery_job",Timestamp.class).toInstant());
    }
    @Test void appendedTailRemainsUrgentAcrossSlicesWhileHistoricalHolesStayBackground() {
        book(1,1,0,0);book(2,1,0,0);refresh.seedSerials(500);due();
        jdbc.update("UPDATE crawl_book_refresh SET observed_count=10,catalog_hash='old' WHERE book_id=1");
        var job=refresh.claim();assertEquals(1L,job.bookId());
        var s=refresh.session(job);assertEquals(10,s.observe(15,"new",n->"old"));
        var audit=new CrawlCollectionStore.Audit(1,1,"b1","new",15);
        audit.mark(10,true);audit.mark(11,true);audit.mark(12,true);s.catalogFinished(audit);s.saved();
        refresh.finish(job,false,null);
        assertEquals(13,jdbc.queryForObject("SELECT pending_from FROM crawl_book_refresh WHERE book_id=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT priority_rank FROM crawl_book_refresh WHERE book_id=1",Integer.class));
        due();var next=refresh.claim();assertEquals(1L,next.bookId(),"Newly published tail precedes old unchecked books of the same source");
        assertEquals(13,refresh.session(next).observe(15,"new",n->"new"));refresh.finish(next,false,null);
    }
    @Test void aReshuffledCatalogCannotInventAnAppendedTail() {
        var s=new CrawlRefreshStore.Session(1,10,"old",8,0,0,0);
        assertEquals(12,s.observe(12,"reordered",n->"different-prefix"));assertNull(s.pendingFrom);
    }
    @Test void firstObservationOfLegacyDataDoesNotTreatAllHistoricalMissingChaptersAsNew() {
        var s=new CrawlRefreshStore.Session(1,0,null,null,0,0,2);
        assertEquals(1000,s.observe(1000,"catalog",n->"catalog"));assertNull(s.pendingFrom);
    }
    @Test void noChangeGraduallyReducesPollingButNeverPermanentlyExcludesSerials() {
        book(1,1,0,0);refresh.seedSerials(500);due();
        jdbc.update("UPDATE crawl_book_refresh SET observed_count=2,catalog_hash='same',unchanged_checks=3");
        var job=refresh.claim();var s=refresh.session(job);s.observe(2,"same",n->"same");
        var audit=new CrawlCollectionStore.Audit(1,1,"b1","same",2);audit.mark(0,true);audit.mark(1,true);s.catalogFinished(audit);
        refresh.finish(job,false,null);
        long delay=Duration.between(Instant.now(),jdbc.queryForObject("SELECT next_check_at FROM crawl_book_refresh",Timestamp.class).toInstant()).toMinutes();
        assertTrue(delay>=359 && delay<=360);assertEquals(1,jdbc.queryForObject("SELECT active_serial FROM crawl_book_refresh",Integer.class));
        assertEquals("COMPLETE",jdbc.queryForObject("SELECT job_status FROM crawl_discovery_job",String.class));
    }
    @Test void savingTheRecoveredTailClearsOldNoChangeBackoffAndKeepsSerialPollingTimely() {
        book(1,1,0,0);refresh.seedSerials(500);due();
        jdbc.update("UPDATE crawl_book_refresh SET observed_count=2,catalog_hash='same',pending_from=1,unchanged_checks=8,failures=7");
        var job=refresh.claimForSource(1);var session=refresh.session(job);session.observe(2,"same",n->"same");
        var audit=new CrawlCollectionStore.Audit(1,1,"b1","same",2);audit.mark(0,true);audit.mark(1,true);
        session.saved();session.catalogFinished(audit);refresh.finish(job,false,null);
        assertEquals(0,jdbc.queryForObject("SELECT unchanged_checks FROM crawl_book_refresh",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT failures FROM crawl_book_refresh",Integer.class));
        long delay=Duration.between(Instant.now(),jdbc.queryForObject("SELECT next_check_at FROM crawl_book_refresh",Timestamp.class).toInstant()).toSeconds();
        assertTrue(delay>=1798&&delay<=1800,"A saved new tail must not inherit six hours of failure-era no-change checks");
        assertEquals(1,jdbc.queryForObject("SELECT active_serial FROM crawl_book_refresh",Integer.class));
    }
    @Test void disablingASourceAfterItWasCachedStillPreventsClaims() {
        book(1,1,0,0);refresh.seedSerials(500);due();refresh.enabledSources();
        jdbc.update("UPDATE crawl_source SET source_status=0 WHERE id=1");assertNull(refresh.claim());
    }
    @Test void stalledPlaceholderTailYieldsToBackgroundAndAnOverdueBookGetsTheFastWorker() {
        book(1,1,0,0);book(2,1,0,0);refresh.seedSerials(500);due();
        jdbc.update("UPDATE crawl_book_refresh SET observed_count=2,catalog_hash='same',pending_from=1,unchanged_checks=8,priority_rank=0 WHERE book_id=1");
        var job=refresh.claimForSource(1);assertEquals(1L,job.bookId());
        var session=refresh.session(job);session.observe(2,"same",n->"same");
        var audit=new CrawlCollectionStore.Audit(1,1,"b1","same",2);audit.mark(0,true);audit.mark(1,false);audit.deferred=1;
        session.catalogFinished(audit);assertTrue(session.yieldedTail);assertFalse(session.complete);assertNull(session.pendingFrom);
        assertTrue(audit.ready.get(0));assertFalse(audit.ready.get(1));assertEquals(1,audit.deferred);
        refresh.finish(job,false,null);
        assertNull(jdbc.queryForObject("SELECT pending_from FROM crawl_book_refresh WHERE book_id=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT priority_rank FROM crawl_book_refresh WHERE book_id=1",Integer.class));
        long seconds=Duration.between(Instant.now(),jdbc.queryForObject("SELECT next_check_at FROM crawl_book_refresh WHERE book_id=1",Timestamp.class).toInstant()).toSeconds();
        assertTrue(seconds>=1798&&seconds<=1800);
        assertEquals("PARTIAL",jdbc.queryForObject("SELECT job_status FROM crawl_discovery_job WHERE book_id=1",String.class));
        var background=queue.claimBackground(true,List.of(1));assertNotNull(background);assertEquals(1L,background.bookId());
        var older=refresh.claimForSource(1);assertNotNull(older);assertEquals(2L,older.bookId());
    }
    @Test void anUnchangedTailGetsAnotherShortRetryBeforeBeingHandedToBackground() {
        var s=new CrawlRefreshStore.Session(1,2,"same",1,0,0,0);s.observe(2,"same",n->"same");
        var audit=new CrawlCollectionStore.Audit(1,1,"b1","same",2);audit.mark(0,true);audit.deferred=1;
        s.catalogFinished(audit);assertEquals(1,s.pendingFrom);assertFalse(s.yieldedTail);
        var next=new CrawlRefreshStore.Session(1,2,"same",s.pendingFrom,s.unchanged,0,0);next.observe(2,"same",n->"same");
        next.catalogFinished(audit);assertNull(next.pendingFrom);assertTrue(next.yieldedTail);assertFalse(next.complete);
    }
    @Test void verifyingAnAdvancingTailRetainsPriorityEvenWithoutNewDatabaseWrites() {
        var s=new CrawlRefreshStore.Session(1,5,"same",1,8,0,0);s.observe(5,"same",n->"same");
        var audit=new CrawlCollectionStore.Audit(1,1,"b1","same",5);for(int i=0;i<4;i++)audit.mark(i,true);
        s.catalogFinished(audit);assertEquals(4,s.pendingFrom);assertFalse(s.yieldedTail);assertFalse(s.complete);
    }
    @Test void savingNewContentKeepsTheTailUrgentUntilItsRemainingChaptersAreChecked() {
        var s=new CrawlRefreshStore.Session(1,5,"same",1,8,0,0);s.observe(5,"same",n->"same");s.saved();
        var audit=new CrawlCollectionStore.Audit(1,1,"b1","same",5);audit.mark(0,true);audit.mark(4,true);
        s.catalogFinished(audit);assertEquals(1,s.pendingFrom);assertFalse(s.yieldedTail);assertEquals(0,s.unchanged);
    }
    @Test void newGrowthRemainsUrgentDespiteAnOlderUnresolvedHole() {
        var s=new CrawlRefreshStore.Session(1,2,"old",1,8,0,0);s.observe(3,"new",n->"old");
        var audit=new CrawlCollectionStore.Audit(1,1,"b1","new",3);audit.mark(0,true);
        s.catalogFinished(audit);assertEquals(1,s.pendingFrom);assertFalse(s.yieldedTail);assertTrue(s.grew);
    }
    @Test void aLaterAppendIsCheckedWithoutRetryingThePreviouslyYieldedHistoricalHole() {
        var s=new CrawlRefreshStore.Session(1,2,"old",null,10,0,1);
        assertEquals(2,s.observe(3,"new",n->"old"));
        var audit=new CrawlCollectionStore.Audit(1,1,"b1","new",3);audit.mark(0,true);audit.mark(1,false);audit.mark(2,true);
        s.catalogFinished(audit);assertNull(s.pendingFrom);assertFalse(s.complete);assertFalse(s.yieldedTail);
        assertFalse(audit.ready.get(1));assertTrue(audit.ready.get(2));
    }
    @Test void anInterruptedCatalogKeepsItsFrontierAndUsesFailureBackoff() {
        book(1,1,0,0);refresh.seedSerials(500);due();
        jdbc.update("UPDATE crawl_book_refresh SET pending_from=1,observed_count=2,catalog_hash='old',priority_rank=0");
        var job=refresh.claimForSource(1);refresh.finish(job,false,null);
        assertEquals(1,jdbc.queryForObject("SELECT pending_from FROM crawl_book_refresh",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT failures FROM crawl_book_refresh",Integer.class));
        long seconds=Duration.between(Instant.now(),jdbc.queryForObject("SELECT next_check_at FROM crawl_book_refresh",Timestamp.class).toInstant()).toSeconds();
        assertTrue(seconds>=58&&seconds<=60);
    }
    @Test void sourceIdentityChangeResetsOnlyItsOwnRefreshMetadata() {
        book(1,1,0,0);refresh.seedSerials(500);
        jdbc.update("UPDATE crawl_book_refresh SET last_checked_at=CURRENT_TIMESTAMP,pending_from=10,observed_count=100");
        jdbc.update("UPDATE book SET crawl_source_id=3,crawl_book_id='changed' WHERE id=1");
        org.springframework.test.util.ReflectionTestUtils.setField(org.springframework.test.util.ReflectionTestUtils.getField(refresh,"serialSweep"),"nextRun",0L);
        refresh.seedSerials(500);
        assertEquals(3,jdbc.queryForObject("SELECT source_id FROM crawl_book_refresh",Integer.class));
        assertNull(jdbc.queryForObject("SELECT last_checked_at FROM crawl_book_refresh",Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT pending_from FROM crawl_book_refresh",Integer.class));
        assertEquals(Instant.EPOCH,jdbc.queryForObject("SELECT crawl_last_time FROM book",Timestamp.class).toInstant());
    }
    @Test void pressureReducesScanningWithoutDeletingOrAdvancingItsCursor() {
        book(1,1,0,0);refresh.seedSerials(500);due();
        var cursor=queue.cursor(1,1,"kept");
        assertEquals(Duration.ofSeconds(30),refresh.scanPageDelay(1));assertEquals(Duration.ofHours(6),refresh.scanCycleDelay());
        assertEquals(cursor,queue.cursor(1,1,"kept"));
        assertEquals(1,refresh.stats(1).refreshWaiting());assertNull(refresh.stats(1).lastCheckedAt());
    }
    @Test void completedSerialRetainsItsActualCheckAndSaveTimesWithoutRemainingInTheWaitingCount() {
        book(1,1,0,0);refresh.seedSerials(500);due();var job=refresh.claim();
        var session=refresh.session(job);session.observe(1,"done",n->"done");session.saved();
        var audit=new CrawlCollectionStore.Audit(1,1,"b1","done",1);audit.mark(0,true);audit.sourceCompleted=true;
        session.catalogFinished(audit);refresh.finish(job,false,null);
        var stats=refresh.stats(1);assertEquals(0,stats.refreshWaiting());assertNotNull(stats.lastCheckedAt());assertNotNull(stats.lastSavedAt());
    }
}
