package com.java2nb.novel.seo;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Fixed memory bound, no request-side I/O. A database outage never blocks a request. */
@Component
public class SeoHttpStats {
    private static final Logger log = LoggerFactory.getLogger(SeoHttpStats.class);
    private final SeoHttpStatsRepository repository;
    private final Clock clock;
    private final String producerId = UUID.randomUUID().toString(); // process identity, never a visitor identity
    private final Object countersLock = new Object(), flushLock = new Object();
    private final Map<LocalDateTime, Counter[]> hours = new LinkedHashMap<>();
    private final Counter[] overflow = counters();
    private long failedFlushes;

    @Autowired public SeoHttpStats(SeoHttpStatsRepository repository) { this(repository, Clock.systemUTC()); }
    public SeoHttpStats(SeoHttpStatsRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }

    public void record(int status, String clientKind, boolean unresolvedException) {
        LocalDateTime hour = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        int kind = SeoHttpStatsRepository.CLIENT_KINDS.indexOf(clientKind);
        if (kind < 0) kind = 0;
        synchronized (countersLock) {
            Counter[] bucket = hours.get(hour);
            if (bucket == null) {
                if (hours.size() >= SeoHttpStatsRepository.WINDOW_HOURS) {
                    // Only evict acknowledged buckets. Never fold a possibly committed in-flight batch.
                    var candidates = hours.entrySet().iterator();
                    while (candidates.hasNext()) {
                        var candidate = candidates.next();
                        if (clean(candidate.getValue())) { candidates.remove(); break; }
                    }
                }
                if (hours.size() < SeoHttpStatsRepository.WINDOW_HOURS) { bucket = counters(); hours.put(hour, bucket); }
                else bucket = overflow;
            }
            Counter counter = bucket[kind]; counter.total++;
            // An unresolved unhandled exception has no confirmed terminal HTTP status. Keep it separate.
            if (unresolvedException) counter.unresolved++;
            else if (status == 404) counter.notFound++;
            else if (status >= 500 && status <= 599) counter.serverErrors++;
            if (counter.first == null || hour.isBefore(counter.first)) counter.first = hour;
            if (counter.last == null || hour.isAfter(counter.last)) counter.last = hour;
        }
    }

    /** User-Agent classification is only a declared hint. No matching string is retained. */
    public static String declaredClient(String userAgent) {
        if (userAgent == null) return "ordinary";
        String ua = userAgent.substring(0, Math.min(userAgent.length(), 1024)).toLowerCase(Locale.ROOT);
        if (ua.contains("googlebot")) return "googlebot";
        if (ua.contains("bingbot")) return "bingbot";
        if (ua.contains("baiduspider")) return "baiduspider";
        if (ua.contains("bot") || ua.contains("spider") || ua.contains("crawler") || ua.contains("slurp")) return "other_declared_crawler";
        return "ordinary";
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void flush() {
        synchronized (flushLock) {
            List<SeoHttpStatsRepository.Bucket> pending = snapshot();
            if (pending.isEmpty()) return;
            try {
                repository.persist(pending);
                synchronized (countersLock) {
                    for (var item : pending) {
                        Counter[] bucket = item.hour().equals(SeoHttpStatsRepository.OVERFLOW_HOUR) ? overflow : hours.get(item.hour());
                        if (bucket == null) continue;
                        Counter counter = bucket[SeoHttpStatsRepository.CLIENT_KINDS.indexOf(item.clientKind())];
                        counter.ackTotal = item.totalRequests(); counter.ackNotFound = item.notFound();
                        counter.ackServerErrors = item.serverErrors(); counter.ackUnresolved = item.unresolvedExceptions();
                    }
                    failedFlushes = 0;
                }
            } catch (RuntimeException ex) {
                failedFlushes++;
                // Log no request data and no SQL parameters. Retry absolute values on the next tick.
                if (failedFlushes == 1 || failedFlushes % 20 == 0) log.warn("SEO anonymous HTTP aggregate flush failed; fixed counters retained (attempt {})", failedFlushes);
            }
        }
    }

    @PreDestroy public void shutdown() { flush(); }

    List<SeoHttpStatsRepository.Bucket> snapshot() {
        synchronized (countersLock) {
            List<SeoHttpStatsRepository.Bucket> result = new ArrayList<>();
            hours.forEach((hour, bucket) -> append(result, hour, bucket));
            append(result, SeoHttpStatsRepository.OVERFLOW_HOUR, overflow); return result;
        }
    }
    int bufferedHourCount() { synchronized (countersLock) { return hours.size(); } }
    private void append(List<SeoHttpStatsRepository.Bucket> result, LocalDateTime hour, Counter[] bucket) {
        for (int kind = 0; kind < bucket.length; kind++) {
            Counter counter = bucket[kind];
            if (!counter.clean()) result.add(new SeoHttpStatsRepository.Bucket(producerId, hour, SeoHttpStatsRepository.CLIENT_KINDS.get(kind),
                counter.first, counter.last, counter.total, counter.notFound, counter.serverErrors, counter.unresolved));
        }
    }
    private static boolean clean(Counter[] bucket) { for (Counter counter : bucket) if (!counter.clean()) return false; return true; }
    private static Counter[] counters() {
        Counter[] values = new Counter[SeoHttpStatsRepository.CLIENT_KINDS.size()];
        for (int i = 0; i < values.length; i++) values[i] = new Counter(); return values;
    }
    private static final class Counter {
        long total, notFound, serverErrors, unresolved, ackTotal, ackNotFound, ackServerErrors, ackUnresolved;
        LocalDateTime first, last;
        boolean clean() { return total == ackTotal && notFound == ackNotFound && serverErrors == ackServerErrors && unresolved == ackUnresolved; }
    }
}
