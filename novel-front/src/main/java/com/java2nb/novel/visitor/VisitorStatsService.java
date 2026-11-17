package com.java2nb.novel.visitor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class VisitorStatsService {
    private static final long CACHE_MILLIS = 3000;
    private static final long KNOWN_MILLIS = 10 * 60 * 1000;
    private final VisitorStatsRepository repository;
    private final VisitorOnlineStore online;
    private final VisitorStatsProperties properties;
    private final Clock clock;
    private volatile Cache<TotalsResult> totalsCache;
    private volatile Cache<OnlineResult> onlineCache;
    // Bounded positive cache; an unseen heartbeat always verifies persistent membership first.
    private final Map<String, Long> known = new LinkedHashMap<>(128, .75f, true);

    @Autowired public VisitorStatsService(VisitorStatsRepository repository, VisitorOnlineStore online, VisitorStatsProperties properties) {
        this(repository, online, properties, Clock.systemUTC());
    }
    public VisitorStatsService(VisitorStatsRepository repository, VisitorOnlineStore online, VisitorStatsProperties properties, Clock clock) {
        this.repository = repository; this.online = online; this.properties = properties; this.clock = clock;
    }

    /** Polling this method is read-only and never creates a visitor or a page view. */
    public Snapshot snapshot() {
        if (!properties.isEnabled()) return unavailable("disabled", false);
        return snapshot(readTotals(), readOnline());
    }

    public Snapshot visit(String visitorId, String pageViewId) {
        String visitorHash = hashId(visitorId), pageViewHash = hashId(pageViewId);
        if (!properties.isEnabled()) return unavailable("disabled", false);
        TotalsResult totals;
        try {
            totals = cacheTotals(new TotalsResult(repository.visit(visitorHash, pageViewHash), true));
            remember(visitorHash);
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (RuntimeException ex) {
            totalsCache = null;
            return snapshot(new TotalsResult(null, false), readOnline());
        }
        return snapshot(totals, touch(visitorHash));
    }

    /** Heartbeats keep prior visitors active; they cannot create UV or PV. */
    public Snapshot heartbeat(String visitorId) {
        String visitorHash = hashId(visitorId);
        if (!properties.isEnabled()) return unavailable("disabled", false);
        boolean seen;
        try { seen = isKnown(visitorHash) || repository.hasVisitor(visitorHash); }
        catch (RuntimeException ex) { return snapshot(new TotalsResult(null, false), readOnline()); }
        if (!seen) return snapshot();
        remember(visitorHash);
        return snapshot(readTotals(), touch(visitorHash));
    }

    private synchronized TotalsResult readTotals() {
        var cached = totalsCache;
        if (fresh(cached)) return cached.value();
        TotalsResult result;
        try { result = new TotalsResult(repository.totals(), true); }
        catch (RuntimeException ex) { result = new TotalsResult(null, false); }
        return cacheTotals(result);
    }

    /** A delayed response from an earlier commit cannot overwrite a newer aggregate snapshot. */
    private synchronized TotalsResult cacheTotals(TotalsResult result) {
        var previous = totalsCache;
        if (result.available() && previous != null && previous.value().available()) {
            var old = previous.value().value(); var next = result.value();
            if (old.visitors() >= next.visitors() && old.pageViews() >= next.pageViews()) result = previous.value();
        }
        totalsCache = new Cache<>(result, clock.millis());
        return result;
    }

    private synchronized OnlineResult readOnline() {
        var cached = onlineCache;
        if (fresh(cached)) return cached.value();
        OnlineResult result;
        try { result = new OnlineResult(Long.toString(online.count()), true); }
        catch (RuntimeException ex) { result = new OnlineResult(null, false); }
        onlineCache = new Cache<>(result, clock.millis());
        return result;
    }

    private synchronized OnlineResult touch(String hash) {
        try { online.touch(hash); onlineCache = null; return readOnline(); }
        catch (RuntimeException ex) {
            OnlineResult result = new OnlineResult(null, false);
            onlineCache = new Cache<>(result, clock.millis());
            return result;
        }
    }

    private boolean fresh(Cache<?> cache) {
        long age = cache == null ? -1 : clock.millis() - cache.at();
        return age >= 0 && age < CACHE_MILLIS;
    }
    private synchronized boolean isKnown(String hash) {
        Long at = known.get(hash);
        return at != null && clock.millis() >= at && clock.millis() - at < KNOWN_MILLIS;
    }
    private synchronized void remember(String hash) {
        known.put(hash, clock.millis());
        if (known.size() > 10000) known.remove(known.keySet().iterator().next());
    }

    public static String hashId(String id) {
        if (id == null || !id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("visitorId and pageViewId must be canonical UUID v4 values");
        String canonical = UUID.fromString(id).toString();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private static Snapshot snapshot(TotalsResult totals, OnlineResult online) {
        var value = totals.value();
        String status = !totals.available() ? "unavailable" : !online.available() ? "online_unavailable" : "available";
        return new Snapshot(online.value(), value == null ? null : Long.toString(value.visitors()),
            value == null ? null : Long.toString(value.pageViews()),
            value == null || value.startedAt() == null ? null : value.startedAt().toInstant(java.time.ZoneOffset.UTC).toString(),
            VisitorOnlineStore.WINDOW_SECONDS, totals.available(), online.available(), true, status);
    }
    private static Snapshot unavailable(String status, boolean enabled) {
        return new Snapshot(null, null, null, null, VisitorOnlineStore.WINDOW_SECONDS, false, false, enabled, status);
    }

    public record Snapshot(String online, String visitors, String pageViews, String startedAt,
                           int onlineWindowSeconds, boolean available, boolean onlineAvailable, boolean enabled, String status) {}
    private record TotalsResult(VisitorStatsRepository.Totals value, boolean available) {}
    private record OnlineResult(String value, boolean available) {}
    private record Cache<T>(T value, long at) {}
}
