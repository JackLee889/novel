package com.java2nb.novel.seo;

import org.h2.api.Trigger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;

class SeoChapterReadinessCacheTest {
    JdbcDataSource source;JdbcTemplate db;SeoRepository repository;SeoChapterReadinessCache cache;
    @BeforeEach void create(){
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:seo_ready_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(source);
        db.execute("CREATE TABLE book_content(index_id BIGINT PRIMARY KEY,content CLOB)");
        db.execute("CREATE TABLE seo_chapter_readiness(index_id BIGINT PRIMARY KEY,revision BIGINT NOT NULL DEFAULT 0,ready TINYINT,validator_version INT NOT NULL DEFAULT 0)");
        db.execute("CREATE TRIGGER invalidation AFTER INSERT, UPDATE, DELETE ON book_content FOR EACH ROW CALL '"+Invalidation.class.getName()+"'");
        repository=spy(new SeoRepository(source));cache=enabledCache();
    }
    SeoChapterReadinessCache enabledCache(){var c=new SeoChapterReadinessCache(source,repository);ReflectionTestUtils.setField(c,"enabled",true);return c;}
    // Mirrors the migration for local SQL tests; actual MySQL triggers are tested independently.
    public static class Invalidation implements Trigger {
        @Override public void fire(Connection c,Object[] old,Object[] value)throws SQLException{
            try(var statement=c.prepareStatement("UPDATE seo_chapter_readiness SET revision=revision+1,ready=NULL,validator_version=0 WHERE index_id IN (?,?)")){
                statement.setObject(1,old==null?null:old[0]);statement.setObject(2,value==null?null:value[0]);statement.executeUpdate();
            }
        }
    }
    @Test void cachesPositiveNegativeAndMissingBodiesAcrossWorkerRestarts(){
        db.update("INSERT INTO book_content VALUES(1,'<p>真实正文</p>'),(2,'<p>&nbsp;</p>'),(3,NULL),(4,'<script>hidden</script>')");
        var ids=List.of(1L,2L,3L,4L,5L);var cold=cache.check(ids);
        assertEquals(Set.of(1L),cold.ready());assertEquals(5,cold.checkedRows());assertEquals(0,cold.cacheHits());
        clearInvocations(repository);var warm=enabledCache().check(ids);
        assertEquals(cold.ready(),warm.ready());assertEquals(5,warm.cacheHits());assertEquals(0,warm.checkedRows());verify(repository,never()).readyDatabaseIds(anyList());
    }
    @Test void onlyChangedAndNewBodiesAreRecheckedIncludingDeleteAndIdentityMove(){
        db.update("INSERT INTO book_content VALUES(1,'正文一'),(2,'正文二')");cache.check(List.of(1L,2L,3L));clearInvocations(repository);
        db.update("UPDATE book_content SET content='<p>&nbsp;</p>' WHERE index_id=1");
        var update=cache.check(List.of(1L,2L,3L));assertEquals(Set.of(2L),update.ready());assertEquals(1,update.checkedRows());verify(repository).readyDatabaseIds(List.of(1L));
        db.update("INSERT INTO book_content VALUES(3,'新正文')");assertEquals(Set.of(2L,3L),cache.check(List.of(1L,2L,3L)).ready());
        db.update("DELETE FROM book_content WHERE index_id=2");assertEquals(Set.of(3L),cache.check(List.of(1L,2L,3L)).ready());
        db.update("UPDATE book_content SET index_id=2 WHERE index_id=3");var move=cache.check(List.of(1L,2L,3L));assertEquals(Set.of(2L),move.ready());assertEquals(2,move.checkedRows());
    }
    @Test void concurrentMutationCannotWriteAnOldPositiveResultIntoTheCache(){
        db.update("INSERT INTO book_content VALUES(1,'原始正文')");
        doAnswer(call->{var result=call.callRealMethod();db.update("UPDATE book_content SET content='' WHERE index_id=1");return result;}).when(repository).readyDatabaseIds(List.of(1L));
        assertThrows(org.springframework.dao.ConcurrencyFailureException.class,()->cache.check(List.of(1L)));
        assertNull(db.queryForObject("SELECT ready FROM seo_chapter_readiness WHERE index_id=1",Integer.class));
        doCallRealMethod().when(repository).readyDatabaseIds(anyList());assertTrue(cache.check(List.of(1L)).ready().isEmpty());
    }
    @Test void rolledBackBodyChangesRollBackInvalidationAndValidatorVersionForcesRecheck(){
        db.update("INSERT INTO book_content VALUES(1,'正文')");cache.check(List.of(1L));
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        tx.execute(status->{db.update("UPDATE book_content SET content='' WHERE index_id=1");status.setRollbackOnly();return null;});
        assertEquals(1,cache.check(List.of(1L)).cacheHits());
        db.update("UPDATE seo_chapter_readiness SET validator_version=0 WHERE index_id=1");assertEquals(1,cache.check(List.of(1L)).checkedRows());
    }
    @Test void featureOffDoesNotRequireMigrationAndErrorsNeverBecomeReady(){
        db.update("INSERT INTO book_content VALUES(1,'正文')");db.execute("DROP TABLE seo_chapter_readiness");
        assertEquals(Set.of(1L),new SeoChapterReadinessCache(source,repository).check(List.of(1L)).ready());
        assertThrows(org.springframework.dao.DataAccessException.class,()->cache.check(List.of(1L)));
    }
    @Test void batchesAreBoundedDeduplicatedAndEmptyBatchDoesNoWork(){
        assertEquals(0,cache.check(List.of()).checkedRows());assertThrows(IllegalArgumentException.class,()->cache.check(Collections.nCopies(101,1L)));
        assertThrows(IllegalArgumentException.class,()->cache.check(List.of(-1L)));
        assertEquals(1,cache.check(List.of(1L,1L)).checkedRows());assertEquals(1,cache.check(List.of(1L,1L)).cacheHits());
    }
}
