package com.java2nb.novel.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** 同一源站串行请求，并在请求开始之间保留间隔；等待可被停止采集操作打断。 */
final class OriginRequestGate {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed=lock.newCondition();
    private record Ticket(int priority,long order) { }
    private final List<Ticket> waiting=new ArrayList<>();
    private boolean active;
    private long sequence;
    private int refreshStreak;
    private final long intervalNanos;
    private long nextRequestNanos;

    OriginRequestGate(int intervalMillis) {
        intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis);
    }

    void enter() throws InterruptedException {
        lock.lockInterruptibly();
        Ticket ticket=new Ticket(CrawlRequestContext.priority(),sequence++);
        waiting.add(ticket);
        try {
            while(true) {
                if(!active && next()==ticket) {
                    long remaining=nextRequestNanos-System.nanoTime();
                    if(remaining<=0) {
                        waiting.remove(ticket);active=true;
                        refreshStreak=ticket.priority==0?refreshStreak+1:0;
                        nextRequestNanos=System.nanoTime()+intervalNanos;
                        return;
                    }
                    changed.awaitNanos(remaining);
                } else changed.await();
            }
        } catch (InterruptedException e) {
            waiting.remove(ticket);changed.signalAll();
            throw e;
        } finally {lock.unlock();}
    }

    private Ticket next() {
        // A bounded high-priority burst preserves occasional progress for backfill/scans.
        if(refreshStreak>=8) {
            Ticket oldest=waiting.stream().filter(t->t.priority>0).min(Comparator.comparingLong(Ticket::order)).orElse(null);
            if(oldest!=null)return oldest;
        }
        return waiting.stream().min(Comparator.comparingInt(Ticket::priority).thenComparingLong(Ticket::order)).orElse(null);
    }

    void leave() {
        lock.lock();
        try {active=false;changed.signalAll();} finally {lock.unlock();}
    }
}
