package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.*;
import com.java2nb.novel.core.io.BackgroundIoGate;
import com.java2nb.novel.service.impl.BookServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual parser, transaction, checkpoint and summary tests with revisions enabled. */
class CrawlIncrementalIoTest {
    CrawlBookSummaryTest fixture;
    JdbcTemplate jdbc;
    CrawlIndexStore store;
    @BeforeEach void setup() throws Exception {
        fixture=new CrawlBookSummaryTest();fixture.setUp();
        jdbc=(JdbcTemplate)ReflectionTestUtils.getField(fixture,"jdbc");
        jdbc.execute("CREATE TABLE crawl_book_io_state(book_id BIGINT PRIMARY KEY,revision BIGINT DEFAULT 0,word_count BIGINT,checked_revision BIGINT,source_id INT,source_book_id VARCHAR(128),catalog_hash CHAR(64),catalog_count INT,aligned TINYINT DEFAULT 0)");
        for(String table:List.of("book_index","book_content"))for(String event:List.of("INSERT","UPDATE","DELETE"))
            jdbc.execute("CREATE TRIGGER io_"+table+event+" AFTER "+event+" ON "+table+" FOR EACH ROW CALL '"+RevisionTrigger.class.getName()+"'");
        store=new CrawlIndexStore(jdbc,BackgroundIoGate.disabled(),new DataSourceTransactionManager(jdbc.getDataSource()));
        ReflectionTestUtils.setField(store,"enabled",true);
        ((BookServiceImpl)ReflectionTestUtils.getField(fixture,"books")).setIndexStore(store);
    }
    @Test void committedSlicesResumeAndDoNotRefetchSavedBodies() throws Exception {fixture.boundedUpdateCommitsItsCheckpointAndResumesWithoutRefetchingSavedChapters();}
    @Test void missingBodyRepairsKeepOriginalChapterIds() throws Exception {fixture.repairsMissingBodyRowsAndEmptySameTitleBodiesWithoutReplacingChapterIdentities();}
    @Test void appendReusesVerifiedPrefix() throws Exception {fixture.appendedChaptersReuseTheVerifiedCatalogPrefixInsteadOfRescanningOldBodies();}
    @Test void failedBatchCannotPublishReadyCheckpoint() throws Exception {fixture.failedStorageCallbackCannotPublishAFalseReadyCheckpoint();}
    @Test void partialValidationSurvivesRestart() throws Exception {fixture.legacyVerificationIsBoundedAndItsCheckpointSurvivesParserRestart();}
    @Test void reorderingPreservesOldBodiesAndCurrentTail() throws Exception {fixture.backgroundReconciliationKeepsHistoryButPublishesTheCurrentCatalogTail();}
    @Test void completedWithHolesContinuesOnlyMissingChapters() throws Exception {fixture.completedSourceWithDeferredOrHtmlOnlyBodiesIsNotCollectedInFullAndResumesOnlyHoles();}
    @Test void failedWriteRollsBackRevisionAndWordTotal() {
        jdbc.update("INSERT INTO book_index(id,book_id,index_num,index_name,source_index_id,word_count) VALUES(11,1,0,'a','a',7)");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(s->assertEquals(7,store.totalWords(1)));
        long before=jdbc.queryForObject("SELECT revision FROM crawl_book_io_state WHERE book_id=1",Long.class);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{jdbc.update("UPDATE book_index SET word_count=13 WHERE id=11");assertEquals(13,store.totalWords(1));throw new IllegalStateException("fixture");}));
        assertEquals(7,jdbc.queryForObject("SELECT word_count FROM crawl_book_io_state WHERE book_id=1",Integer.class));
        assertEquals(before,jdbc.queryForObject("SELECT revision FROM crawl_book_io_state WHERE book_id=1",Long.class));
        jdbc.update("UPDATE book_index SET word_count=9 WHERE id=11");
        assertEquals(9,jdbc.queryForObject("SELECT word_count FROM crawl_book_io_state WHERE book_id=1",Integer.class));
    }
    @Test void sparseRequiresExactRevisionAndArchivedIdentityCollisionsFallBack() {
        jdbc.update("INSERT INTO book_index(id,book_id,index_num,index_name,source_index_id,word_count) VALUES(11,1,0,'a','a',7),(12,1,99,'b','b',9)");
        var first=store.snapshot(1);assertFalse(first.canUseSparse(1,"s","hash",1,n->"hash"));first.certify(List.of("a"));
        var audit=new CrawlCollectionStore.Audit(1,1,"s","hash",1);first.checkpoint(audit,()->{});
        var second=store.snapshot(1);assertTrue(second.canUseSparse(1,"s","append",2,n->"hash"));
        assertFalse(second.loadSlice(List.of(1),List.of("b")));assertEquals(2,second.size());assertFalse(second.sparse());
        jdbc.update("INSERT INTO book_content(index_id,content) VALUES(11,'changed')");
        var third=store.snapshot(1);assertFalse(third.canUseSparse(1,"s","hash",1,n->"hash"));assertTrue(third.invalidAudit());
        assertThrows(ConcurrentModificationException.class,()->first.checkpoint(audit,()->fail("stale audit saved")));
    }
    @Test void sparseSliceNeverTreatsUnqueriedRowsAsAbsentAndSourceSwitchFallsBack(){
        jdbc.update("INSERT INTO book_index(id,book_id,index_num,index_name,source_index_id,word_count) VALUES(11,1,0,'a','a',7),(12,1,1,'b','b',9)");
        var first=store.snapshot(1);first.certify(List.of("a","b"));first.checkpoint(new CrawlCollectionStore.Audit(1,1,"s","hash",2),()->{});
        var wrong=store.snapshot(1);assertFalse(wrong.canUseSparse(2,"other","hash",2,n->"hash"));
        var next=store.snapshot(1);assertTrue(next.canUseSparse(1,"s","hash",2,n->"hash"));assertTrue(next.loadSlice(List.of(1),List.of("b")));
        assertTrue(next.sparse());assertEquals(Set.of(1),next.keySet());assertEquals(12L,next.get(1).getId());
    }
    @Test void completedBookExclusionRequiresUnchangedRevisionAndOriginalSource(){
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());var queue=new CrawlDiscoveryStore(jdbc,manager);
        ReflectionTestUtils.setField(queue,"ioVersioned",true);
        var book=new com.java2nb.novel.entity.Book();book.setId(1L);book.setBookStatus((byte)1);book.setCrawlSourceId(1);book.setCrawlBookId("s");
        var audit=new CrawlCollectionStore.Audit(1,1,"s",CrawlCollectionStore.hash("s"),1);audit.sourceCompleted=true;audit.mark(0,true);
        new CrawlCollectionStore(jdbc,System.getProperty("java.io.tmpdir")).save(audit);
        assertTrue(queue.isVerifiedCompleted(book));
        jdbc.update("INSERT INTO crawl_book_io_state(book_id,revision,checked_revision) VALUES(1,2,2)");assertTrue(queue.isVerifiedCompleted(book));
        jdbc.update("UPDATE crawl_book_io_state SET revision=3 WHERE book_id=1");assertFalse(queue.isVerifiedCompleted(book));
        jdbc.update("UPDATE crawl_book_io_state SET checked_revision=3 WHERE book_id=1");assertTrue(queue.isVerifiedCompleted(book));
        book.setCrawlSourceId(2);assertFalse(queue.isVerifiedCompleted(book));
    }
    public static class RevisionTrigger implements org.h2.api.Trigger {
        boolean index;
        public void init(Connection c,String schema,String trigger,String table,boolean before,int type){index=table.equalsIgnoreCase("book_index");}
        public void fire(Connection c,Object[] old,Object[] row)throws SQLException {
            if(index){
                if(old!=null&&row!=null&&Arrays.equals(Arrays.copyOf(old,9),Arrays.copyOf(row,9)))return;
                if(old!=null)bump(c,((Number)old[1]).longValue(),-words(old[5]));
                if(row!=null)bump(c,((Number)row[1]).longValue(),words(row[5]));
            }else{
                if(old!=null&&row!=null&&Objects.equals(old[1],row[1])&&Objects.equals(String.valueOf(old[2]),String.valueOf(row[2])))return;
                Set<Long> ids=new HashSet<>();if(old!=null)ids.add(((Number)old[1]).longValue());if(row!=null)ids.add(((Number)row[1]).longValue());
                for(long id:ids)try(var p=c.prepareStatement("SELECT book_id FROM book_index WHERE id=?")){p.setLong(1,id);try(var r=p.executeQuery()){if(r.next())bump(c,r.getLong(1),0);}}
            }
        }
        private long words(Object value){return value==null?0:((Number)value).longValue();}
        private void bump(Connection c,long id,long delta)throws SQLException{
            try(var p=c.prepareStatement("INSERT INTO crawl_book_io_state(book_id,revision) VALUES(?,1) ON DUPLICATE KEY UPDATE revision=revision+1,word_count=word_count+?")){p.setLong(1,id);p.setLong(2,delta);p.executeUpdate();}
        }
    }
}
