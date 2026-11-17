package com.java2nb.novel.seo;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: only the separately provisioned, empty QA schema, never the novel data schema. */
@EnabledIfEnvironmentVariable(named="SEO_MYSQL_TEST_URL",matches=".+")
class SeoMySqlIntegrationTest {
    static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis()*1000);
    DriverManagerDataSource source;JdbcTemplate db;SeoRepository repository;TransactionTemplate transaction;
    @BeforeEach void connect(){
        String url=System.getenv("SEO_MYSQL_TEST_URL");
        assertTrue(url.matches("jdbc:mysql://[^/]+/novel_seo_integration_[0-9]+(?:\\?.*)?"),"Integration tests require their dedicated schema");
        source=new DriverManagerDataSource(url,System.getenv("MYSQL_USERNAME"),System.getenv("MYSQL_PASSWORD"));db=new JdbcTemplate(source);repository=new SeoRepository(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("novel_seo_integration_"));
    }
    <T>T tx(Supplier<T> work){return transaction.execute(status->work.get());}
    long book(){long id=IDS.incrementAndGet();db.update("INSERT INTO book(id,book_name,author_name,pic_url,score,book_desc,status,word_count,last_index_id,create_time,update_time) VALUES(?,?,?,'/images/test-cover.png',7.0,'原创测试简介',1,20,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,"SEO测试"+id,"原创测试作者",id+1);tx(()->{repository.markBookDirty(id);return null;});return id;}
    long revision(long id){return ((Number)repository.state(id).get("revision")).longValue();}
    SeoSiteSettings enable(){return tx(()->{var before=repository.settings();return repository.updateSettings(new SeoSiteSettings(before.epoch(),true,true,true,"小说精品屋","原创小说测试","小说,原创","",""),1);});}
    @Test void settingsAndOverrideCompareAndSetRejectStaleEditorsAndAuditWrites(){
        long id=book();var before=repository.settings();
        tx(()->repository.updateSettings(new SeoSiteSettings(before.epoch(),true,true,true,"原创标题"+id,"原创小说测试","小说,原创","",""),1));
        assertThrows(ConcurrentModificationException.class,()->tx(()->repository.updateSettings(before,1)));
        long revision=revision(id);tx(()->repository.saveBookOverride(new SeoBookOverride(id,true,"原创标题","原创描述","原创"),revision,1));
        assertThrows(ConcurrentModificationException.class,()->tx(()->repository.saveBookOverride(new SeoBookOverride(id,false,"覆盖","",""),revision,1)));
        assertEquals("原创标题",repository.bookOverride(id).title());assertTrue(db.queryForObject("SELECT COUNT(*) FROM seo_audit_log WHERE book_id=?",Long.class,id)>0);
    }
    @Test void businessAndSeoMutationRollbackTogether(){
        long id=book();enable();long before=revision(id);
        assertThrows(IllegalStateException.class,()->tx(()->{repository.lockMutation();db.update("UPDATE book SET status=0 WHERE id=?",id);repository.markBookDirty(id);throw new IllegalStateException("intentional rollback");}));
        assertEquals(1,db.queryForObject("SELECT status FROM book WHERE id=?",Integer.class,id));assertEquals(before,revision(id));
    }
    @Test void expiredLeaseIsRecoveredAndOldBuilderCannotPublishOrFailNewOwner(){
        long id=book();enable();var first=tx(()->repository.claimBook(id).orElseThrow());
        db.update("UPDATE seo_book_state SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE book_id=?",id);
        // A fresh repository models a worker process restart and a new connection pool.
        var restarted=new SeoRepository(source);var next=tx(()->restarted.claimBook(id).orElseThrow());assertNotEquals(first.leaseToken(),next.leaseToken());
        assertFalse(tx(()->repository.complete(first,"old.xml.gz","https://xs001.xyz",1,false)));
        tx(()->{repository.failed(first,"old builder");return null;});assertEquals("running",repository.state(id).get("status"));
        String file="chapters-"+id+"-r"+next.revision()+"-e"+next.epoch()+"-p1-"+next.leaseToken()+".xml.gz";
        assertTrue(tx(()->restarted.complete(next,file,"https://xs001.xyz",1,false)));
        tx(()->repository.enqueueBook(id));var retry=tx(()->repository.claimBook(id).orElseThrow());tx(()->{repository.failed(retry,"retry failed");return null;});
        assertTrue(repository.filePublished(file,next.epoch(),"https://xs001.xyz"));
        tx(()->{repository.lockMutation();db.update("UPDATE book SET status=0 WHERE id=?",id);repository.markBookDirty(id);return null;});assertFalse(repository.filePublished(file,next.epoch(),"https://xs001.xyz"));
    }
    @Test void globalEpochAndNoindexImmediatelyRevokePublishedSnapshots(){
        long id=book();enable();var ticket=tx(()->repository.claimBook(id).orElseThrow());String file="chapters-"+id+"-r"+ticket.revision()+"-e"+ticket.epoch()+"-p1-"+ticket.leaseToken()+".xml.gz";
        assertTrue(tx(()->repository.complete(ticket,file,"https://xs001.xyz",1,false)));assertTrue(repository.filePublished(file,ticket.epoch(),"https://xs001.xyz"));
        tx(()->repository.saveBookOverride(new SeoBookOverride(id,false,"","",""),revision(id),1));assertFalse(repository.filePublished(file,ticket.epoch(),"https://xs001.xyz"));
        var old=tx(()->repository.claimBook(id).orElseThrow());var site=repository.settings();tx(()->repository.updateSettings(new SeoSiteSettings(site.epoch(),false,false,false,"","","","",""),1));
        assertFalse(tx(()->repository.complete(old,file,"https://xs001.xyz",1,false)));assertTrue(repository.publishedStates(repository.settings().epoch(),"https://xs001.xyz").isEmpty());
    }
    @Test void coreManifestRejectsOldEpochAndVisibilityButRetainsUsablePreviousVersion(){
        long id=book();enable();var first=tx(()->repository.coreTicket());
        assertTrue(tx(()->repository.completeCore(first,"https://xs001.xyz","first","index-first.xml","core-first.xml.gz","first-hash",1)));
        tx(()->repository.enqueueBook(id));var previous=repository.coreState();
        assertEquals(first.visibilityRevision(),((Number)previous.get("visibilityRevision")).longValue());
        assertEquals(first.visibilityRevision(),((Number)previous.get("builtVisibilityRevision")).longValue());
        assertEquals("index-first.xml",previous.get("indexFile"));
        var rebuilding=tx(()->repository.coreTicket());
        tx(()->{repository.lockMutation();db.update("UPDATE book SET status=0 WHERE id=?",id);repository.markBookDirty(id);return null;});
        assertFalse(tx(()->repository.completeCore(rebuilding,"https://xs001.xyz","stale","index-stale.xml","core-stale.xml.gz","stale-hash",1)));
        assertNotEquals(repository.coreState().get("visibilityRevision"),repository.coreState().get("builtVisibilityRevision"));
        var oldEpoch=tx(()->repository.coreTicket());var site=repository.settings();
        tx(()->repository.updateSettings(new SeoSiteSettings(site.epoch(),false,false,false,"","","","",""),1));
        assertFalse(tx(()->repository.completeCore(oldEpoch,"https://xs001.xyz","epoch","index-epoch.xml","core-epoch.xml.gz","epoch-hash",1)));
    }
    @Test void globalMutationLockSerializesIndependentConnections()throws Exception{
        long id=book();enable();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            Future<?> first=pool.submit(()->tx(()->{repository.lockMutation();entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<?> second=pool.submit(()->{secondStarted.countDown();return tx(()->repository.enqueueBook(id));});assertTrue(secondStarted.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->second.get(150,TimeUnit.MILLISECONDS));release.countDown();first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);assertEquals("pending",repository.state(id).get("status"));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void readyRowsRequireRealNonemptyBodyAndAreBounded(){
        long chapter=IDS.incrementAndGet();db.update("INSERT INTO book_content(index_id,content) VALUES(?,?)",chapter,"测试正文");db.update("INSERT INTO book_content(index_id,content) VALUES(?,?)",chapter+1,"");db.update("INSERT INTO book_content(index_id,content) VALUES(?,?)",chapter+2,"  ");
        db.update("INSERT INTO book_content(index_id,content) VALUES(?,?)",chapter+3,"\n\t\r ");db.update("INSERT INTO book_content(index_id,content) VALUES(?,?)",chapter+4,"<p>&nbsp;</p><br/>\t");db.update("INSERT INTO book_content(index_id,content) VALUES(?,?)",chapter+5,"<script>not visible</script><style>p{}</style><!--comment-->");db.update("INSERT INTO book_content(index_id,content) VALUES(?,?)",chapter+6,"<p>正文</p>");
        assertEquals(Set.of(chapter,chapter+6),repository.readyDatabaseIds(List.of(chapter,chapter+1,chapter+2,chapter+3,chapter+4,chapter+5,chapter+6)));assertThrows(IllegalArgumentException.class,()->repository.readyDatabaseIds(Collections.nCopies(501,chapter)));
    }
    @Test void bookDurationDiagnosticsRejectExpiredLeaseAndKeepBusinessLastmod(){
        long id=book();enable();Object modified=repository.state(id).get("modifiedAt");var first=tx(()->repository.claimBook(id).orElseThrow());
        assertNotNull(repository.state(id).get("startedAt"));assertNull(repository.state(id).get("durationMs"));
        db.update("UPDATE seo_book_state SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE book_id=?",id);
        assertFalse(tx(()->repository.failed(first,"expired worker",900)));
        assertFalse(tx(()->repository.complete(first,"old","https://xs001.xyz",1,false,1,900)));
        var next=tx(()->repository.claimBook(id).orElseThrow());assertTrue(tx(()->repository.failed(next,"body storage unavailable",123)));
        var failure=repository.state(id);assertEquals("failed",failure.get("status"));assertEquals(123L,((Number)failure.get("durationMs")).longValue());assertNotNull(failure.get("completedAt"));assertNotNull(failure.get("nextAttemptAt"));assertEquals(modified,failure.get("modifiedAt"));
        assertFalse(tx(()->repository.failed(first,"stale worker",999)));assertEquals(123L,((Number)repository.state(id).get("durationMs")).longValue());
    }
    @Test void independentCoreClaimsHaveOneOwnerAndOldLeaseCannotOverwriteRestartedWorker()throws Exception{
        book();enable();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<SeoRepository.CoreTicket> claim=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return tx(()->new SeoRepository(source).coreTicket());};
            Future<SeoRepository.CoreTicket> one=pool.submit(claim),two=pool.submit(claim);start.countDown();var a=one.get(5,TimeUnit.SECONDS);var b=two.get(5,TimeUnit.SECONDS);
            assertTrue((a==null)!=(b==null));var first=a==null?b:a;
            db.update("UPDATE seo_sitemap_control SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=1");
            assertFalse(tx(()->repository.failedCore(first,"expired failed",900)));
            assertFalse(tx(()->repository.completeCore(first,"https://xs001.xyz","expired","index-expired.xml","core-expired.xml.gz","expired",1,900)));
            var next=tx(()->repository.coreTicket());assertNotNull(next);assertNotEquals(first.leaseToken(),next.leaseToken());
            assertFalse(tx(()->repository.completeCore(first,"https://xs001.xyz","stale","index-stale.xml","core-stale.xml.gz","stale",1,900)));
            assertFalse(tx(()->repository.failedCore(first,"stale failed",900)));assertEquals("running",repository.coreState().get("status"));
            assertTrue(tx(()->repository.failedCore(next,"disk unavailable",321)));var failed=repository.coreState();assertEquals("failed",failed.get("status"));assertEquals(321L,((Number)failed.get("durationMs")).longValue());assertNotNull(failed.get("startedAt"));assertNotNull(failed.get("completedAt"));assertEquals("disk unavailable",failed.get("lastError"));
            assertNull(tx(()->repository.coreTicket()),"Failed core tasks back off before another claim");
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void coreNoopChangesAttemptDiagnosticsWithoutChangingTheManifestClockOrEtag(){
        long id=book();enable();var first=tx(()->repository.coreTicket());assertNotNull(first);
        assertTrue(tx(()->repository.completeCore(first,"https://xs001.xyz","one","index-one.xml","core-same.xml.gz","stable-hash",1,12)));var before=repository.coreState();
        tx(()->repository.enqueueBook(id));var next=tx(()->repository.coreTicket());assertNotNull(next);
        assertTrue(tx(()->repository.completeCore(next,"https://xs001.xyz","two","index-two.xml","core-same.xml.gz","stable-hash",1,34)));var after=repository.coreState();
        assertEquals(before.get("modifiedAt"),after.get("modifiedAt"));assertEquals(before.get("indexHash"),after.get("indexHash"));assertEquals(34L,((Number)after.get("durationMs")).longValue());assertEquals("complete",after.get("status"));
    }
    @Test void nightlyReconciliationEstablishesOnlyABaselineAndQueuesActualStateDifferences(){
        long id=book();enable();db.update("UPDATE seo_book_state SET observed_hash=NULL WHERE book_id=?",id);var before=repository.state(id);var coreBefore=repository.coreState();
        assertFalse(tx(()->repository.reconcileBook(id)));assertFalse(tx(()->repository.reconcileBook(id)));
        db.update("UPDATE book SET update_time=CURRENT_TIMESTAMP,last_index_update_time=CURRENT_TIMESTAMP,visit_count=visit_count+1 WHERE id=?",id);
        assertFalse(tx(()->repository.reconcileBook(id)));assertEquals(before.get("revision"),repository.state(id).get("revision"));assertEquals(coreBefore.get("revision"),repository.coreState().get("revision"));
        db.update("UPDATE book SET word_count=word_count+1 WHERE id=?",id);assertTrue(tx(()->repository.reconcileBook(id)));assertFalse(tx(()->repository.reconcileBook(id)));
        assertEquals(((Number)before.get("revision")).longValue()+1,revision(id));assertEquals(before.get("modifiedAt"),repository.state(id).get("modifiedAt"));
        long visibility=((Number)repository.coreState().get("visibilityRevision")).longValue();db.update("UPDATE book SET status=0 WHERE id=?",id);assertTrue(tx(()->repository.reconcileBook(id)));assertFalse(tx(()->repository.reconcileBook(id)));
        assertEquals(visibility+1,((Number)repository.coreState().get("visibilityRevision")).longValue());
        long orphan=IDS.incrementAndGet();db.update("INSERT INTO seo_book_state(book_id,revision,job_id,status) VALUES(?,4,?,'complete')",orphan,UUID.randomUUID().toString());db.update("INSERT INTO seo_book_visibility(book_id,published) VALUES(?,1)",orphan);
        assertTrue(tx(()->repository.reconcileBook(orphan)));assertFalse(tx(()->repository.reconcileBook(orphan)));assertEquals(5,revision(orphan));
        assertTrue(repository.reconciliationIds(id-1,1).size()<=1);
    }
}
