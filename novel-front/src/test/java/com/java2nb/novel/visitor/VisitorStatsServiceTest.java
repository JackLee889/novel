package com.java2nb.novel.visitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VisitorStatsServiceTest {
    MutableClock clock; VisitorStatsRepository repository; TestOnline online; VisitorStatsProperties properties; VisitorStatsService service;
    @BeforeEach void setup() {
        clock = new MutableClock(); var source = VisitorStatsRepositoryTest.source();
        VisitorStatsRepositoryTest.schema(new JdbcTemplate(source)); repository = new VisitorStatsRepository(source, clock);
        online = new TestOnline(clock); properties = new VisitorStatsProperties(); service = new VisitorStatsService(repository, online, properties, clock);
    }
    String id() { return UUID.randomUUID().toString(); }

    @Test void getAndUnseenHeartbeatNeverCountVisitsAndSeveralTabsShareOneOnlineVisitor() {
        String visitor = id();
        assertEquals("0", service.snapshot().visitors()); service.heartbeat(visitor);
        assertEquals(0, repository.totals().visitors()); assertEquals(0, repository.totals().pageViews());
        assertEquals("0", service.snapshot().online());
        service.visit(visitor, id()); service.visit(visitor, id()); service.heartbeat(visitor);
        assertEquals("1", service.snapshot().online());
        assertEquals(1, repository.totals().visitors()); assertEquals(2, repository.totals().pageViews());
    }
    @Test void activityExpiresExactlyAtNinetySecondsAndHeartbeatOnlyExtendsIt() {
        String visitor = id(); service.visit(visitor, id());
        clock.advance(89000); assertEquals("1", service.snapshot().online());
        // Clear the documented 3-second read cache before checking the exact Redis window.
        assertEquals(1, online.count()); clock.advance(1000); assertEquals(0, online.count());
        clock.advance(3000); assertEquals("0", service.snapshot().online());
        service.heartbeat(visitor); assertEquals("1", service.snapshot().online());
        assertEquals(1, repository.totals().pageViews());
    }
    @Test void redisOutageKeepsDurableUvPvAndDoesNotInventAnOnlineZero() {
        online.failed = true;
        var response = service.visit(id(), id());
        assertTrue(response.available()); assertFalse(response.onlineAvailable()); assertNull(response.online());
        assertEquals("1", response.visitors()); assertEquals("1", response.pageViews()); assertEquals("online_unavailable", response.status());
        online.failed = false; clock.advance(3000); assertTrue(service.snapshot().onlineAvailable());
    }
    @Test void databaseOutageDoesNotAcknowledgeAVistAsCountedAndRetryRemainsIdempotent() {
        var missing = new VisitorStatsRepository(VisitorStatsRepositoryTest.source(), clock);
        var unavailable = new VisitorStatsService(missing, online, properties, clock);
        var response = unavailable.visit(id(), id());
        assertFalse(response.available()); assertNull(response.visitors()); assertNull(response.pageViews()); assertNull(response.startedAt());
        assertEquals(0, online.count());
        String visitor = id(), page = id(); service.visit(visitor, page); service.visit(visitor, page);
        assertEquals("1", service.snapshot().pageViews());
    }
    @Test void disabledFeatureDoesNotCallEitherStore() {
        var db = mock(VisitorStatsRepository.class); var store = mock(VisitorOnlineStore.class);
        properties.setEnabled(false); var disabled = new VisitorStatsService(db, store, properties, clock);
        assertEquals("disabled", disabled.snapshot().status()); assertFalse(disabled.visit(id(), id()).enabled()); disabled.heartbeat(id());
        verifyNoInteractions(db, store);
    }
    @Test void uuidValidationIsStrictAndHashingNormalizesCase() {
        String value = id(); assertEquals(VisitorStatsService.hashId(value), VisitorStatsService.hashId(value.toUpperCase()));
        assertEquals(64, VisitorStatsService.hashId(value).length());
        for (String bad : new String[]{"", "1-1-1-1-1", value + " ", "<script>", "00000000-0000-0000-0000-000000000000", "x".repeat(500)})
            assertThrows(IllegalArgumentException.class, () -> service.visit(bad, id()));
        assertThrows(IllegalArgumentException.class, () -> service.heartbeat(null));
        assertEquals(0, repository.totals().pageViews());
    }
    @Test void pollsUseShortCacheWithoutKeepingAFalseUnavailableStateForever() {
        var db = mock(VisitorStatsRepository.class); var store = mock(VisitorOnlineStore.class);
        when(db.totals()).thenThrow(new IllegalStateException()).thenReturn(new VisitorStatsRepository.Totals(8, 9, null));
        when(store.count()).thenReturn(3L); var cached = new VisitorStatsService(db, store, properties, clock);
        assertFalse(cached.snapshot().available()); cached.snapshot();
        verify(db).totals(); verify(store).count();
        clock.advance(3000); assertEquals("8", cached.snapshot().visitors()); verify(db, times(2)).totals();
    }
    @Test void delayedOlderCommitCannotOverwriteNewerTotalsCache() throws Exception {
        var db = mock(VisitorStatsRepository.class); var oldWaiting = new CountDownLatch(1); var releaseOld = new CountDownLatch(1);
        var order = new AtomicInteger();
        when(db.visit(anyString(), anyString())).thenAnswer(call -> {
            if (order.incrementAndGet() == 1) { oldWaiting.countDown(); assertTrue(releaseOld.await(5, TimeUnit.SECONDS)); return new VisitorStatsRepository.Totals(1, 1, null); }
            return new VisitorStatsRepository.Totals(2, 2, null);
        });
        var stats = new VisitorStatsService(db, online, properties, clock);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> stats.visit(id(), id())); assertTrue(oldWaiting.await(5, TimeUnit.SECONDS));
            assertEquals("2", stats.visit(id(), id()).visitors()); releaseOld.countDown();
            assertEquals("2", first.get(5, TimeUnit.SECONDS).visitors()); assertEquals("2", stats.snapshot().pageViews());
        } finally { releaseOld.countDown(); }
    }

    static class MutableClock extends Clock {
        long millis = Instant.parse("2026-10-09T06:00:00Z").toEpochMilli();
        void advance(long value) { millis += value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
    static class TestOnline implements VisitorOnlineStore {
        final MutableClock clock; final Map<String, Long> activity = new HashMap<>(); boolean failed;
        TestOnline(MutableClock clock) { this.clock = clock; }
        @Override public synchronized void touch(String hash) { if (failed) throw new IllegalStateException(); activity.put(hash, clock.millis()); }
        @Override public synchronized long count() {
            if (failed) throw new IllegalStateException(); activity.values().removeIf(at -> at <= clock.millis() - 90000); return activity.size();
        }
    }
}
