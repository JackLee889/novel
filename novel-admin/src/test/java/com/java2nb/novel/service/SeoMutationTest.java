package com.java2nb.novel.service;

import com.java2nb.novel.dao.BookDao;
import com.java2nb.novel.dao.BookContentDao;
import com.java2nb.novel.dao.BookIndexDao;
import com.java2nb.novel.domain.BookDO;
import com.java2nb.novel.domain.BookContentDO;
import com.java2nb.novel.domain.BookIndexDO;
import com.java2nb.novel.service.impl.BookServiceImpl;
import com.java2nb.novel.service.impl.BookContentServiceImpl;
import com.java2nb.novel.service.impl.BookIndexServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeoMutationTest {
    @Test void viewCountsCommentsAndFetchTimesDoNotAdvanceRevision() {
        BookDO before=book("书名"); BookDO stats=new BookDO();stats.setId(42L);stats.setVisitCount(3L);stats.setCommentCount(2);stats.setCrawlLastTime(new Date());stats.setUpdateTime(new Date());
        assertFalse(SeoMutationSupport.bookChanged(before,stats));
        BookDO noOp=book("书名");assertFalse(SeoMutationSupport.bookChanged(before,noOp));
        noOp.setStatus(0);assertTrue(SeoMutationSupport.bookChanged(before,noOp));
        noOp=book("书名");noOp.setWordCount(0);assertTrue(SeoMutationSupport.bookChanged(before,noOp));
    }
    @Test void bookWriteLocksBeforeBusinessRowsAndCommitsDirtyTogether() {
        BookDao dao=mock(BookDao.class);SeoMutationSupport seo=mock(SeoMutationSupport.class);BookServiceImpl target=new BookServiceImpl();
        ReflectionTestUtils.setField(target,"bookDao",dao);ReflectionTestUtils.setField(target,"seo",seo);
        when(dao.get(42L)).thenReturn(book("旧标题"));when(dao.update(any())).thenReturn(1);
        RecordingTransactions tx=new RecordingTransactions();BookService service=proxy(target,tx,BookService.class);
        service.update(book("新标题"));
        var order=inOrder(seo,dao);order.verify(seo).begin();order.verify(dao).get(42L);order.verify(dao).update(any());order.verify(seo).dirty(42L);
        assertEquals(1,tx.commits);assertEquals(0,tx.rollbacks);
    }
    @Test void dirtyFailureRollsBackBusinessWriteAndNoopSkipsDirty() {
        BookDao dao=mock(BookDao.class);SeoMutationSupport seo=mock(SeoMutationSupport.class);BookServiceImpl target=new BookServiceImpl();
        ReflectionTestUtils.setField(target,"bookDao",dao);ReflectionTestUtils.setField(target,"seo",seo);
        when(dao.get(42L)).thenReturn(book("旧标题"));when(dao.update(any())).thenReturn(1);
        doThrow(new IllegalStateException("state store unavailable")).when(seo).dirty(42L);
        RecordingTransactions tx=new RecordingTransactions();BookService service=proxy(target,tx,BookService.class);
        assertThrows(IllegalStateException.class,()->service.update(book("新标题")));
        assertEquals(0,tx.commits);assertEquals(1,tx.rollbacks);
        reset(seo);service.update(book("旧标题"));verify(seo,never()).dirty(any());assertEquals(1,tx.commits);
    }
    @Test void chapterMoveDirtiesBothOwnersAndBodyNoopDoesNotDirty() {
        BookIndexDao indexes=mock(BookIndexDao.class);SeoMutationSupport seo=mock(SeoMutationSupport.class);
        BookIndexServiceImpl target=new BookIndexServiceImpl();ReflectionTestUtils.setField(target,"bookIndexDao",indexes);ReflectionTestUtils.setField(target,"seo",seo);
        BookIndexDO before=index(9L,42L);when(indexes.get(9L)).thenReturn(before);when(indexes.update(any())).thenReturn(1);
        target.update(index(9L,43L));verify(seo).dirty(42L);verify(seo).dirty(43L);
        reset(seo);BookContentDao bodies=mock(BookContentDao.class);BookContentServiceImpl content=new BookContentServiceImpl();
        ReflectionTestUtils.setField(content,"bookContentDao",bodies);ReflectionTestUtils.setField(content,"bookIndexDao",indexes);ReflectionTestUtils.setField(content,"seo",seo);
        BookContentDO old=body("正文"),change=body("正文");when(bodies.get(5L)).thenReturn(old);when(bodies.update(any())).thenReturn(1);
        content.update(change);verify(seo,never()).dirty(any());change.setContent("修改正文");content.update(change);verify(seo).dirty(42L);
    }
    @Test void removeRevokesOldMapsOnlyForSuccessfulBusinessRemoval() {
        BookDao dao=mock(BookDao.class);BookIndexDao indexes=mock(BookIndexDao.class);BookContentDao bodies=mock(BookContentDao.class);SeoMutationSupport seo=mock(SeoMutationSupport.class);
        BookServiceImpl target=new BookServiceImpl();ReflectionTestUtils.setField(target,"bookDao",dao);ReflectionTestUtils.setField(target,"bookIndexDao",indexes);ReflectionTestUtils.setField(target,"bookContentDao",bodies);ReflectionTestUtils.setField(target,"seo",seo);
        when(indexes.getIdsByBookId(42L)).thenReturn(java.util.List.of());when(dao.remove(42L)).thenReturn(1);
        target.remove(42L);verify(seo).removed(42L);when(dao.remove(43L)).thenReturn(0);target.remove(43L);verify(seo,never()).removed(43L);
    }
    private static BookDO book(String title){BookDO b=new BookDO();b.setId(42L);b.setBookName(title);b.setStatus(1);b.setWordCount(100);return b;}
    private static BookIndexDO index(long id,long book){BookIndexDO i=new BookIndexDO();i.setId(id);i.setBookId(book);i.setIndexName("章节");return i;}
    private static BookContentDO body(String value){BookContentDO b=new BookContentDO();b.setId(5L);b.setIndexId(9L);b.setContent(value);return b;}
    private static <T> T proxy(Object target,RecordingTransactions tx,Class<T> type){
        ProxyFactory factory=new ProxyFactory(target);factory.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));return type.cast(factory.getProxy());
    }
    static final class RecordingTransactions extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
