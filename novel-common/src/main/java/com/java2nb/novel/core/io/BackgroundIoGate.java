package com.java2nb.novel.core.io;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cross-process admission, using Redis time and owner-specific expiring leases. */
@Component
public class BackgroundIoGate implements AutoCloseable {
    public static final String PREFIX="novel:background-io:{v1}:";
    private static final List<String> KEYS=List.of(PREFIX+"active",PREFIX+"catalog",PREFIX+"pause");
    private static final DefaultRedisScript<Long> ACQUIRE=new DefaultRedisScript<>("""
        local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000)
        redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
        redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now)
        if redis.call('EXISTS',KEYS[3])==1 then return -1 end
        if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[2]) then return 0 end
        if ARGV[4]=='1' and redis.call('ZCARD',KEYS[2])>=1 then return 0 end
        redis.call('ZADD',KEYS[1],now+tonumber(ARGV[3]),ARGV[1])
        redis.call('PEXPIRE',KEYS[1],tonumber(ARGV[3])*2)
        if ARGV[4]=='1' then redis.call('ZADD',KEYS[2],now+tonumber(ARGV[3]),ARGV[1]); redis.call('PEXPIRE',KEYS[2],tonumber(ARGV[3])*2) end
        return 1
        """,Long.class);
    private static final DefaultRedisScript<Long> RENEW=new DefaultRedisScript<>("""
        local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000)
        local expiry=redis.call('ZSCORE',KEYS[1],ARGV[1])
        if not expiry or tonumber(expiry)<=now then return 0 end
        redis.call('ZADD',KEYS[1],now+tonumber(ARGV[2]),ARGV[1]); redis.call('PEXPIRE',KEYS[1],tonumber(ARGV[2])*2)
        if redis.call('ZSCORE',KEYS[2],ARGV[1]) then redis.call('ZADD',KEYS[2],now+tonumber(ARGV[2]),ARGV[1]); redis.call('PEXPIRE',KEYS[2],tonumber(ARGV[2])*2) end
        return 1
        """,Long.class);
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>(
        "redis.call('ZREM',KEYS[2],ARGV[1]); return redis.call('ZREM',KEYS[1],ARGV[1])",Long.class);
    private final StringRedisTemplate redis;
    @Value("${background.io.enabled:false}") private boolean enabled;
    @Value("${background.io.max-concurrent:2}") private int capacity=2;
    @Value("${background.io.wait-ms:15000}") private long waitMillis=15000;
    private final long leaseMillis=120000;
    private final ThreadLocal<Permit> owned=new ThreadLocal<>();
    private final ScheduledExecutorService renewals=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"background-io-lease");t.setDaemon(true);return t;});
    public BackgroundIoGate(StringRedisTemplate redis) { this.redis=redis; }
    public static BackgroundIoGate disabled() { return new BackgroundIoGate(null); }
    public boolean paused() {
        if(!enabled)return false;
        try{return Boolean.TRUE.equals(redis.hasKey(KEYS.get(2)));}
        catch(RuntimeException unavailable){return true;}
    }
    public Permit acquire(boolean catalog) {
        Permit current=owned.get();
        if(current!=null){current.check();if(catalog&&!current.catalog)throw new IllegalStateException("Cannot upgrade an active I/O permit");return new Permit(current);}
        if(!enabled)return new Permit((Permit)null);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Wait for background admission before opening a transaction");
        String token=UUID.randomUUID().toString();long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(waitMillis);
        do {
            if(Thread.currentThread().isInterrupted())throw new BusyException("Background admission interrupted");
            Long result;
            try{result=redis.execute(ACQUIRE,KEYS,token,Integer.toString(Math.max(1,capacity)),Long.toString(leaseMillis),catalog?"1":"0");}
            catch(RuntimeException unavailable){throw new BusyException("Background admission unavailable",unavailable);}
            if(Long.valueOf(1).equals(result)){
                var permit=new Permit(token,catalog);owned.set(permit);
                permit.renewal=renewals.scheduleWithFixedDelay(permit::renew,20,20,TimeUnit.SECONDS);return permit;
            }
            if(Long.valueOf(-1).equals(result))throw new BusyException("Background work is draining");
            try{Thread.sleep(ThreadLocalRandom.current().nextLong(80,161));}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusyException("Background admission interrupted",e);}
        }while(System.nanoTime()<deadline);
        throw new BusyException("Background admission timed out");
    }
    public final class Permit implements AutoCloseable {
        private final String token;
        private final boolean catalog;
        private final Permit parent;
        private final AtomicBoolean lost=new AtomicBoolean();
        private ScheduledFuture<?> renewal;
        private boolean closed;
        private Permit(String token,boolean catalog){this.token=token;this.catalog=catalog;this.parent=null;}
        private Permit(Permit parent){this.token=null;this.catalog=false;this.parent=parent;}
        private void renew(){try{if(!Long.valueOf(1).equals(redis.execute(RENEW,KEYS,token,Long.toString(leaseMillis))))lost.set(true);}catch(RuntimeException failure){lost.set(true);}}
        public void check(){if(parent!=null)parent.check();if(lost.get()||closed)throw new BusyException("Background permit expired");if(Thread.currentThread().isInterrupted())throw new BusyException("Background operation interrupted");}
        @Override public void close(){if(closed)return;closed=true;if(token==null)return;if(renewal!=null)renewal.cancel(false);owned.remove();try{redis.execute(RELEASE,KEYS,token);}catch(RuntimeException ignored){/* bounded lease expiry releases this owner only */}}
    }
    public static final class BusyException extends RuntimeException {
        public BusyException(String message){super(message);}
        public BusyException(String message,Throwable cause){super(message,cause);}
    }
    @PreDestroy public void close(){renewals.shutdownNow();}
}
