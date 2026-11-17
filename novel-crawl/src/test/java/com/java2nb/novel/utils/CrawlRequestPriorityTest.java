package com.java2nb.novel.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Timeout(10)
class CrawlRequestPriorityTest {
    @Test void queuedRefreshPrecedesScansWithoutIncreasingOriginConcurrency() throws Exception {
        var gate=new OriginRequestGate(1);List<String> order=Collections.synchronizedList(new ArrayList<>());
        var active=new AtomicInteger();var maximum=new AtomicInteger();gate.enter();
        try(var pool=Executors.newFixedThreadPool(3)) {
            var scan=waiting(pool,gate,CrawlRequestContext.Kind.SCAN,"scan",order,active,maximum);
            var background=waiting(pool,gate,CrawlRequestContext.Kind.BACKGROUND,"background",order,active,maximum);
            var refresh=waiting(pool,gate,CrawlRequestContext.Kind.REFRESH,"refresh",order,active,maximum);
            gate.leave();scan.get(2,TimeUnit.SECONDS);background.get(2,TimeUnit.SECONDS);refresh.get(2,TimeUnit.SECONDS);
        }
        assertEquals(List.of("refresh","background","scan"),order);assertEquals(1,maximum.get());
    }
    Future<?> waiting(ExecutorService pool,OriginRequestGate gate,CrawlRequestContext.Kind kind,String name,List<String> order,AtomicInteger active,AtomicInteger max) {
        var thread=new AtomicReference<Thread>();
        Future<?> task=pool.submit(()->{thread.set(Thread.currentThread());try(var context=CrawlRequestContext.open(kind)) {
            gate.enter();try {max.accumulateAndGet(active.incrementAndGet(),Math::max);order.add(name);}
            finally {active.decrementAndGet();gate.leave();}
        } catch(InterruptedException e) {Thread.currentThread().interrupt();}});
        await().atMost(java.time.Duration.ofSeconds(1)).until(()->thread.get()!=null &&
            (thread.get().getState()==Thread.State.WAITING||thread.get().getState()==Thread.State.TIMED_WAITING));
        return task;
    }
    @Test void canceledWaitingScanCannotBlockAFollowingRefresh() throws Exception {
        var gate=new OriginRequestGate(1);gate.enter();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var order=Collections.synchronizedList(new ArrayList<String>());var active=new AtomicInteger();var maximum=new AtomicInteger();
            var scan=waiting(pool,gate,CrawlRequestContext.Kind.SCAN,"scan",order,active,maximum);scan.cancel(true);
            var refresh=waiting(pool,gate,CrawlRequestContext.Kind.REFRESH,"refresh",order,active,maximum);
            gate.leave();refresh.get(2,TimeUnit.SECONDS);assertEquals(List.of("refresh"),order);
        }
    }
    @Test void originRequestStartsStillRespectTheConfiguredInterval() throws Exception {
        var gate=new OriginRequestGate(40);gate.enter();long first=System.nanoTime();gate.leave();gate.enter();
        try {assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-first)>=35);}finally {gate.leave();}
    }
    @Test void pageReuseIsBoundToOneTaskAndCharset() throws Exception {
        var calls=new AtomicInteger();String body="<article>有效页面</article>"+" ".repeat(1500);
        var http=new CrawlHttpClient(){@Override public String fetchPage(String url,String charset){calls.incrementAndGet();return body;}};
        try(var context=CrawlRequestContext.bound("1","book","author")) {
            assertEquals(body,http.get("https://fixture/book/1/","UTF-8"));
            assertEquals(body,http.get("https://fixture/book/1/","UTF-8"));assertEquals(1,calls.get());
            http.get("https://fixture/book/1/","GBK");assertEquals(2,calls.get());
        }
        http.get("https://fixture/book/1/","UTF-8");assertEquals(3,calls.get());
    }
    @Test void authorReuseRequiresTheBoundSourceBookAndMatchingTitleAndNeverLeaksToAnotherTask() {
        try(var context=CrawlRequestContext.bound("1","book","author")) {
            assertEquals("author",CrawlRequestContext.boundAuthor("1","book"));
            assertNull(CrawlRequestContext.boundAuthor("2","book"));assertNull(CrawlRequestContext.boundAuthor("1","another"));
        }
        assertNull(CrawlRequestContext.boundAuthor("1","book"));assertEquals(1,CrawlRequestContext.priority());
    }
    @Test void leasedWorkerYieldsImmediatelyDuringChallengeCooldown() {
        var http=new CrawlHttpClient(){
            @Override public String fetchPage(String url,String charset){throw new CrawlAccessChallengeException(300);}
            @Override protected void waitForChallenge(long seconds){fail("A leased worker must yield its capacity");}
        };
        try(var context=CrawlRequestContext.open(CrawlRequestContext.Kind.REFRESH)) {
            assertThrows(CrawlAccessChallengeException.class,()->http.get("https://fixture/book/1/","UTF-8"));
        }
    }
}
