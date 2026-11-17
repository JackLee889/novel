package com.java2nb.novel.core.listener;

import com.java2nb.novel.core.crawl.CrawlDiscoveryStore;
import com.java2nb.novel.core.crawl.CrawlRefreshStore;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real worker threads with blocked source fixtures; all database and HTTP operations are isolated mocks. */
@Timeout(15)
class CrawlSourceWorkerIsolationTest {
    CrawlDiscoveryStore queue;
    CrawlRefreshStore refresh;
    CrawlServiceImpl service;
    CrawlDiscoveryWorker worker;
    final AtomicReference<List<Integer>> enabled=new AtomicReference<>(List.of(1,2,3,4));
    final Map<Integer,AtomicInteger> tickets=new ConcurrentHashMap<>();
    final Map<Integer,Set<Thread>> sourceThreads=new ConcurrentHashMap<>();
    final Map<Integer,AtomicInteger> downloadTickets=new ConcurrentHashMap<>();
    final Map<Integer,Set<Thread>> downloadThreads=new ConcurrentHashMap<>();
    final List<CountDownLatch> releases=new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        queue=mock(CrawlDiscoveryStore.class);refresh=mock(CrawlRefreshStore.class);service=mock(CrawlServiceImpl.class);
        worker=new CrawlDiscoveryWorker(queue,service);worker.setRefreshStore(refresh);
        when(refresh.enabledSources()).thenAnswer(invocation->enabled.get());
        when(refresh.claimForSource(anyInt())).thenAnswer(invocation->{
            int source=invocation.getArgument(0);
            sourceThreads.computeIfAbsent(source,ignored->ConcurrentHashMap.newKeySet()).add(Thread.currentThread());
            int available=tickets.computeIfAbsent(source,ignored->new AtomicInteger()).getAndUpdate(n->Math.max(0,n-1));
            return available>0?job(source,source*100L+available):null;
        });
        when(queue.claimBackground(anyBoolean(),anyList())).thenAnswer(invocation->{
            List<Integer> sources=invocation.getArgument(1);
            assertEquals(1,sources.size(),"Every download worker must own exactly one source");
            int source=sources.getFirst();
            downloadThreads.computeIfAbsent(source,ignored->ConcurrentHashMap.newKeySet()).add(Thread.currentThread());
            int available=downloadTickets.computeIfAbsent(source,ignored->new AtomicInteger()).getAndUpdate(n->Math.max(0,n-1));
            return available>0?job(source,10000+source*100L+available):null;
        });
        when(service.queryCrawlSource(anyInt())).thenAnswer(invocation->{
            int id=invocation.getArgument(0);var source=new CrawlSource();source.setId(id);
            source.setSourceStatus((byte)(enabled.get().contains(id)?1:0));source.setCrawlRule("{}");return source;
        });
    }

    @AfterEach void cleanup() throws Exception {
        releases.forEach(CountDownLatch::countDown);worker.stop();
        for(Set<Thread> threads:sourceThreads.values()) for(Thread thread:threads)
            assertFalse(thread.isAlive(),"A source worker leaked after shutdown: "+thread.getName());
        for(Set<Thread> threads:downloadThreads.values()) for(Thread thread:threads)
            assertFalse(thread.isAlive(),"A download worker leaked after shutdown: "+thread.getName());
    }

    static CrawlDiscoveryStore.Job job(int source,long id) {
        return new CrawlDiscoveryStore.Job(id,source,"book"+id,1,id,"lease"+id,0);
    }
    static CrawlServiceImpl.DiscoveryResult partial(CrawlDiscoveryStore.Job job) {
        return new CrawlServiceImpl.DiscoveryResult(false,"PARTIAL",job.bookId());
    }
    CountDownLatch release() {var latch=new CountDownLatch(1);releases.add(latch);return latch;}
    void available(int source) {tickets.computeIfAbsent(source,ignored->new AtomicInteger()).incrementAndGet();}
    void downloadAvailable(int source) {downloadTickets.computeIfAbsent(source,ignored->new AtomicInteger()).incrementAndGet();}
    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"Timed out waiting for worker progress");
    }

    @Test void oneBlockedSourceDoesNotDelayAnyOfTheOtherThreeSources() throws Exception {
        enabled.get().forEach(this::available);
        var blocked=new CountDownLatch(1);var fast=new CountDownLatch(3);var resume=release();
        when(service.refreshExistingBook(any(),any(RuleBean.class),same(refresh))).thenAnswer(invocation->{
            CrawlDiscoveryStore.Job job=invocation.getArgument(0);
            if(job.sourceId()==1) {blocked.countDown();resume.await();}
            else fast.countDown();
            return partial(job);
        });
        worker.start();worker.start();
        assertTrue(blocked.await(5,TimeUnit.SECONDS));
        assertTrue(fast.await(5,TimeUnit.SECONDS),"All other sources must progress while source 1 is still blocked");
        assertEquals(1,resume.getCount());
        for(int source:enabled.get()) {
            assertEquals(1,sourceThreads.get(source).size());
            assertEquals("crawl-refresh-source-"+source,sourceThreads.get(source).iterator().next().getName());
        }
        verify(refresh,never()).claim();
    }

    @Test void allFourDownloadWorkersRunWhenEverySourceRefreshWorkerIsOccupied() throws Exception {
        enabled.get().forEach(this::available);
        enabled.get().forEach(this::downloadAvailable);
        var entered=new CountDownLatch(4);var resume=release();var background=new CountDownLatch(4);
        when(service.refreshExistingBook(any(),any(RuleBean.class),same(refresh))).thenAnswer(invocation->{
            entered.countDown();resume.await();return partial(invocation.getArgument(0));
        });
        when(service.parseDiscoveredBook(any(),any())).thenAnswer(invocation->{
            CrawlDiscoveryStore.Job job=invocation.getArgument(0);
            assertEquals("crawl-download-source-"+job.sourceId(),Thread.currentThread().getName());
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            background.countDown();return partial(job);
        });
        worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
        assertTrue(background.await(7,TimeUnit.SECONDS));assertEquals(1,resume.getCount());
        verify(queue,timeout(1000).times(4)).finish(any(),eq("PARTIAL"),anyLong(),isNull(),eq(false));
    }

    @Test void oneSlowDownloadDoesNotBlockOtherSourceDownloadsOrItsOwnRefreshThread() throws Exception {
        enabled.get().forEach(this::downloadAvailable);available(1);
        var blocked=new CountDownLatch(1);var fast=new CountDownLatch(3);var ownRefresh=new CountDownLatch(1);var resume=release();
        when(service.parseDiscoveredBook(any(),any())).thenAnswer(invocation->{
            CrawlDiscoveryStore.Job job=invocation.getArgument(0);
            assertEquals("crawl-download-source-"+job.sourceId(),Thread.currentThread().getName());
            if(job.sourceId()==1) {blocked.countDown();resume.await();} else fast.countDown();
            return partial(job);
        });
        when(service.refreshExistingBook(any(),any(),same(refresh))).thenAnswer(invocation->{
            ownRefresh.countDown();return partial(invocation.getArgument(0));
        });
        worker.start();worker.start();
        assertTrue(blocked.await(5,TimeUnit.SECONDS));assertTrue(fast.await(5,TimeUnit.SECONDS));
        assertTrue(ownRefresh.await(5,TimeUnit.SECONDS));assertEquals(1,resume.getCount());
        for(int source:enabled.get())assertEquals(1,downloadThreads.get(source).size());
    }

    @Test void sourceSwitchesCreateStopAndRecreateOnlyTheCorrespondingWorker() throws Exception {
        enabled.set(List.of(1));available(1);var completed=new AtomicInteger();
        when(service.refreshExistingBook(any(),any(RuleBean.class),same(refresh))).thenAnswer(invocation->{
            completed.incrementAndGet();return partial(invocation.getArgument(0));
        });
        worker.start();await(()->completed.get()==1);Thread first=sourceThreads.get(1).iterator().next();
        verify(refresh,never()).claimForSource(2);
        enabled.set(List.of(1,2));available(2);worker.reconcileRefreshWorkers();await(()->completed.get()==2);
        enabled.set(List.of(2));worker.reconcileRefreshWorkers();await(()->!first.isAlive());
        enabled.set(List.of(1,2));available(1);worker.reconcileRefreshWorkers();await(()->completed.get()==3);
        assertEquals(2,sourceThreads.get(1).size());assertEquals(1,sourceThreads.get(2).size());
        await(()->downloadThreads.get(1).size()==2 && downloadThreads.containsKey(2));
        assertEquals(1,downloadThreads.get(1).stream().filter(Thread::isAlive).count());
        assertEquals(1,downloadThreads.get(2).size());
    }

    @Test void rapidDisableAndEnableCannotStartASecondWorkerWhileTheOldClientIgnoresInterruption() throws Exception {
        enabled.set(List.of(1));available(1);var resume=release();var calls=new AtomicInteger();
        var concurrent=new AtomicInteger();var maximum=new AtomicInteger();
        when(service.refreshExistingBook(any(),any(RuleBean.class),same(refresh))).thenAnswer(invocation->{
            int active=concurrent.incrementAndGet();maximum.accumulateAndGet(active,Math::max);
            try {
                if(calls.incrementAndGet()==1) while(resume.getCount()>0) {
                    try {resume.await();} catch(InterruptedException slowClient) { /* Simulated uninterruptible library call. */ }
                }
                return partial(invocation.getArgument(0));
            } finally {concurrent.decrementAndGet();}
        });
        worker.start();await(()->calls.get()==1);Thread first=sourceThreads.get(1).iterator().next();
        enabled.set(List.of());worker.reconcileRefreshWorkers();
        enabled.set(List.of(1));available(1);worker.reconcileRefreshWorkers();
        assertTrue(first.isAlive());assertEquals(1,sourceThreads.get(1).size());assertEquals(1,calls.get());
        resume.countDown();await(()->!first.isAlive());worker.reconcileRefreshWorkers();await(()->calls.get()==2);
        assertEquals(1,maximum.get(),"A cancelled but still live client must retain its source slot");
    }

    @Test void rapidDownloadDisableEnableWaitsForTheOldSlowClientBeforeReplacingItsThread() throws Exception {
        enabled.set(List.of(1));downloadAvailable(1);var resume=release();var calls=new AtomicInteger();
        var active=new AtomicInteger();var maximum=new AtomicInteger();
        when(service.parseDiscoveredBook(any(),any())).thenAnswer(invocation->{
            maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
            try {
                if(calls.incrementAndGet()==1)while(resume.getCount()>0) {
                    try {resume.await();}catch(InterruptedException slowClient) { /* Keep its slot until this call returns. */ }
                }
                return partial(invocation.getArgument(0));
            }finally {active.decrementAndGet();}
        });
        worker.start();await(()->calls.get()==1);Thread old=downloadThreads.get(1).iterator().next();
        enabled.set(List.of());worker.reconcileRefreshWorkers();
        enabled.set(List.of(1));downloadAvailable(1);worker.reconcileRefreshWorkers();
        assertTrue(old.isAlive());assertEquals(1,downloadThreads.get(1).size());assertEquals(1,calls.get());
        resume.countDown();await(()->!old.isAlive());worker.reconcileRefreshWorkers();await(()->calls.get()==2);
        assertEquals(1,maximum.get());
    }

    @Test void shutdownReturnsOwnedLeasesAndNeverRecreatesSourceWorkers() throws Exception {
        enabled.set(List.of(1,2));enabled.get().forEach(this::available);enabled.get().forEach(this::downloadAvailable);
        var entered=new CountDownLatch(4);var resume=release();
        when(service.refreshExistingBook(any(),any(RuleBean.class),same(refresh))).thenAnswer(invocation->{
            entered.countDown();resume.await();return partial(invocation.getArgument(0));
        });
        when(service.parseDiscoveredBook(any(),any())).thenAnswer(invocation->{
            entered.countDown();resume.await();return partial(invocation.getArgument(0));
        });
        worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));worker.stop();
        verify(refresh,times(2)).finish(any(),eq(false),isNull());
        verify(queue,times(2)).finish(any(),eq("PENDING"),isNull(),eq("Interrupted; resumable"),eq(false));
        worker.start();worker.reconcileRefreshWorkers();
        for(Set<Thread> threads:sourceThreads.values()) assertEquals(1,threads.size());
        for(Set<Thread> threads:downloadThreads.values()) assertEquals(1,threads.size());
    }

    @Test void disablingGlobalJobsStartsNeitherBackgroundNorSourceWorkers() {
        ReflectionTestUtils.setField(worker,"enabled",false);worker.start();
        verifyNoInteractions(queue,refresh,service);
    }
}
