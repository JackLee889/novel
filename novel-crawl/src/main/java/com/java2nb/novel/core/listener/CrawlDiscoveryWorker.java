package com.java2nb.novel.core.listener;

import com.java2nb.novel.core.crawl.CrawlBookBusyException;
import com.java2nb.novel.core.crawl.CrawlDiscoveryStore;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.core.crawl.CrawlRefreshStore;
import com.java2nb.novel.utils.CrawlRequestContext;
import com.java2nb.novel.utils.CrawlAccessChallengeException;
import java.time.Duration;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** Each enabled source owns one refresh worker and one background download worker. */
@Component
@Slf4j
public class CrawlDiscoveryWorker {
    private final CrawlDiscoveryStore store;
    private final CrawlServiceImpl service;
    private final List<Worker> backgroundWorkers = new ArrayList<>();
    private final Map<Integer,Worker> refreshWorkers = new HashMap<>();
    private final Map<Integer,Worker> downloadWorkers = new HashMap<>();
    private ScheduledExecutorService heartbeats;
    private ScheduledExecutorService sourceSupervisor;
    private volatile boolean stopping;
    private boolean started;
    @Value("${crawl.jobs.enabled:true}") private boolean enabled = true;
    // Used only when the refresh store is absent (standalone legacy consumers).
    @Value("${crawl.discovery.threads:2}") private int threadCount = 2;
    private CrawlRefreshStore refreshStore;
    private com.java2nb.novel.core.io.BackgroundIoGate ioGate=com.java2nb.novel.core.io.BackgroundIoGate.disabled();
    @org.springframework.beans.factory.annotation.Autowired
    public void setIoGate(com.java2nb.novel.core.io.BackgroundIoGate value){ioGate=value;}
    @org.springframework.beans.factory.annotation.Autowired
    public void setRefreshStore(CrawlRefreshStore value) {refreshStore=value;}

