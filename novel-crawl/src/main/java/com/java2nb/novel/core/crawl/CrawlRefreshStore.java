package com.java2nb.novel.core.crawl;

import com.java2nb.novel.entity.Book;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/** Independent refresh deadlines, sharing the original exclusive source/book lease. */
@Repository
public class CrawlRefreshStore {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${crawl.io.incremental-enabled:false}") private boolean ioVersioned;
    private final CrawlDiscoveryStore queue;
    private final AtomicInteger rotation=new AtomicInteger();
    private final Set<Integer> activeSources=ConcurrentHashMap.newKeySet();
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    private volatile long sourcesUntil;
    private volatile List<Integer> sources=List.of();
    private final Map<Integer,CachedStats> stats=new ConcurrentHashMap<>();
    private final Map<Integer,AtomicInteger> priorityTurns=new ConcurrentHashMap<>();
    private final Map<Integer,CachedDelay> scanDelays=new ConcurrentHashMap<>();
    private final Sweep serialSweep=new Sweep();
    private final Sweep completedSweep=new Sweep();
    private final Deque<Book> completedBatch=new ArrayDeque<>();
    private static final long RECONCILE_NANOS=Duration.ofMinutes(10).toNanos();

    /** A restart may repeat a bounded batch, but never loses durable jobs or checkpoints. */
    private static final class Sweep {
        Long after, ceiling;
        long nextRun;
        boolean pending;
    }
    private List<Long> nextWindow(Sweep sweep,int limit) {
        if(sweep.ceiling==null) {
            if(System.nanoTime()<sweep.nextRun) return List.of();
            sweep.ceiling=jdbc.queryForObject("SELECT MAX(id) FROM book",Long.class);
            if(sweep.ceiling==null) {sweep.nextRun=System.nanoTime()+RECONCILE_NANOS;return List.of();}
        }
        return sweep.after==null
            ?jdbc.queryForList("SELECT id FROM book WHERE id<=? ORDER BY id LIMIT "+limit,Long.class,sweep.ceiling)
            :jdbc.queryForList("SELECT id FROM book WHERE id>? AND id<=? ORDER BY id LIMIT "+limit,Long.class,sweep.after,sweep.ceiling);
    }
    private void advance(Sweep sweep,List<Long> ids,int limit) {
        sweep.pending=ids.size()==limit && !ids.getLast().equals(sweep.ceiling);
        if(sweep.pending) sweep.after=ids.getLast();
        else {sweep.after=null;sweep.ceiling=null;sweep.nextRun=System.nanoTime()+RECONCILE_NANOS;}
    }
    private static String placeholders(List<Long> ids) {return String.join(",",Collections.nCopies(ids.size(),"?"));}
    public synchronized boolean seedSweepPending() {return !enabledSources().isEmpty() && serialSweep.pending;}
    public synchronized boolean completedSweepPending() {return !enabledSources().isEmpty() && (completedSweep.pending || !completedBatch.isEmpty());}

