package com.java2nb.novel.core.crawl;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.java2nb.novel.entity.Book;
import lombok.extern.slf4j.Slf4j;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Durable pagination and deduplicated jobs. Page advancement and its jobs share one transaction. */
@Repository
@Slf4j
public class CrawlDiscoveryStore {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${crawl.io.incremental-enabled:false}") private boolean ioVersioned;
    private final TransactionTemplate transaction;
    public record Cursor(int sourceId,int catId,String hash,int page,int total,long revision,Instant due) { }
    public record Job(long id,int sourceId,String sourceBookId,int catId,Long bookId,String token,int failures) { }
    public static final Duration LEASE = Duration.ofMinutes(10);
    private static final String[] CLAIM_STATES={"PENDING","UPDATE","RETRY","FILTERED","RUNNING","PARTIAL"};
    private static final int MAX_LOCK_ATTEMPTS=5;
    private final java.util.concurrent.atomic.AtomicInteger claimRotation=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger sourceRotation=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ConcurrentMap<Integer,java.util.concurrent.atomic.AtomicInteger> backgroundRotations=
        new java.util.concurrent.ConcurrentHashMap<>();

    public CrawlDiscoveryStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(manager);
        // Queue range scans must not retain next-key/gap locks between candidate states.
        // Each retry owns a fresh transaction, even if a future caller has an outer transaction.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    <T> T queueTransaction(String operation, Supplier<T> action) {
        for(int attempt=1;;attempt++) {
            try {
                return transaction.execute(status -> action.get());
            } catch(PessimisticLockingFailureException conflict) {
                // TransactionTemplate has rolled the entire failed attempt back before this catch.
                if(attempt>=MAX_LOCK_ATTEMPTS || Thread.currentThread().isInterrupted()) throw conflict;
                long base=50L << (attempt-1);
                long pause=ThreadLocalRandom.current().nextLong(base,base*2+1);
                log.warn("书目队列锁冲突，事务已回滚：operation={}, {}ms后重试（{}/{}）",operation,pause,attempt,MAX_LOCK_ATTEMPTS);
                try { pauseBeforeRetry(pause); }
                catch(InterruptedException stopped) { Thread.currentThread().interrupt();throw conflict; }
            }
        }
    }

    protected void pauseBeforeRetry(long millis) throws InterruptedException { Thread.sleep(millis); }

    public Cursor cursor(int sourceId,int catId,String hash) {
        var rows=jdbc.query("SELECT next_page,total_pages,revision,next_scan_at FROM crawl_discovery_cursor "
            + "WHERE source_id=? AND cat_id=? AND rule_hash=?",(r,n)->new Cursor(sourceId,catId,hash,
                r.getInt(1),r.getInt(2),r.getLong(3),r.getTimestamp(4).toInstant()),sourceId,catId,hash);
        if(!rows.isEmpty()) return rows.getFirst();
        Timestamp now=Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        try { queueTransaction("初始化分类",()->jdbc.update("INSERT INTO crawl_discovery_cursor(source_id,cat_id,rule_hash,next_page,total_pages,revision,next_scan_at,updated_at) "
            + "VALUES(?,?,?,1,1,0,?,?)",sourceId,catId,hash,now,now)); }
        catch(DuplicateKeyException anotherScanner) { return cursor(sourceId,catId,hash); }
        return new Cursor(sourceId,catId,hash,1,1,0,now.toInstant());
    }

    public boolean commitPage(Cursor cursor,int totalPages,List<String> sourceBooks,String policyHash,Duration cycleDelay) {
        if(totalPages<cursor.page() || totalPages>100000) throw new IllegalArgumentException("Invalid category page count");
        // Never silently truncate a malformed ID and accidentally bind two books to the same job.
        List<String> ids=new LinkedHashSet<>(sourceBooks).stream().toList();
        if(ids.stream().anyMatch(id->id==null || id.isBlank() || id.length()>128)) throw new IllegalArgumentException("Invalid source book ID");
        List<String> ordered=ids.stream().sorted().toList();
        return Boolean.TRUE.equals(queueTransaction("分类入队",()->{
            Long revision=jdbc.queryForObject("SELECT revision FROM crawl_discovery_cursor WHERE source_id=? AND cat_id=? AND rule_hash=? FOR UPDATE",
                Long.class,cursor.sourceId(),cursor.catId(),cursor.hash());
            if(!Objects.equals(revision,cursor.revision())) return false;
            Timestamp now=Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            for(int from=0;from<ordered.size();from+=100) {
                List<String> batch=ordered.subList(from,Math.min(from+100,ordered.size()));
                List<Object> lookup=new ArrayList<>();lookup.add(cursor.sourceId());lookup.addAll(batch);lookup.add(policyHash);
                // Most rescanned books already have this policy. A non-locking read avoids an
                // otherwise redundant duplicate-key UPDATE and its exclusive record/gap locks.
                Set<String> unchanged=new HashSet<>(jdbc.query("SELECT source_book_id FROM crawl_discovery_job WHERE source_id=? "
                    + "AND source_book_id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+") AND policy_hash=?",
                    (r,n)->r.getString(1),lookup.toArray()));
                List<String> writes=batch.stream().filter(id->!unchanged.contains(id)).toList();
                if(writes.isEmpty()) continue;
                List<Object> parameters=new ArrayList<>(writes.size()*7);
                for(String id:writes) Collections.addAll(parameters,cursor.sourceId(),id,cursor.catId(),policyHash,now,now,now);
                // Terminal COMPLETE/EXISTING jobs stay terminal. Filtered books are periodically
                // rechecked, or immediately reconsidered after a filter/rule change.
                jdbc.update("INSERT INTO crawl_discovery_job(source_id,source_book_id,cat_id,policy_hash,next_attempt_at,created_at,updated_at) "
                    + "VALUES" + String.join(",",Collections.nCopies(writes.size(),"(?,?,?,?,?,?,?)")) + " ON DUPLICATE KEY UPDATE "
                    + "next_attempt_at=CASE WHEN job_status='FILTERED' AND policy_hash<>VALUES(policy_hash) THEN VALUES(next_attempt_at) ELSE next_attempt_at END,"
                    + "policy_hash=VALUES(policy_hash)",parameters.toArray());
            }
            boolean end=cursor.page()>=totalPages;
            jdbc.update("UPDATE crawl_discovery_cursor SET next_page=?,total_pages=?,revision=revision+1,next_scan_at=?,updated_at=? "
                + "WHERE source_id=? AND cat_id=? AND rule_hash=?",end?1:cursor.page()+1,totalPages,
                Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plus(end?cycleDelay:Duration.ZERO)),now,cursor.sourceId(),cursor.catId(),cursor.hash());
            return true;
        }));
    }

    public Job claim() {
        return claim(false);
    }

    /** Persist the update and its rotation timestamp together; do not steal active leases or reset retries. */
    public boolean enqueueExistingBook(Book book, String policyHash) {
        Objects.requireNonNull(book.getId());
        Objects.requireNonNull(book.getCrawlSourceId());
        if (book.getCrawlBookId() == null || book.getCrawlBookId().isBlank()) return false;
        return Boolean.TRUE.equals(queueTransaction("已有书入队",() -> {
            var current = jdbc.query("SELECT crawl_source_id,crawl_book_id,crawl_is_stop,book_status FROM book WHERE id=? FOR UPDATE",
                (r,n) -> new Object[]{r.getObject(1,Integer.class),r.getString(2),r.getObject(3,Integer.class),r.getObject(4,Byte.class)},book.getId());
            if (current.isEmpty()) return false;
            Object[] row = current.getFirst();
            if (!Objects.equals(row[0],book.getCrawlSourceId()) || !Objects.equals(row[1],book.getCrawlBookId())
                || Integer.valueOf(1).equals(row[2])) return false;
            book.setBookStatus((Byte)row[3]);
            if (isVerifiedCompleted(book)) return false;
            if (jdbc.queryForObject("SELECT COUNT(*) FROM crawl_source WHERE id=? AND source_status=1",Integer.class,book.getCrawlSourceId()) == 0) return false;
            var existing = jdbc.queryForList("SELECT job_status,book_id FROM crawl_discovery_job WHERE source_id=? AND source_book_id=? FOR UPDATE",
                book.getCrawlSourceId(),book.getCrawlBookId());
            if (!existing.isEmpty()) {
                Map<String,Object> job = existing.getFirst();
                if ("RUNNING".equals(job.get("job_status"))) return false;
                Object knownId=job.get("book_id");
                if (knownId instanceof Number n && n.longValue()!=book.getId())
                    throw new IllegalStateException("Source book is bound to a different local book");
            }
            Timestamp now=Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            jdbc.update("INSERT INTO crawl_discovery_job(source_id,source_book_id,cat_id,policy_hash,book_id,job_status,next_attempt_at,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,'UPDATE',?,?,?) ON DUPLICATE KEY UPDATE "
                + "next_attempt_at=CASE WHEN job_status IN('PENDING','COMPLETE','EXISTING','FILTERED') THEN VALUES(next_attempt_at) ELSE next_attempt_at END,"
                + "job_status=CASE WHEN job_status IN('PENDING','COMPLETE','EXISTING','FILTERED') THEN 'UPDATE' ELSE job_status END,"
                + "book_id=COALESCE(book_id,VALUES(book_id)),updated_at=VALUES(updated_at)",
                book.getCrawlSourceId(),book.getCrawlBookId(),book.getCatId()==null?1:book.getCatId(),policyHash,book.getId(),now,now,now);
            jdbc.update("UPDATE book SET crawl_last_time=? WHERE id=?",now,book.getId());
            return true;
        }));
    }

    /** Counter evidence must belong to this current source identity; unknown is never complete. */
    public boolean isVerifiedCompleted(Book book) {
        if (!Byte.valueOf((byte)1).equals(book.getBookStatus())) return false;
        return jdbc.queryForObject("SELECT COUNT(*) FROM crawl_collection_state WHERE book_id=? AND source_id=? AND source_book_id=? "
            + "AND source_completed=1 AND collection_status='COMPLETE' AND expected_count>0 AND ready_count=expected_count "
            + "AND missing_count=0 AND unchecked_count=0 AND failed_count=0 AND deferred_count=0"
            +(ioVersioned?" AND NOT EXISTS(SELECT 1 FROM crawl_book_io_state io WHERE io.book_id=crawl_collection_state.book_id AND (io.checked_revision IS NULL OR io.checked_revision<>io.revision))":""),Integer.class,
            book.getId(),book.getCrawlSourceId(),book.getCrawlBookId()) == 1;
    }

    public Job claim(boolean preferResume) {
        return queueTransaction("领取任务",()->{
            Timestamp now=Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            List<Job> jobs=List.of();
            if(preferResume) jobs=claimCandidate("PARTIAL",now);
            if(jobs.isEmpty()) {
                // A preferred resume must not consume a rotation turn. With an even number
                // of states, alternating preference could otherwise skip UPDATE forever.
                int start=Math.floorMod(claimRotation.getAndIncrement(),CLAIM_STATES.length);
                // Separate status ranges preserve index order and LIMIT 1 without a global sort.
                for(int i=0;jobs.isEmpty()&&i<CLAIM_STATES.length;i++) {
                    String state=CLAIM_STATES[(start+i)%CLAIM_STATES.length];
                    if(preferResume&&"PARTIAL".equals(state)) continue;
                    jobs=claimCandidate(state,now);
                }
            }
            if(jobs.isEmpty()) return null;
            Job job=jobs.getFirst();
            jdbc.update("UPDATE crawl_discovery_job SET job_status='RUNNING',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=? WHERE id=?",
                job.token(),Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plus(LEASE)),now,job.id());
            return job;
        });
    }

    private List<Job> claimCandidate(String state,Timestamp now) {
        return claimCandidate(state,now,null);
    }

    public Job claimBackground(boolean preferResume,List<Integer> enabledSources) {
        if(enabledSources.isEmpty())return null;
        return queueTransaction("按来源领取补采",()->{
            Timestamp stamp=Timestamp.from(Instant.now());
            int start=Math.floorMod(sourceRotation.getAndIncrement(),enabledSources.size());
            for(int s=0;s<enabledSources.size();s++) {
                int source=enabledSources.get((start+s)%enabledSources.size());
                // Source rotation and task-type rotation must be independent. A global
                // alternating preference otherwise gives the same sources PARTIAL forever.
                int stateStart=Math.floorMod(backgroundRotations.computeIfAbsent(source,
                    ignored->new java.util.concurrent.atomic.AtomicInteger()).getAndIncrement(),CLAIM_STATES.length);
                List<Job> jobs=claimCandidate(CLAIM_STATES[stateStart],stamp,source);
                // Resume preference is a fallback, never a veto on a source's due new books.
                if(jobs.isEmpty() && preferResume && !"PARTIAL".equals(CLAIM_STATES[stateStart]))
                    jobs=claimCandidate("PARTIAL",stamp,source);
                for(int n=1;jobs.isEmpty()&&n<CLAIM_STATES.length;n++) jobs=claimCandidate(CLAIM_STATES[(stateStart+n)%CLAIM_STATES.length],stamp,source);
                if(jobs.isEmpty())continue;
                Job job=jobs.getFirst();
                jdbc.update("UPDATE crawl_discovery_job SET job_status='RUNNING',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=? WHERE id=?",
                    job.token(),Timestamp.from(Instant.now().plus(LEASE)),stamp,job.id());
                return job;
            }
            return null;
        });
    }

    private List<Job> claimCandidate(String state,Timestamp now,Integer source) {
        String clock="RUNNING".equals(state)?"lease_until":"next_attempt_at";
        List<Object> args=new ArrayList<>();args.add(state);args.add(now);if(source!=null)args.add(source);
        return jdbc.query("SELECT j.id,j.source_id,j.source_book_id,j.cat_id,j.book_id,j.consecutive_failures "
            + "FROM crawl_discovery_job j WHERE j.source_id IN (SELECT id FROM crawl_source WHERE source_status=1) "
            + "AND j.job_status=? AND j."+clock+"<=? "+(source==null?"":"AND j.source_id=? ")+"ORDER BY j."+clock+",j.id LIMIT 1 FOR UPDATE SKIP LOCKED",
            (r,n)->new Job(r.getLong(1),r.getInt(2),r.getString(3),r.getInt(4),r.getObject(5,Long.class),UUID.randomUUID().toString(),r.getInt(6)),args.toArray());
    }

    public boolean renew(Job job) {
        return jdbc.update("UPDATE crawl_discovery_job SET lease_until=? WHERE id=? AND job_status='RUNNING' AND lease_token=?",
            Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plus(LEASE)),job.id(),job.token())==1;
    }

    public void checkpointBook(Job job,long bookId) {
        if(jdbc.update("UPDATE crawl_discovery_job SET book_id=? WHERE id=? AND job_status='RUNNING' AND lease_token=?",
            bookId,job.id(),job.token())!=1) throw new IllegalStateException("Discovery lease was lost before checkpoint");
    }

    public void finish(Job job,String outcome,Long bookId,String error,boolean failed) {
        if(!Set.of("COMPLETE","EXISTING","FILTERED","PARTIAL","RETRY","PENDING").contains(outcome)) throw new IllegalArgumentException("Invalid job outcome");
        int failures=failed?Math.min(job.failures()+1,20):0;
        Duration delay=switch(outcome) {
            case "FILTERED" -> Duration.ofHours(6);
            case "RETRY" -> Duration.ofSeconds(Math.min(21600,60L << Math.min(failures,8)));
            case "PARTIAL", "PENDING" -> Duration.ofSeconds(5);
            default -> Duration.ZERO;
        };
        jdbc.update("UPDATE crawl_discovery_job SET job_status=CASE WHEN ?='PENDING' AND COALESCE(?,book_id) IS NOT NULL THEN 'PARTIAL' ELSE ? END,book_id=COALESCE(?,book_id),consecutive_failures=?,next_attempt_at=?,"
            + "lease_token=NULL,lease_until=NULL,last_error=?,updated_at=? WHERE id=? AND job_status='RUNNING' AND lease_token=?",
            outcome,bookId,outcome,bookId,failures,Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plus(delay)),error==null?null:error.substring(0,Math.min(200,error.length())),
            Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)),job.id(),job.token());
    }
}
