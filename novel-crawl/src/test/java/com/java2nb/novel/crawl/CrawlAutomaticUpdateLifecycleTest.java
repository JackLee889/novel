package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.CrawlBookBusyException;
import com.java2nb.novel.core.crawl.CrawlBookHandler;
import com.java2nb.novel.core.listener.StarterListener;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.entity.CrawlSingleTask;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.CrawlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(5)
class CrawlAutomaticUpdateLifecycleTest {
    @Test
    void updaterSchedulesBooksWithoutPerformingNetworkRequests() throws Exception {
        BookService books=mock(BookService.class); CrawlService sources=mock(CrawlService.class); CrawlParser parser=mock(CrawlParser.class);
        when(books.queryNeedUpdateBook(any(Date.class),eq(100)))
            .thenReturn(List.of(book(1L,12,"a"),book(2L,13,"b"),book(3L,14,"c")),List.of());
        CountDownLatch queued=new CountDownLatch(3);
        when(sources.enqueueBookUpdate(anyLong())).thenAnswer(call->{queued.countDown();return true;});
        var listener=new StarterListener(books,sources,parser);
        ReflectionTestUtils.setField(listener,"updateThreadCount",1);
        try {
            listener.onStartup();assertTrue(queued.await(2,TimeUnit.SECONDS));
            verify(sources).enqueueBookUpdate(1L);verify(sources).enqueueBookUpdate(2L);verify(sources).enqueueBookUpdate(3L);
            verifyNoInteractions(parser);
        } finally {stop(listener);}
    }

    @Test
    void anEnqueueFailureDoesNotPreventSchedulingLaterBooks() throws Exception {
        BookService books=mock(BookService.class); CrawlService sources=mock(CrawlService.class);
        when(books.queryNeedUpdateBook(any(Date.class),eq(100))).thenReturn(List.of(book(1L,12,"bad"),book(2L,12,"next")));
        when(sources.enqueueBookUpdate(1L)).thenThrow(new IllegalStateException("isolated transaction failure"));
        CountDownLatch next=new CountDownLatch(1);
        when(sources.enqueueBookUpdate(2L)).thenAnswer(call->{next.countDown();return true;});
        var listener=new StarterListener(books,sources,mock(CrawlParser.class));
        ReflectionTestUtils.setField(listener,"updateThreadCount",1);
        try {listener.onStartup();assertTrue(next.await(2,TimeUnit.SECONDS));}
        finally {stop(listener);}
    }

    @Test
    void aBacklogContinuesIntoTheNextBatchWithoutTheTenMinuteIdleDelay() throws Exception {
        BookService books=mock(BookService.class); CrawlService sources=mock(CrawlService.class);
        when(books.queryNeedUpdateBook(any(Date.class),eq(100)))
            .thenReturn(List.of(book(1L,12,"first")),List.of(book(2L,12,"second")),List.of());
        CountDownLatch second=new CountDownLatch(1);
        when(sources.enqueueBookUpdate(2L)).thenAnswer(call->{second.countDown();return true;});
        var listener=new StarterListener(books,sources,mock(CrawlParser.class));
        ReflectionTestUtils.setField(listener,"updateThreadCount",1);
        ReflectionTestUtils.setField(listener,"updateBatchPauseMillis",10L);
        try {listener.onStartup();assertTrue(second.await(2,TimeUnit.SECONDS));verify(sources).enqueueBookUpdate(1L);}
        finally {stop(listener);}
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void idleAndFailedSelectionKeepTheirBackoff(boolean fail) throws Exception {
        BookService books = mock(BookService.class);
        CountDownLatch selected = new CountDownLatch(1);
        when(books.queryNeedUpdateBook(any(Date.class), eq(100))).thenAnswer(call -> {
            selected.countDown();
            if (fail) throw new IllegalStateException("simulated database outage");
            return List.of();
        });
        var listener = new StarterListener(books, mock(CrawlService.class), mock(CrawlParser.class));
        ReflectionTestUtils.setField(listener, "updateThreadCount", 1);
        ReflectionTestUtils.setField(listener, "updateBatchPauseMillis", 1L);
        try {
            listener.onStartup();
            assertTrue(selected.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            verify(books, times(1)).queryNeedUpdateBook(any(Date.class), eq(100));
        } finally { stop(listener); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aBusyOrInterruptedSingleTaskRequeuesWithoutARecordedFailure(boolean interrupt) throws Exception {
        CrawlService sources = mock(CrawlService.class);
        var source = new CrawlSource(); source.setCrawlRule("{}");
        when(sources.queryCrawlSource(14)).thenReturn(source);
        var task = new CrawlSingleTask(); task.setId(8L); task.setSourceId(14); task.setSourceBookId("sample"); task.setCatId(1);
        when(sources.getCrawlSingleTask()).thenReturn(task);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch queued = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (interrupt) { Thread.sleep(60000); return false; }
            throw new CrawlBookBusyException(1L);
        }).when(sources).parseBookAndSave(eq(1), any(), eq(14), eq("sample"), same(task));
        doAnswer(call -> {
            assertFalse(Thread.currentThread().isInterrupted(), "Requeue must be allowed to acquire a JDBC connection");
            queued.countDown(); return null;
        }).when(sources).updateCrawlSingleTask(same(task), eq((byte)2));
        var listener = new StarterListener(mock(BookService.class), sources, mock(CrawlParser.class));
        try {
            listener.onStartup();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            if (interrupt) listener.shutdown();
            assertTrue(queued.await(2, TimeUnit.SECONDS));
            verify(sources).updateCrawlSingleTask(same(task), eq((byte)2));
        } finally { stop(listener); }
    }

    @Test
    void aFailedSourceRestoreIsClosedAndDoesNotPreventRestoringOthers() throws Exception {
        BookService books = mock(BookService.class);
        CrawlService sources = mock(CrawlService.class);
        var broken = new CrawlSource(); broken.setId(1);
        var healthy = new CrawlSource(); healthy.setId(14);
        when(sources.queryCrawlSourceByStatus((byte) 1)).thenReturn(List.of(broken,healthy));
        doThrow(new IllegalArgumentException("invalid fixture rule"))
            .when(sources).openOrCloseCrawl(1,(byte) 1);
        var listener = new StarterListener(books,sources,mock(CrawlParser.class));
        try {
            listener.onStartup();
            verify(sources).updateCrawlSourceStatus(1,(byte) 0);
            verify(sources).openOrCloseCrawl(14,(byte) 1);
        } finally {
            stop(listener);
        }
    }

    private static Book book(long id, int source, String sourceBook) {
        var book = new Book();
        book.setId(id); book.setCrawlSourceId(source); book.setCrawlBookId(sourceBook);
        return book;
    }

    private static void stop(StarterListener listener) throws Exception {
        listener.shutdown();
        @SuppressWarnings("unchecked")
        List<Thread> workers = (List<Thread>) ReflectionTestUtils.getField(listener,"workers");
        for (Thread worker : workers) {
            worker.join(1000);
            assertFalse(worker.isAlive());
        }
    }
}
