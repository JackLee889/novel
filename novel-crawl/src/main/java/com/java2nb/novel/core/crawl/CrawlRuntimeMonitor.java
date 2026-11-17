package com.java2nb.novel.core.crawl;

import com.java2nb.novel.utils.CrawlAccessChallengeException;
import com.java2nb.novel.utils.CrawlMetadataException;
import com.java2nb.novel.utils.CrawlPersistenceErrors;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Bounded process-local telemetry. No database reads or writes on the sampling path. */
@Component
public class CrawlRuntimeMonitor {
    private static final int WINDOW = 60;
    private static final int MAX_ERRORS = 5;
    private static final CrawlRuntimeMonitor DISABLED = new CrawlRuntimeMonitor(false, Clock.systemUTC(), System::nanoTime);
    private final boolean enabled;
    private final Clock clock;
    private final LongSupplier nanos;
    private final long startedAt;
    private final Map<Integer, Source> sources = new ConcurrentHashMap<>();
    private final ThreadLocal<Scope> current = new ThreadLocal<>();

    public CrawlRuntimeMonitor() { this(true, Clock.systemUTC(), System::nanoTime); }

    CrawlRuntimeMonitor(Clock clock, LongSupplier nanos) { this(true, clock, nanos); }

    private CrawlRuntimeMonitor(boolean enabled, Clock clock, LongSupplier nanos) {
        this.enabled = enabled; this.clock = clock; this.nanos = nanos; this.startedAt = clock.millis();
    }

    public static CrawlRuntimeMonitor disabled() { return DISABLED; }

    public record Work(String kind, String thread, String sourceBookId, String bookName, String phase,
                       int processed, int total, String chapter, long saved, long startedAt, String outcome) { }
    public record Failure(long occurredAt, String message) { }
    public record Snapshot(long observedAt, long startedAt, long savedLastMinute, long savedSinceStart,
                           long errorsLastMinute, Long lastActivityAt, Long lastSavedAt, Long retryAt,
                           int activeDownloads, int activeScans, List<Work> tasks, Work lastTask, List<Failure> errors) { }

    private static final class Bucket {
        long second = Long.MIN_VALUE;
        long saved, errors;
    }

    private static final class Source {
        final Bucket[] buckets = new Bucket[WINDOW];
        final Map<Long, Scope> active = new HashMap<>();
        final Deque<Failure> errors = new ArrayDeque<>();
        long saved;
        Long lastActivityAt, lastSavedAt, retryAt;
        Work lastTask;
        Source() { Arrays.setAll(buckets, i -> new Bucket()); }
    }

    public final class Scope implements AutoCloseable {
        private final Source source;
        private final Scope previous;
        private final long threadId = Thread.currentThread().threadId();
        private final String thread = Thread.currentThread().getName();
        private final String kind, sourceBookId;
        private final long start = clock.millis();
        private String title = "读取图书信息", phase = "DETAIL", chapter, outcome;
        private int processed, total;
        private long saved;
        private boolean closed;

        private Scope(Source source, String kind, String sourceBookId, Scope previous) {
            this.source = source; this.kind = kind; this.sourceBookId = clean(sourceBookId, 80); this.previous = previous;
        }

        public void result(String outcome) {
            if (source != null) synchronized (source) { this.outcome = clean(outcome, 24); }
        }

        private Work snapshot() {
            return new Work(kind, thread, sourceBookId, title, phase, processed, total, chapter, saved, start, outcome);
        }

        @Override public void close() {
            if (source == null || closed) return;
            synchronized (source) {
                if (closed) return;
                closed = true;
                source.active.remove(threadId, this);
                source.lastActivityAt = clock.millis();
                source.lastTask = snapshot();
                if (previous != null && previous.source == source && !previous.closed) source.active.put(threadId, previous);
            }
            if (current.get() == this) {
                if (previous == null) current.remove(); else current.set(previous);
            }
        }
    }

    public Scope beginBook(Integer sourceId, String kind, String sourceBookId) {
        Source source = enabled && sourceId != null && sourceId > 0 ? sources.computeIfAbsent(sourceId, id -> new Source()) : null;
        Scope scope = new Scope(source, kind, sourceBookId, source == null ? null : current.get());
        if (source != null) synchronized (source) {
            source.active.put(scope.threadId, scope);
            source.lastActivityAt = clock.millis();
            current.set(scope);
        }
        return scope;
    }

    public Scope beginScan(Integer sourceId, int category, int page, int pages) {
        Scope scope = beginBook(sourceId, "SCAN", null);
        if (scope.source != null) synchronized (scope.source) {
            scope.title = "分类 " + category;
            scope.phase = "SCAN"; scope.processed = page; scope.total = pages;
        }
        return scope;
    }