    public CrawlDiscoveryWorker(CrawlDiscoveryStore store,CrawlServiceImpl service) { this.store=store; this.service=service; }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if(!enabled || stopping || started) return;
        if(refreshStore==null && (threadCount<1 || threadCount>4)) throw new IllegalArgumentException("Background workers must be between 1 and 4");
        started=true;
        heartbeats=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"crawl-discovery-lease");t.setDaemon(true);return t;});
        for(int i=0;refreshStore==null && i<threadCount;i++) {
            Worker worker=new Worker(null,false,"crawl-discovery-"+i);
            backgroundWorkers.add(worker);worker.thread.start();
        }
        if(refreshStore!=null) {
            sourceSupervisor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"crawl-refresh-sources");t.setDaemon(true);return t;});
            sourceSupervisor.scheduleWithFixedDelay(this::reconcileRefreshWorkers,0,5,TimeUnit.SECONDS);
        }
    }

    /** Keep a cancelled worker registered until it exits, including clients slow to react to interruption. */
    void reconcileRefreshWorkers() {
        if(stopping || refreshStore==null) return;
        try {
            Set<Integer> sources=new HashSet<>(refreshStore.enabledSources());
            synchronized(this) {
                if(stopping || !started) return;
                refreshWorkers.entrySet().removeIf(entry->!entry.getValue().thread.isAlive());
                downloadWorkers.entrySet().removeIf(entry->!entry.getValue().thread.isAlive());
                refreshWorkers.forEach((source,worker)->{if(!sources.contains(source)) worker.cancel();});
                downloadWorkers.forEach((source,worker)->{if(!sources.contains(source)) worker.cancel();});
                for(int source:sources) if(!refreshWorkers.containsKey(source)) {
                    Worker worker=new Worker(source,true,"crawl-refresh-source-"+source);
                    refreshWorkers.put(source,worker);worker.thread.start();
                }
                for(int source:sources) if(!downloadWorkers.containsKey(source)) {
                    Worker worker=new Worker(source,false,"crawl-download-source-"+source);
                    downloadWorkers.put(source,worker);worker.thread.start();
                }
            }
        } catch(Exception failure) {
            log.warn("刷新书源追更与下载线程失败，保留现有工作线程",failure);
        }
    }

    private final class Worker {
        final Integer source;
        final boolean refresh;
        final Thread thread;
        volatile boolean cancelled;
        Worker(Integer source,boolean refresh,String name) {
            this.source=source;this.refresh=refresh;thread=new Thread(()->run(this),name);thread.setDaemon(true);
        }
        void cancel() {cancelled=true;thread.interrupt();}
    }

    private void run(Worker worker) {
        boolean refresh=worker.refresh;
        boolean preferResume=false;
        while(!stopping && !worker.cancelled && !Thread.currentThread().isInterrupted()) {
            CrawlDiscoveryStore.Job job=null; ScheduledFuture<?> heartbeat=null;
            java.util.concurrent.atomic.AtomicBoolean leaseActive=new java.util.concurrent.atomic.AtomicBoolean(true);
            try {
                if(ioGate.paused()){Thread.sleep(2000);continue;}
                job=refresh?refreshStore.claimForSource(worker.source):refreshStore==null?store.claim(preferResume)
                    :store.claimBackground(preferResume,List.of(worker.source));
                if(job!=null && worker.source!=null && job.sourceId()!=worker.source)
                    throw new IllegalStateException("Source worker received another source's job");
                preferResume=!preferResume;
                if(job==null) { Thread.sleep(refresh?1000:5000); continue; }
                if(stopping || worker.cancelled) {
                    Thread.interrupted();finish(job,refresh,"PENDING",null,"Worker stopped; resumable",false);return;
                }
                CrawlDiscoveryStore.Job owned=job; Thread owner=Thread.currentThread();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(refresh?90:180);
                heartbeat=heartbeats.scheduleAtFixedRate(()->{
                    try {
                        CrawlSource source=service.queryCrawlSource(owned.sourceId());
                        boolean stop=stopping || worker.cancelled || System.nanoTime()>=deadline || source==null
                            || !Byte.valueOf((byte)1).equals(source.getSourceStatus()) || !store.renew(owned);
                        synchronized(leaseActive) { if(stop && leaseActive.get()) owner.interrupt(); }
                    } catch(Exception e) {
                        log.warn("书目队列租约续期失败：jobId={}",owned.id());
                        synchronized(leaseActive) { if(leaseActive.get()) owner.interrupt(); }
                    }
                },30,30,TimeUnit.SECONDS);
                CrawlSource source=service.queryCrawlSource(job.sourceId());
                if(source==null || !Byte.valueOf((byte)1).equals(source.getSourceStatus())) {
                    finish(job,refresh,"PENDING",null,null,false);job=null;continue;
                }
                RuleBean rule=new ObjectMapper().readValue(source.getCrawlRule(),RuleBean.class);
                com.java2nb.novel.service.impl.CrawlServiceImpl.DiscoveryResult result;
                if(refresh) result=service.refreshExistingBook(job,rule,refreshStore);
                else try(var context=CrawlRequestContext.open(CrawlRequestContext.Kind.BACKGROUND)) {result=service.parseDiscoveredBook(job,rule);}
                finish(job,refresh,result.outcome(),result.bookId(),null,"RETRY".equals(result.outcome()));
                log.info("书目队列进度：jobId={}, sourceId={}, outcome={}, bookId={}",job.id(),job.sourceId(),result.outcome(),result.bookId());
                job=null;
            } catch(com.java2nb.novel.core.io.BackgroundIoGate.BusyException busy) {
                // Admission pressure is not a source failure. Keep the durable book checkpoint.
                Thread.interrupted();
                if(job!=null)try{finish(job,refresh,"PARTIAL",null,"Background I/O busy; resumable",false);}
                    catch(Exception failure){log.warn("I/O admission return waits for lease expiry: jobId={}",job.id());}
                try{Thread.sleep(ThreadLocalRandom.current().nextLong(3000,7001));}
                catch(InterruptedException interrupted){if(stopping||worker.cancelled)return;}
            } catch(CrawlBookBusyException busy) {
                if(job!=null) try { finish(job,refresh,"PARTIAL",null,"Book busy",false); }
                    catch(Exception e) { log.warn("忙碌任务等待租约过期后恢复：jobId={}",job.id()); }
            } catch(InterruptedException interrupted) {
                // Clear interruption only while returning the owned lease to the durable queue.
                Thread.interrupted();
                if(job!=null) try { finish(job,refresh,"PENDING",null,"Interrupted; resumable",false); }
                    catch(Exception e) { log.warn("中断任务等待租约过期后恢复：jobId={}",job.id()); }
                if(stopping || worker.cancelled) { Thread.currentThread().interrupt(); return; }
            } catch(Exception e) {
                if(job!=null) try {
                    if(stopping || worker.cancelled) finish(job,refresh,"PENDING",null,"Worker stopped; resumable",false);
                    else if(refresh && e instanceof CrawlAccessChallengeException challenge)
                        refreshStore.finish(job,true,Duration.ofSeconds(challenge.getRetryAfterSeconds()));
                    else {
                        String database = com.java2nb.novel.utils.CrawlPersistenceErrors.describe(e);
                        finish(job,refresh,"RETRY",null,e instanceof com.java2nb.novel.utils.CrawlMetadataException
                            ? e.getMessage() : database != null
                            ? com.java2nb.novel.utils.CrawlPersistenceErrors.context(database,job.sourceBookId(),null)
                            : e.getClass().getSimpleName(),true);
                    }
                }
                    catch(Exception saveFailure) { log.warn("书目任务结果保存失败，将按租约恢复：jobId={}",job.id()); }
                log.warn("书目队列任务失败：jobId={}",job==null?null:job.id(),e);
                if(stopping || worker.cancelled) return;
                try { Thread.sleep(5000); } catch(InterruptedException interrupted) { if(stopping || worker.cancelled) return; }
            } finally {
                synchronized(leaseActive) { leaseActive.set(false); }
                if(heartbeat!=null) heartbeat.cancel(false);
                // A delayed lease check must not leave an unrelated following job interrupted.
                if(!stopping && !worker.cancelled) Thread.interrupted();
            }
        }
    }

    private void finish(CrawlDiscoveryStore.Job job,boolean refresh,String outcome,Long bookId,String error,boolean failed) {
        if(refresh) refreshStore.finish(job,failed,null);
        else store.finish(job,outcome,bookId,error,failed);
    }

    @PreDestroy
    public void stop() {
        List<Worker> workers;
        synchronized(this) {
            stopping=true;
            if(sourceSupervisor!=null) sourceSupervisor.shutdownNow();
            workers=new ArrayList<>(backgroundWorkers);workers.addAll(refreshWorkers.values());workers.addAll(downloadWorkers.values());
            workers.forEach(Worker::cancel);
            if(heartbeats!=null) heartbeats.shutdownNow();
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        for(Worker worker:workers) {
            long remaining=deadline-System.nanoTime(); if(remaining<=0) break;
            try { worker.thread.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining))); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
}
