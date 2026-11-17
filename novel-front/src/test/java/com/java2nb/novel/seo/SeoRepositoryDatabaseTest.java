package com.java2nb.novel.seo;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** SQL behavior checks; production MySQL locking is separately verified against an isolated copy. */
class SeoRepositoryDatabaseTest {
    JdbcTemplate db;SeoRepository repository;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path contentDirectory;
    @BeforeEach void create() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:seo_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(source);repository=new SeoRepository(source);
        db.execute("CREATE TABLE seo_site_config(id INT PRIMARY KEY,epoch BIGINT,index_enabled INT,chapters_index_enabled INT,chapter_sitemap_enabled INT,default_title VARCHAR,default_description VARCHAR,keywords VARCHAR,baidu_verification VARCHAR,google_verification VARCHAR)");
        db.execute("INSERT INTO seo_site_config VALUES(1,3,1,1,1,'title','','','','')");
        db.execute("CREATE TABLE book(id BIGINT PRIMARY KEY,status INT,word_count INT,last_index_id BIGINT,book_name VARCHAR,author_name VARCHAR,book_desc VARCHAR,pic_url VARCHAR,cat_id INT,cat_name VARCHAR,work_direction INT,book_status INT,is_vip INT,last_index_update_time TIMESTAMP,create_time TIMESTAMP,update_time TIMESTAMP,visit_count BIGINT)");db.execute("INSERT INTO book(id,status,word_count,last_index_id) VALUES(7,1,100,8)");
        db.execute("CREATE TABLE seo_book_override(book_id BIGINT PRIMARY KEY,index_enabled INT,title VARCHAR,description VARCHAR,keywords VARCHAR)");
        db.execute("CREATE TABLE seo_book_state(book_id BIGINT PRIMARY KEY,revision BIGINT DEFAULT 1,built_revision BIGINT DEFAULT 0,built_epoch BIGINT DEFAULT 0,modified_at TIMESTAMP,job_id VARCHAR,status VARCHAR DEFAULT 'pending',attempts INT DEFAULT 0,built_files VARCHAR,built_origin VARCHAR,url_count BIGINT DEFAULT 0,chapter_count BIGINT DEFAULT 0,last_error VARCHAR,started_at TIMESTAMP,completed_at TIMESTAMP,duration_ms BIGINT,next_attempt_at TIMESTAMP,lease_until TIMESTAMP,lease_token VARCHAR,observed_hash VARCHAR)");
        db.execute("CREATE TABLE book_content(index_id BIGINT PRIMARY KEY,content CLOB)");
        db.execute("CREATE TABLE book_index(id BIGINT PRIMARY KEY,book_id BIGINT,index_num INT,index_name VARCHAR,word_count INT,is_vip INT,storage_type VARCHAR,create_time TIMESTAMP,update_time TIMESTAMP)");
        db.execute("CREATE TABLE seo_book_visibility(book_id BIGINT PRIMARY KEY,published BOOLEAN)");
        db.execute("CREATE TABLE seo_sitemap_control(id INT PRIMARY KEY,revision BIGINT DEFAULT 1,visibility_revision BIGINT DEFAULT 1,built_revision BIGINT DEFAULT 0,built_visibility_revision BIGINT DEFAULT 0,built_epoch BIGINT DEFAULT 0,built_origin VARCHAR,generation VARCHAR,index_file VARCHAR,core_files VARCHAR,modified_at TIMESTAMP,index_hash VARCHAR,url_count BIGINT DEFAULT 0,status VARCHAR DEFAULT 'idle',started_at TIMESTAMP,completed_at TIMESTAMP,duration_ms BIGINT,last_error VARCHAR,next_attempt_at TIMESTAMP,lease_until TIMESTAMP,lease_token VARCHAR)");
        db.execute("INSERT INTO seo_sitemap_control(id) VALUES(1)");
        db.execute("CREATE TABLE website_info(id INT PRIMARY KEY,name VARCHAR)");db.execute("INSERT INTO website_info VALUES(1,'测试站点')");
    }
    @Test void retentionReadsAllManifestsAcrossVisibilityStatusAndEpochWithBoundedKeysets(){
        db.execute("UPDATE book SET status=0 WHERE id=7");
        db.execute("INSERT INTO seo_book_state(book_id,status,built_epoch,built_files) VALUES(7,'pending',1,'hidden-previous.xml.gz'),(8,'failed',3,'failed-previous.xml.gz')");
        db.execute("INSERT INTO seo_book_state(book_id,status,built_files,lease_token,lease_until) VALUES(9,'running','running-previous.xml.gz','active',TIMESTAMP '2099-01-01 00:00:00'),(10,'running','expired-previous.xml.gz','expired',TIMESTAMP '2000-01-01 00:00:00')");
        assertEquals(List.of(new SeoRepository.SnapshotFiles(7,"hidden-previous.xml.gz",null),new SeoRepository.SnapshotFiles(8,"failed-previous.xml.gz",null)),repository.snapshotFilePage(0,2));
        assertEquals(List.of(new SeoRepository.SnapshotFiles(9,"running-previous.xml.gz","active"),new SeoRepository.SnapshotFiles(10,"expired-previous.xml.gz",null)),repository.snapshotFilePage(8,2));assertTrue(repository.snapshotFilePage(10,2).isEmpty());
    }
    private SeoRepository transactionalRepository(){
        var factory=new org.springframework.aop.framework.ProxyFactory(repository);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()),
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (SeoRepository)factory.getProxy();
    }
    @Test void bootstrapPagesCommitIndependentlyAndNeverOverwriteExistingSnapshots(){
        for(long id=8;id<=128;id++)db.update("INSERT INTO book(id,status,word_count,last_index_id) VALUES(?,1,100,?)",id,id+1000);
        db.execute("INSERT INTO seo_book_state(book_id,revision,status,built_files) VALUES(7,9,'complete','retained.xml.gz')");
        db.execute("INSERT INTO seo_book_visibility VALUES(7,FALSE)");
        var proxy=transactionalRepository();var outer=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
        outer.execute(status->{var first=proxy.bootstrapMissingStates(0,5000);assertEquals(50,first.scanned());assertEquals(56,first.nextCursor());assertFalse(first.complete());status.setRollbackOnly();return null;});
        assertEquals(50,db.queryForObject("SELECT COUNT(*) FROM seo_book_state",Integer.class));
        assertEquals("retained.xml.gz",repository.state(7).get("builtFiles"));assertEquals(9L,((Number)repository.state(7).get("revision")).longValue());
        assertFalse(db.queryForObject("SELECT published FROM seo_book_visibility WHERE book_id=7",Boolean.class));
        var second=proxy.bootstrapMissingStates(56,50);assertEquals(106,second.nextCursor());assertFalse(second.complete());
        var last=proxy.bootstrapMissingStates(second.nextCursor(),50);assertEquals(22,last.scanned());assertTrue(last.complete());
        assertEquals(122,db.queryForObject("SELECT COUNT(*) FROM seo_book_state",Integer.class));
        assertEquals(0,proxy.bootstrapMissingStates(0,50).added());
        assertEquals(0,proxy.bootstrapMissingStates(128,50).scanned());
    }
    @Test void failedBootstrapPageRollsBackBothTablesAndCanRetrySameCursor(){
        var proxy=transactionalRepository();db.execute("DROP TABLE seo_sitemap_control");
        assertThrows(org.springframework.dao.DataAccessException.class,()->proxy.bootstrapMissingStates(0,50));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM seo_book_state",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM seo_book_visibility",Integer.class));
        db.execute("CREATE TABLE seo_sitemap_control(id INT PRIMARY KEY,revision BIGINT,visibility_revision BIGINT,status VARCHAR,next_attempt_at TIMESTAMP,lease_until TIMESTAMP,lease_token VARCHAR)");
        db.execute("INSERT INTO seo_sitemap_control(id,revision,visibility_revision) VALUES(1,1,1)");
        assertEquals(1,proxy.bootstrapMissingStates(0,50).added());
        assertTrue(db.queryForObject("SELECT published FROM seo_book_visibility WHERE book_id=7",Boolean.class));
    }
    @Test void whitespaceNullAndAbsentContentAreExcludedWithoutReturningText() {
        db.update("INSERT INTO book_content VALUES(?,?)",1,"正文");db.update("INSERT INTO book_content VALUES(?,?)",2,"");db.update("INSERT INTO book_content VALUES(?,?)",3,"   ");db.update("INSERT INTO book_content VALUES(?,?)",4,(Object)null);
        db.update("INSERT INTO book_content VALUES(?,?)",6,"\n\t\r ");db.update("INSERT INTO book_content VALUES(?,?)",7,"<p>&nbsp;</p><br/>\t");db.update("INSERT INTO book_content VALUES(?,?)",8,"<script>not visible</script><style>p{}</style><!--comment-->");db.update("INSERT INTO book_content VALUES(?,?)",9,"<p>正文</p>");
        assertEquals(Set.of(1L,9L),repository.readyDatabaseIds(List.of(1L,2L,3L,4L,5L,6L,7L,8L,9L)));
        assertThrows(IllegalArgumentException.class,()->repository.readyDatabaseIds(Collections.nCopies(501,1L)));
    }

    @Test void quietPeriodDefersRecentlyChangedBooksButManualClaimsRemainImmediate(){
        db.execute("INSERT INTO seo_book_state(book_id,revision,status,modified_at,started_at) VALUES(7,4,'pending',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        assertTrue(repository.claim(60).isEmpty());assertEquals("pending",repository.state(7).get("status"));
        assertEquals(7,repository.claimBook(7).orElseThrow().bookId());
    }

    @Test void continuousImportsCannotStarveAnOldAttemptAndFirstBuildsAreImmediate(){
        db.execute("INSERT INTO seo_book_state(book_id,revision,status,modified_at,started_at) VALUES(7,4,'pending',CURRENT_TIMESTAMP,TIMESTAMPADD(SECOND,-301,CURRENT_TIMESTAMP))");
        assertEquals(7,repository.claim(60).orElseThrow().bookId());
        db.execute("UPDATE seo_book_state SET status='pending',started_at=NULL,modified_at=CURRENT_TIMESTAMP WHERE book_id=7");
        assertEquals(7,repository.claim(60).orElseThrow().bookId());
    }

    @Test void quietBooksAndEligibleFailuresAreNotDelayedAndRetryDeadlinesAreRespected(){
        db.execute("INSERT INTO seo_book_state(book_id,revision,status,modified_at,started_at) VALUES(7,4,'pending',TIMESTAMPADD(SECOND,-61,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP)");
        assertEquals(7,repository.claim(60).orElseThrow().bookId());
        db.execute("UPDATE seo_book_state SET status='failed',modified_at=CURRENT_TIMESTAMP,next_attempt_at=TIMESTAMPADD(MINUTE,1,CURRENT_TIMESTAMP) WHERE book_id=7");
        assertTrue(repository.claim(60).isEmpty());
        db.execute("UPDATE seo_book_state SET next_attempt_at=NULL WHERE book_id=7");assertEquals(7,repository.claim(60).orElseThrow().bookId());
    }
    @Test void staleSiteEpochAndBookRevisionRejectEdits() {
        assertThrows(ConcurrentModificationException.class,()->repository.updateSettings(new SeoSiteSettings(2,true,true,true,"overwrite","","","",""),5));
        db.execute("INSERT INTO seo_book_state(book_id,revision,built_revision,built_epoch,job_id,status,attempts,url_count,chapter_count) VALUES(7,4,0,0,'job','pending',0,0,0)");
        assertThrows(ConcurrentModificationException.class,()->repository.saveBookOverride(new SeoBookOverride(7,false,"x","",""),3,5));
        assertEquals("title",repository.settings().defaultTitle());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM seo_book_override",Integer.class));
    }
    @Test void successfulSnapshotSurvivesRebuildFailureButVisibilityAndEpochRevokeIt() {
        String file="chapters-7-r1-e3-p1-11111111-1111-1111-1111-111111111111.xml.gz";
        db.update("INSERT INTO seo_book_state(book_id,revision,built_revision,built_epoch,job_id,status,attempts,built_files,built_origin,url_count,chapter_count) VALUES(7,2,1,3,'job','failed',1,?,'https://xs001.xyz',2,2)",file);
        assertTrue(repository.filePublished(file,3,"https://xs001.xyz"));assertEquals(1,repository.publishedStates(3,"https://xs001.xyz").size());
        assertFalse(repository.filePublished(file,4,"https://xs001.xyz"));assertFalse(repository.filePublished(file,3,"https://other.invalid"));
        db.execute("UPDATE book SET word_count=0 WHERE id=7");assertFalse(repository.filePublished(file,3,"https://xs001.xyz"));db.execute("UPDATE book SET word_count=100 WHERE id=7");
        db.execute("UPDATE book SET status=0 WHERE id=7");assertFalse(repository.filePublished(file,3,"https://xs001.xyz"));
        db.execute("UPDATE book SET status=1 WHERE id=7");db.execute("INSERT INTO seo_book_override VALUES(7,0,'','','')");assertFalse(repository.filePublished(file,3,"https://xs001.xyz"));
        assertFalse(repository.filePublished("../secret",3,"https://xs001.xyz"));
    }
    @Test void reconciliationBaselineAndRecrawlOnlyUpdatesDoNotChangeRevisionOrContentClock(){
        db.execute("INSERT INTO seo_book_state(book_id,revision,status,modified_at) VALUES(7,9,'complete',TIMESTAMP '2024-01-01 00:00:00')");
        db.execute("INSERT INTO seo_book_visibility VALUES(7,TRUE)");
        var before=repository.state(7);var core=repository.coreState();
        assertFalse(repository.reconcileBook(7));
        db.execute("UPDATE book SET update_time=CURRENT_TIMESTAMP,last_index_update_time=CURRENT_TIMESTAMP,visit_count=100 WHERE id=7");
        assertFalse(repository.reconcileBook(7));
        assertEquals(before.get("revision"),repository.state(7).get("revision"));
        assertEquals(before.get("modifiedAt"),repository.state(7).get("modifiedAt"));
        assertEquals(core.get("revision"),repository.coreState().get("revision"));
        assertNotNull(db.queryForObject("SELECT observed_hash FROM seo_book_state WHERE book_id=7",String.class));
    }
    @Test void unchangedReconciliationDoesNotIssueAnyWriteAndRepairsMissingVisibility(){
        db.execute("INSERT INTO seo_book_state(book_id,revision,status) VALUES(7,9,'complete')");
        db.execute("INSERT INTO seo_book_visibility VALUES(7,TRUE)");repository.reconcileBook(7);
        var observed=org.mockito.Mockito.spy(db);
        org.springframework.test.util.ReflectionTestUtils.setField(repository,"jdbc",observed);
        assertFalse(repository.reconcileBook(7));
        assertTrue(org.mockito.Mockito.mockingDetails(observed).getInvocations().stream().noneMatch(i->i.getMethod().getName().equals("update")),"Unchanged reconciliation must be read-only");
        db.execute("DELETE FROM seo_book_visibility WHERE book_id=7");
        assertFalse(repository.reconcileBook(7));assertEquals(Boolean.TRUE,db.queryForObject("SELECT published FROM seo_book_visibility WHERE book_id=7",Boolean.class));
        assertEquals(9L,db.queryForObject("SELECT revision FROM seo_book_state WHERE book_id=7",Long.class));
    }
    @Test void reconciliationQueuesOnlyRealSummaryChangesAndRetainsTheModificationClock(){
        db.execute("INSERT INTO seo_book_state(book_id,revision,status,modified_at) VALUES(7,9,'complete',TIMESTAMP '2024-01-01 00:00:00')");
        db.execute("INSERT INTO seo_book_visibility VALUES(7,TRUE)");repository.reconcileBook(7);
        var before=repository.state(7);db.execute("UPDATE book SET word_count=101 WHERE id=7");
        assertTrue(repository.reconcileBook(7));assertFalse(repository.reconcileBook(7));
        assertEquals(10L,repository.state(7).get("revision"));assertEquals("pending",repository.state(7).get("status"));
        assertEquals(before.get("modifiedAt"),repository.state(7).get("modifiedAt"));
        long visibility=((Number)repository.coreState().get("visibilityRevision")).longValue();
        db.execute("UPDATE book SET status=0 WHERE id=7");assertTrue(repository.reconcileBook(7));
        assertEquals(visibility+1,((Number)repository.coreState().get("visibilityRevision")).longValue());
    }
    @Test void reconciliationFindsMissingStatesAndOrphanSnapshotsWithBoundedCursors(){
        db.execute("INSERT INTO seo_book_state(book_id,revision,status) VALUES(19,4,'complete')");
        db.execute("INSERT INTO seo_book_visibility VALUES(19,TRUE)");
        assertEquals(List.of(7L),repository.reconciliationIds(0,1));assertEquals(List.of(19L),repository.reconciliationIds(7,1));
        assertTrue(repository.reconcileBook(7));assertFalse(repository.reconcileBook(7));
        assertTrue(repository.reconcileBook(19));assertFalse(repository.reconcileBook(19));
        assertEquals(5L,repository.state(19).get("revision"));assertEquals(false,db.queryForObject("SELECT published FROM seo_book_visibility WHERE book_id=19",Boolean.class));
    }
    @Test void reconciliationDetectsLastChapterDirectoryReadinessChangesWithoutReadingBodies(){
        db.execute("INSERT INTO book_index(id,book_id,index_num,index_name,word_count,is_vip,storage_type) VALUES(8,7,1,'末章',10,0,'db')");
        repository.reconcileBook(7);assertFalse(repository.reconcileBook(7));
        db.execute("UPDATE book_index SET word_count=0 WHERE id=8");assertTrue(repository.reconcileBook(7));assertFalse(repository.reconcileBook(7));
    }
    @Test void completionDiagnosticsRequireTheCurrentRevisionAndUnexpiredLease(){
        db.execute("INSERT INTO seo_book_state(book_id,revision,status,lease_token,lease_until,started_at,modified_at) VALUES(7,4,'running','current',TIMESTAMP '2099-01-01 00:00:00',CURRENT_TIMESTAMP,TIMESTAMP '2024-01-01 00:00:00')");
        var old=new SeoRepository.Ticket(7,4,3,null,"job","old");var current=new SeoRepository.Ticket(7,4,3,null,"job","current");
        assertFalse(repository.complete(old,"old","https://xs001.xyz",1,false,1,900));
        assertTrue(repository.complete(current,"new","https://xs001.xyz",1,false,1,123));
        var result=repository.state(7);assertEquals(123L,result.get("durationMs"));assertNotNull(result.get("startedAt"));assertNotNull(result.get("completedAt"));
        assertEquals(java.sql.Timestamp.valueOf("2024-01-01 00:00:00"),result.get("modifiedAt"));
        db.execute("UPDATE seo_book_state SET status='running',lease_token='expired',lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE book_id=7");
        assertFalse(repository.complete(new SeoRepository.Ticket(7,4,3,null,"job","expired"),"expired","https://xs001.xyz",1,false,1,999));assertEquals(123L,repository.state(7).get("durationMs"));
    }
    @Test void coreCompletionDiagnosticsDoNotManufactureLastmodForIdenticalXml(){
        db.execute("UPDATE seo_sitemap_control SET revision=4,visibility_revision=2,status='running',lease_token='first',lease_until=TIMESTAMP '2099-01-01 00:00:00',started_at=CURRENT_TIMESTAMP WHERE id=1");
        var first=new SeoRepository.CoreTicket(3,4,2,"first");
        assertTrue(repository.completeCore(first,"https://xs001.xyz","one","index-one.xml","core-one.xml.gz","same",1,80));
        var before=repository.coreState();
        db.execute("UPDATE seo_sitemap_control SET status='running',lease_token='next',lease_until=TIMESTAMP '2099-01-01 00:00:00',started_at=CURRENT_TIMESTAMP WHERE id=1");
        assertFalse(repository.completeCore(first,"https://xs001.xyz","stale","old","old","changed",2,99));
        assertTrue(repository.completeCore(new SeoRepository.CoreTicket(3,4,2,"next"),"https://xs001.xyz","two","index-two.xml","core-one.xml.gz","same",1,95));
        var after=repository.coreState();assertEquals(before.get("modifiedAt"),after.get("modifiedAt"));assertEquals(before.get("indexHash"),after.get("indexHash"));assertEquals(95L,after.get("durationMs"));
    }
    @Test void directoryAndManagementPreviewWorkWithoutBodyOrCacheTables()throws Exception{
        db.execute("DROP TABLE book_content");
        db.execute("INSERT INTO book_index(id,book_id,index_num,index_name,word_count,is_vip,storage_type) VALUES(8,7,1,'正文',10,0,'db'),(9,7,2,'文件正文',10,0,'txt'),(10,7,3,'付费',10,1,'db')");
        var page=repository.chapterPage(7,0,100);assertEquals(List.of(8L,9L,10L),page.stream().map(row->row.chapter().getId()).toList());
        for(long id:new long[]{8,9}){
            var preview=repository.bookPreview(7,id);assertEquals(false,preview.get("contentChecked"));assertFalse(preview.containsKey("contentReady"));
            assertEquals(true,preview.get("chapterIndexable"));assertEquals("",preview.get("chapterExcludedReason"));
        }
        assertEquals("paid_chapter",repository.bookPreview(7,10L).get("chapterExcludedReason"));
        db.execute("UPDATE book SET status=0 WHERE id=7");assertEquals("unpublished",repository.bookPreview(7,8L).get("chapterExcludedReason"));
    }
}