    public void title(String title) { change(scope -> scope.title = clean(title, 120)); }
    public void catalog(int total) { change(scope -> { scope.phase = "CATALOG"; scope.total = Math.max(0, total); }); }
    public void chapter(int position, String chapter) {
        change(scope -> { scope.phase = "CHAPTER"; scope.processed = Math.min(Math.max(0, position), scope.total); scope.chapter = clean(chapter, 120); });
    }
    public void scanPages(int page, int total) { change(scope -> { scope.processed = page; scope.total = total; }); }

    private void change(java.util.function.Consumer<Scope> mutation) {
        Scope scope = enabled ? current.get() : null;
        if (scope == null) return;
        synchronized (scope.source) {
            mutation.accept(scope); scope.source.lastActivityAt = clock.millis();
        }
    }

    private Bucket bucket(Source source) {
        long second = Math.floorDiv(nanos.getAsLong(), 1_000_000_000L);
        Bucket bucket = source.buckets[Math.floorMod(second, WINDOW)];
        if (bucket.second != second) { bucket.second = second; bucket.saved = 0; bucket.errors = 0; }
        return bucket;
    }

    /** Call only after the persistence callback returns successfully. Metadata-only batches do not count. */
    public void saved(Integer sourceId, int count) {
        if (!enabled || sourceId == null || count <= 0) return;
        Source source = sources.computeIfAbsent(sourceId, id -> new Source());
        synchronized (source) {
            bucket(source).saved += count; source.saved += count;
            source.lastSavedAt = clock.millis(); source.lastActivityAt = source.lastSavedAt;
            Scope scope = current.get();
            if (scope != null && scope.source == source) scope.saved += count;
        }
    }

    public void failure(Integer sourceId, String message) { failure(sourceId, message, null); }

    public void failure(Integer sourceId, Throwable error) {
        if (!enabled || sourceId == null) return;
        Throwable cause = error;
        for (int i = 0; cause != null && i < 6; i++, cause = cause.getCause()) {
            if (cause instanceof CrawlMetadataException metadata) {
                failure(sourceId, metadata.getMessage(), null);
                return;
            }
            if (cause instanceof CrawlAccessChallengeException challenge) {
                failure(sourceId, "源站要求验证或限制访问，等待重试", clock.millis() + Math.min(Math.max(0, challenge.getRetryAfterSeconds()), 86400) * 1000L);
                return;
            }
        }
        String database = CrawlPersistenceErrors.describe(error);
        if (database != null) {
            Scope scope = enabled ? current.get() : null;
            if (scope != null && scope.source == sources.get(sourceId)) synchronized (scope.source) {
                database = CrawlPersistenceErrors.context(database, scope.sourceBookId, scope.title);
            }
            failure(sourceId, database, null);
            return;
        }
        // Exception messages may contain request URLs, headers, credentials or source HTML.
        failure(sourceId, "请求或处理失败（" + error.getClass().getSimpleName() + "）", null);
    }

    private void failure(Integer sourceId, String message, Long retryAt) {
        if (!enabled || sourceId == null) return;
        Source source = sources.computeIfAbsent(sourceId, id -> new Source());
        synchronized (source) {
            bucket(source).errors++; source.lastActivityAt = clock.millis();
            source.errors.addFirst(new Failure(clock.millis(), clean(message, 360)));
            while (source.errors.size() > MAX_ERRORS) source.errors.removeLast();
            if (retryAt != null) source.retryAt = retryAt;
        }
    }

    public Snapshot snapshot(Integer sourceId) {
        Source source = sourceId == null ? null : sources.get(sourceId);
        if (source == null) return new Snapshot(clock.millis(), startedAt, 0, 0, 0, null, null, null, 0, 0, List.of(), null, List.of());
        synchronized (source) {
            long second = Math.floorDiv(nanos.getAsLong(), 1_000_000_000L), saved = 0, errors = 0;
            for (Bucket bucket : source.buckets) {
                if (bucket.second <= second && bucket.second > second - WINDOW) { saved += bucket.saved; errors += bucket.errors; }
            }
            List<Work> tasks = source.active.values().stream().filter(scope -> !scope.closed)
                .map(Scope::snapshot).sorted(Comparator.comparing(Work::kind).thenComparing(Work::thread)).toList();
            int scans = (int) tasks.stream().filter(task -> "SCAN".equals(task.kind())).count();
            return new Snapshot(clock.millis(), startedAt, saved, source.saved, errors, source.lastActivityAt,
                source.lastSavedAt, source.retryAt != null && source.retryAt > clock.millis() ? source.retryAt : null,
                tasks.size() - scans, scans, tasks, source.lastTask, List.copyOf(source.errors));
        }
    }

    private static String clean(String text, int limit) {
        if (text == null) return null;
        String safe = text.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replace('\n', ' ');
        return safe.length() > limit ? safe.substring(0, limit) : safe;
    }
}
