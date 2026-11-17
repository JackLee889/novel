package com.java2nb.novel.core.crawl;

import com.java2nb.novel.core.listener.StarterListener;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.CrawlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrawlRefreshBootstrapTest {
    @Test @Timeout(5)
    void completedBackfillCannotDelayInitialSerialRefreshScheduling() throws Exception {
        var books=mock(BookService.class);var service=mock(CrawlService.class);var refresh=mock(CrawlRefreshStore.class);
        var seeded=new AtomicInteger();var queued=new CountDownLatch(1);
        when(refresh.seedSerials(500)).thenAnswer(call->seeded.incrementAndGet()<3?500:0);
        var completed=new Book();completed.setId(77L);
        when(refresh.completedCandidates(any(Date.class),eq(100))).thenAnswer(call->{
            assertTrue(seeded.get()>=3,"All initial serial batches precede completed-book scheduling");return List.of(completed);
        });
        when(service.enqueueBookUpdate(77L)).thenAnswer(call->{queued.countDown();return true;});
        var listener=new StarterListener(books,service,mock(CrawlParser.class));listener.setRefreshStore(refresh);
        ReflectionTestUtils.setField(listener,"updateThreadCount",1);ReflectionTestUtils.setField(listener,"updateBatchPauseMillis",1L);
        try {listener.onStartup();assertTrue(queued.await(2,TimeUnit.SECONDS));verify(books,never()).queryNeedUpdateBook(any(),anyInt());}
        finally {listener.shutdown();}
    }
}