    public CrawlRefreshStore(JdbcTemplate jdbc,CrawlDiscoveryStore queue) {this.jdbc=jdbc;this.queue=queue;}
    @PostConstruct public void initialize() {
        Boolean exists=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection->{
            String name=connection.getMetaData().storesUpperCaseIdentifiers()?"CRAWL_BOOK_REFRESH":"crawl_book_refresh";
            try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,name,new String[]{"TABLE"})) {return tables.next();}
        });
        if(!Boolean.TRUE.equals(exists))
            new ResourceDatabasePopulator(new ClassPathResource("db/crawler-priority-20261019.sql")).execute(jdbc.getDataSource());
        Boolean indexed=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection->{
            String table=connection.getMetaData().storesUpperCaseIdentifiers()?"CRAWL_DISCOVERY_JOB":"crawl_discovery_job";
            try(var indexes=connection.getMetaData().getIndexInfo(connection.getCatalog(),null,table,false,false)) {
                while(indexes.next()) if("ix_discovery_source_due".equalsIgnoreCase(indexes.getString("INDEX_NAME")))return true;
            }
            return false;
        });
        if(!Boolean.TRUE.equals(indexed)) jdbc.execute("CREATE INDEX ix_discovery_source_due ON crawl_discovery_job(source_id,job_status,next_attempt_at,id)");
    }
    private static Timestamp now() {return Timestamp.from(Instant.now());}
    public synchronized List<Integer> enabledSources() {
        if(System.nanoTime()>=sourcesUntil) {
            sources=jdbc.query("SELECT id FROM crawl_source WHERE source_status=1 ORDER BY id",(r,n)->r.getInt(1));
            sourcesUntil=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        }
        return sources;
    }

    /** Bounded seeding avoids thousands of per-book remote transactions and never touches book timestamps. */
    public synchronized int seedSerials(int limit) {
        if(limit<1 || limit>1000) throw new IllegalArgumentException("Invalid refresh seed limit");
        // A disabled source is not a reason to advance its durable reconciliation window.
        if(enabledSources().isEmpty()) return 0;
        List<Long> ids=nextWindow(serialSweep,limit);
        if(ids.isEmpty()) {if(serialSweep.ceiling!=null) advance(serialSweep,ids,limit);return 0;}
        var seedArgs=new ArrayList<Object>();
        Timestamp stamp=now();
        seedArgs.add(stamp);seedArgs.add(stamp);seedArgs.addAll(ids);
        int inserted=queue.queueTransaction("连载追更排期",()->jdbc.update(
            "INSERT INTO crawl_book_refresh(book_id,source_id,source_book_id,next_check_at,last_enqueued_at,observed_count,catalog_hash,priority_rank) "
            + "SELECT b.id,b.crawl_source_id,b.crawl_book_id,?,?,COALESCE(a.expected_count,0),a.catalog_hash,"
            + "CASE WHEN a.collection_status='COMPLETE' AND a.ready_count=a.expected_count AND a.expected_count>0 THEN 1 ELSE 2 END FROM book b "
            + "LEFT JOIN crawl_collection_state a ON a.book_id=b.id AND a.source_id=b.crawl_source_id AND a.source_book_id=b.crawl_book_id "
            + "LEFT JOIN crawl_book_refresh r ON r.book_id=b.id "
            + "WHERE b.id IN("+placeholders(ids)+") AND b.book_status=0 AND b.crawl_source_id IN(SELECT id FROM crawl_source WHERE source_status=1) "
            + "AND b.crawl_book_id IS NOT NULL AND (b.crawl_is_stop IS NULL OR b.crawl_is_stop=0) "
            + "AND (r.book_id IS NULL OR r.active_serial=0 OR r.source_id<>b.crawl_source_id OR r.source_book_id<>b.crawl_book_id) "
            + "ORDER BY b.last_index_update_time DESC,b.id LIMIT "+limit+" ON DUPLICATE KEY UPDATE "
            + "source_id=VALUES(source_id),source_book_id=VALUES(source_book_id),active_serial=1,next_check_at=VALUES(next_check_at),"
            + "last_enqueued_at=VALUES(last_enqueued_at),last_attempted_at=NULL,last_checked_at=NULL,last_saved_at=NULL,"
            + "observed_count=VALUES(observed_count),catalog_hash=VALUES(catalog_hash),priority_rank=VALUES(priority_rank),pending_from=NULL,unchanged_checks=0,failures=0",seedArgs.toArray()));
        // Separate transactions keep the lock order j -> r in claims and completions.
        var leaseArgs=new ArrayList<Object>();leaseArgs.add(stamp);leaseArgs.add(stamp);leaseArgs.add(stamp);leaseArgs.addAll(ids);
        queue.queueTransaction("补充追更租约",()->jdbc.update(
            "INSERT INTO crawl_discovery_job(source_id,source_book_id,cat_id,policy_hash,book_id,job_status,next_attempt_at,created_at,updated_at) "
            + "SELECT r.source_id,r.source_book_id,COALESCE(b.cat_id,1),'refresh',r.book_id,'UPDATE',?,?,? "
            + "FROM crawl_book_refresh r JOIN book b ON b.id=r.book_id "
            + "LEFT JOIN crawl_discovery_job j ON j.source_id=r.source_id AND j.source_book_id=r.source_book_id "
            + "WHERE r.book_id IN("+placeholders(ids)+") AND j.id IS NULL AND r.source_id IN(SELECT id FROM crawl_source WHERE source_status=1) LIMIT "+limit,
            leaseArgs.toArray()));
        // Advance only after both transactions succeed; a retry safely reconciles the same IDs.
        advance(serialSweep,ids,limit);
        return inserted;
    }

    /** Completed/unverified books retain the old completeness exclusion and background queue. */
    public synchronized List<Book> completedCandidates(Date before,int limit) {
        if(limit<1 || limit>1000) throw new IllegalArgumentException("Invalid completed candidate limit");
        if(enabledSources().isEmpty()) return List.of();
        if(completedBatch.isEmpty()) {
            List<Long> ids=nextWindow(completedSweep,500);
            if(ids.isEmpty()) {if(completedSweep.ceiling!=null) advance(completedSweep,ids,500);return List.of();}
            var args=new ArrayList<Object>();args.addAll(ids);args.add(new Timestamp(before.getTime()));
            completedBatch.addAll(jdbc.query("SELECT b.id FROM book b WHERE b.id IN("+placeholders(ids)+") AND b.book_status=1 "
            + "AND b.crawl_source_id IN(SELECT id FROM crawl_source WHERE source_status=1) "
            + "AND (b.crawl_is_stop IS NULL OR b.crawl_is_stop=0) AND (b.crawl_last_time IS NULL OR b.crawl_last_time<=?) "
            + "AND NOT EXISTS(SELECT 1 FROM crawl_collection_state a WHERE a.book_id=b.id AND a.source_id=b.crawl_source_id "
            + "AND a.source_book_id=b.crawl_book_id AND a.source_completed=1 AND a.collection_status='COMPLETE' "
            + "AND a.expected_count>0 AND a.ready_count=a.expected_count AND a.missing_count=0 AND a.unchecked_count=0 "
            + "AND a.failed_count=0 AND a.deferred_count=0"
            +(ioVersioned?" AND NOT EXISTS(SELECT 1 FROM crawl_book_io_state io WHERE io.book_id=b.id AND (io.checked_revision IS NULL OR io.checked_revision<>io.revision))":"")
            +") ORDER BY b.crawl_last_time,b.id",
            (r,n)->{var b=new Book();b.setId(r.getLong(1));return b;},args.toArray()));
            advance(completedSweep,ids,500);
        }
        var result=new ArrayList<Book>();
        while(result.size()<limit && !completedBatch.isEmpty()) result.add(completedBatch.removeFirst());
        return result;
    }

    public CrawlDiscoveryStore.Job claim() {
        List<Integer> enabled=enabledSources(); if(enabled.isEmpty()) return null;
        int start=Math.floorMod(rotation.getAndIncrement(),enabled.size());
        for(int i=0;i<enabled.size();i++) {
            int source=enabled.get((start+i)%enabled.size());
            CrawlDiscoveryStore.Job job=claimActiveSource(source);
            if(job!=null) return job;
        }
        return null;
    }

    /** A dedicated source worker must never borrow another source's tasks. */
    public CrawlDiscoveryStore.Job claimForSource(int source) {
        if(!enabledSources().contains(source)) return null;
        return claimActiveSource(source);
    }

    private CrawlDiscoveryStore.Job claimActiveSource(int source) {
        if(!activeSources.add(source)) return null;
        try {
            CrawlDiscoveryStore.Job job=queue.queueTransaction("领取连载目录",()->claimSource(source));
            if(job!=null) return job;
        } catch(RuntimeException e) {
            activeSources.remove(source);sessions.entrySet().removeIf(x->x.getValue().source==source);throw e;
        }
        activeSources.remove(source);
        return null;
    }
    private CrawlDiscoveryStore.Job claimSource(int source) {
        Timestamp stamp=now();
        // Read a small deadline-ordered candidate set without locking an entire status range.
        List<Object[]> candidates=new ArrayList<>();
        int turn=Math.floorMod(priorityTurns.computeIfAbsent(source,ignored->new AtomicInteger()).getAndIncrement(),6);
        int preferred=turn==5?2:turn==2||turn==4?1:0;
        var ranks=new LinkedHashSet<>(List.of(preferred,0,1,2));
        for(int rank:ranks) {
            candidates=jdbc.query("SELECT r.book_id,r.source_book_id FROM crawl_book_refresh r JOIN book b ON b.id=r.book_id "
            + "WHERE r.source_id=? AND r.active_serial=1 AND r.next_check_at<=? AND b.book_status=0 AND b.crawl_source_id=r.source_id "
            + "AND b.crawl_book_id=r.source_book_id AND (b.crawl_is_stop IS NULL OR b.crawl_is_stop=0) "
            + "AND r.source_id IN(SELECT id FROM crawl_source WHERE source_status=1) AND r.priority_rank=? "
            + "ORDER BY r.next_check_at,r.book_id LIMIT 8",(r,n)->new Object[]{r.getLong(1),r.getString(2)},source,stamp,rank);
            if(!candidates.isEmpty())break;
        }
        for(Object[] candidate:candidates) {
            long bookId=(Long)candidate[0];String sourceBook=(String)candidate[1];
            var jobs=jdbc.query("SELECT id,cat_id,job_status,next_attempt_at,consecutive_failures FROM crawl_discovery_job "
                + "WHERE source_id=? AND source_book_id=? AND (book_id IS NULL OR book_id=?) "
                + "AND (job_status<>'RUNNING' OR lease_until<=?) FOR UPDATE SKIP LOCKED",
                (r,n)->new Object[]{r.getLong(1),r.getInt(2),r.getString(3),r.getTimestamp(4),r.getInt(5)},source,sourceBook,bookId,stamp);
            if(jobs.isEmpty()) continue;
            var states=jdbc.query("SELECT observed_count,catalog_hash,pending_from,unchanged_checks,failures,priority_rank FROM crawl_book_refresh "
                + "WHERE book_id=? AND source_id=? AND source_book_id=? AND next_check_at<=? FOR UPDATE SKIP LOCKED",
                (r,n)->new Session(source,r.getInt(1),r.getString(2),r.getObject(3,Integer.class),r.getInt(4),r.getInt(5),r.getInt(6)),bookId,source,sourceBook,stamp);
            if(states.isEmpty()) continue;
            Object[] row=jobs.getFirst();String token=UUID.randomUUID().toString();
            var job=new CrawlDiscoveryStore.Job((Long)row[0],source,sourceBook,(Integer)row[1],bookId,token,(Integer)row[4]);
            Session session=states.getFirst();session.priorStatus=(String)row[2];session.priorDue=(Timestamp)row[3];
            jdbc.update("UPDATE crawl_discovery_job SET job_status='RUNNING',book_id=COALESCE(book_id,?),lease_token=?,lease_until=?,"
                + "attempts=attempts+1,updated_at=? WHERE id=?",bookId,token,Timestamp.from(Instant.now().plus(CrawlDiscoveryStore.LEASE)),stamp,job.id());
            jdbc.update("UPDATE crawl_book_refresh SET last_attempted_at=? WHERE book_id=?",stamp,bookId);
            sessions.put(token,session);return job;
        }
        return null;
    }
    public Session session(CrawlDiscoveryStore.Job job) {return Objects.requireNonNull(sessions.get(job.token()),"Unknown refresh lease");}
    public void finish(CrawlDiscoveryStore.Job job,boolean failed,Duration retryAfter) {
        Session s=session(job);
        try {
            queue.queueTransaction("提交连载检查",()->{
                var owner=jdbc.queryForList("SELECT id FROM crawl_discovery_job WHERE id=? AND job_status='RUNNING' AND lease_token=? FOR UPDATE",job.id(),job.token());
                if(owner.isEmpty()) return false;
                int failures=failed || s.checkedAt==null?Math.min(s.failures+1,10):0;
                Duration delay=failures>0?Duration.ofSeconds(Math.min(3600,60L<<Math.min(failures-1,6)))
                    :s.pendingFrom!=null?Duration.ofSeconds(5):s.yieldedTail?Duration.ofMinutes(30)
                    :Duration.ofMinutes(s.unchanged>=4?360:s.unchanged>=2?120:30);
                if(retryAfter!=null && retryAfter.compareTo(delay)>0) delay=retryAfter;
                String status=s.priorStatus;
                Timestamp due=s.priorDue;
                if(s.checkedAt!=null) {
                    if(s.complete) {status="COMPLETE";due=now();}
                    else if(!"RETRY".equals(status)) {
                        status="PARTIAL";
                        // A quick catalog check must not push an old backfill task to the
                        // end of its queue each time the serial is checked again.
                        if(due==null || due.after(Timestamp.from(Instant.now().plusSeconds(5))))
                            due=Timestamp.from(Instant.now().plusSeconds(5));
                    }
                }
                if("RUNNING".equals(status)) status="PARTIAL";
                jdbc.update("UPDATE crawl_discovery_job SET job_status=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,updated_at=? "
                    + "WHERE id=? AND lease_token=?",status,due,now(),job.id(),job.token());
                jdbc.update("UPDATE crawl_book_refresh SET active_serial=?,priority_rank=?,next_check_at=?,last_enqueued_at=?,last_checked_at=COALESCE(?,last_checked_at),"
                    + "last_saved_at=COALESCE(?,last_saved_at),observed_count=?,catalog_hash=?,pending_from=?,unchanged_checks=?,failures=? "
                    + "WHERE book_id=? AND source_id=? AND source_book_id=?",
                    s.sourceCompleted?0:1,s.pendingFrom!=null?0:s.yieldedTail?1:s.unchanged<2&&(s.rank<2||s.grew)?1:2,
                    Timestamp.from(Instant.now().plus(delay)),now(),s.checkedAt,s.savedAt,s.observed,s.hash,s.pendingFrom,s.unchanged,failures,
                    job.bookId(),job.sourceId(),job.sourceBookId());
                return true;
            });
        } finally {sessions.remove(job.token());activeSources.remove(job.sourceId());}
    }

    public static final class Session {
        final int source;
        int observed,unchanged,failures,rank;
        String hash,priorStatus;
        Integer pendingFrom;
        Timestamp checkedAt,savedAt,priorDue;
        boolean complete,sourceCompleted,grew,yieldedTail;
        Session(int source,int count,String hash,Integer pending,int unchanged,int failures,int rank) {
            this.source=source;this.observed=count;this.hash=hash;this.pendingFrom=pending;this.unchanged=unchanged;this.failures=failures;this.rank=rank;
        }
        public int observe(int count,String catalogHash,IntFunction<String> prefixHash) {
            return observe(count,catalogHash,prefixHash,count);
        }
        public boolean hasCatalogHistory() {return observed>0 && hash!=null;}
        public int observe(int count,String catalogHash,IntFunction<String> prefixHash,int bootstrapFrom) {
            boolean append=observed>0 && observed<=count && Objects.equals(hash,prefixHash.apply(observed));
            boolean changed=!Objects.equals(hash,catalogHash);
            grew=append && count>observed;
            if(!append) pendingFrom=null; // A reshuffle belongs to guarded background reconciliation.
            else if(count>observed && pendingFrom==null) pendingFrom=observed;
            // Legacy books without an audit can still have a fully matching local
            // prefix followed by a small new tail. Do not postpone those new chapters
            // until the source publishes yet another chapter after bootstrap.
            if(!hasCatalogHistory() && bootstrapFrom>=0 && bootstrapFrom<count) {pendingFrom=bootstrapFrom;grew=true;}
            unchanged=changed?0:Math.min(unchanged+1,10);
            observed=count;hash=catalogHash;checkedAt=now();
            return pendingFrom==null?count:pendingFrom;
        }
        public void catalogFinished(CrawlCollectionStore.Audit audit) {
            complete=audit.complete();
            sourceCompleted=audit.sourceCompleted;
            if(pendingFrom!=null) {
                int previous=pendingFrom;
                int first=audit.ready.nextClearBit(pendingFrom);
                pendingFrom=first>=audit.expected?null:first;
                // Empty/placeholder tails are still incomplete. Repeated checks of an
                // unchanged tail with no verified or saved progress belong to background
                // repair, rather than monopolizing this source's five-second fast lane.
                // Only release the fast frontier; retain all audit bits and queue state.
                if(pendingFrom!=null && pendingFrom==previous && savedAt==null && !grew && unchanged>=2) {
                    pendingFrom=null;
                    yieldedTail=true;
                }
            }
        }
        public void saved() {savedAt=now();unchanged=0;}
    }

    public record Stats(long observedAt,long refreshWaiting,long discoveryWaiting,long backfillWaiting,
                        Long oldestRefreshDueAt,Long lastEnqueuedAt,Long lastCheckedAt,Long lastSavedAt) { }
    private record CachedStats(long until,Stats value) { }
    public Stats stats(int source) {
        CachedStats cached=stats.get(source);long clock=System.nanoTime();
        if(cached!=null && cached.until>clock) return cached.value;
        synchronized(stats) {
            cached=stats.get(source);if(cached!=null && cached.until>System.nanoTime()) return cached.value;
            var times=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN active_serial=1 AND next_check_at<=? THEN 1 ELSE 0 END),0) AS waiting,"
                + "MIN(CASE WHEN active_serial=1 AND next_check_at<=? THEN next_check_at ELSE NULL END) AS oldest,MAX(last_enqueued_at) AS enqueued,"
                + "MAX(last_checked_at) AS checked,MAX(last_saved_at) AS saved FROM crawl_book_refresh WHERE source_id=?",
                (r,n)->new Stats(System.currentTimeMillis(),r.getLong("waiting"),0,0,
                    millis(r.getTimestamp("oldest")),millis(r.getTimestamp("enqueued")),millis(r.getTimestamp("checked")),millis(r.getTimestamp("saved"))),
                now(),now(),source);
            var counts=jdbc.query("SELECT job_status,COUNT(*) FROM crawl_discovery_job WHERE source_id=? "
                + "AND job_status IN('PENDING','PARTIAL','UPDATE') GROUP BY job_status",(r,n)->Map.entry(r.getString(1),r.getLong(2)),source);
            long discovery=0,backfill=0;for(var count:counts) {if(count.getKey().equals("PENDING"))discovery+=count.getValue();else backfill+=count.getValue();}
            var value=new Stats(times.observedAt,times.refreshWaiting,discovery,backfill,
                times.oldestRefreshDueAt,times.lastEnqueuedAt,times.lastCheckedAt,times.lastSavedAt);
            stats.put(source,new CachedStats(System.nanoTime()+Duration.ofSeconds(30).toNanos(),value));return value;
        }
    }
    private static Long millis(Timestamp value) {return value==null?null:value.getTime();}
    public Duration scanPageDelay(int source) {
        synchronized(scanDelays) {
            CachedDelay cached=scanDelays.get(source);
            if(cached!=null && cached.until>System.nanoTime()) return cached.delay;
            // Scheduling needs only a threshold, never the exact size of a six-figure queue.
            boolean due=!jdbc.queryForList("SELECT book_id FROM crawl_book_refresh WHERE source_id=? "
                + "AND active_serial=1 AND next_check_at<=? LIMIT 1",Long.class,source,now()).isEmpty();
            boolean backlog=!due && jdbc.queryForList("SELECT id FROM crawl_discovery_job WHERE source_id=? "
                + "AND job_status IN('PARTIAL','UPDATE') LIMIT 101",Long.class,source).size()>100;
            Duration delay=Duration.ofSeconds(due || backlog?30:3);
            scanDelays.put(source,new CachedDelay(System.nanoTime()+Duration.ofSeconds(30).toNanos(),delay));
            return delay;
        }
    }
    private record CachedDelay(long until,Duration delay) { }
    public Duration scanCycleDelay() {return Duration.ofHours(6);}
}
