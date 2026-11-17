package com.java2nb.novel.seo;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class SeoHttpStatsTest {
    @Test void observationDoesNotWriteAndRepeatedFlushesOnlySendChangedAbsoluteCounters() {
        var repository = mock(SeoHttpStatsRepository.class);
        var stats = new SeoHttpStats(repository, Clock.fixed(Instant.parse("2026-10-08T10:21:00Z"), ZoneOffset.UTC));
        stats.record(200, "ordinary", false); stats.record(404, "googlebot", false); stats.record(503, "googlebot", false);
        verifyNoInteractions(repository);
        var pending = stats.snapshot(); assertEquals(3, pending.stream().mapToLong(SeoHttpStatsRepository.Bucket::totalRequests).sum());
        assertEquals(1, pending.stream().mapToLong(SeoHttpStatsRepository.Bucket::notFound).sum());
        assertEquals(1, pending.stream().mapToLong(SeoHttpStatsRepository.Bucket::serverErrors).sum());
        stats.flush(); assertTrue(stats.snapshot().isEmpty()); stats.flush(); verify(repository, times(1)).persist(anyList());
        stats.record(200, "googlebot", false); stats.flush();
        var sent = org.mockito.ArgumentCaptor.forClass(List.class); verify(repository, times(2)).persist(sent.capture());
        @SuppressWarnings("unchecked") List<SeoHttpStatsRepository.Bucket> second = sent.getAllValues().get(1);
        assertEquals(3, second.getFirst().totalRequests()); // absolute googlebot count, not a delta
    }

    @Test void prolongedDatabaseFailuresHaveFixedMemoryBoundAndLoseNoCounts() {
        var repository = mock(SeoHttpStatsRepository.class);
        doThrow(new IllegalStateException("database unavailable")).when(repository).persist(anyList());
        var clock = new MutableClock(); var stats = new SeoHttpStats(repository, clock);
        for (int i = 0; i < 2000; i++) { clock.instant = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 3600L); stats.record(404, "bingbot", false); stats.flush(); }
        assertEquals(168, stats.bufferedHourCount());
        assertEquals(2000, stats.snapshot().stream().mapToLong(SeoHttpStatsRepository.Bucket::totalRequests).sum());
        var overflow = stats.snapshot().stream().filter(b -> b.hour().equals(SeoHttpStatsRepository.OVERFLOW_HOUR)).findFirst().orElseThrow();
        assertEquals(1832, overflow.notFound()); assertTrue(overflow.firstHour().isBefore(overflow.lastHour()));
        doNothing().when(repository).persist(anyList()); stats.flush(); assertTrue(stats.snapshot().isEmpty());
        clock.instant = clock.instant.plusSeconds(3600); stats.record(500, "bingbot", false);
        assertEquals(168, stats.bufferedHourCount()); assertEquals(1, stats.snapshot().getFirst().serverErrors());
    }

    @Test void requestsDuringDatabaseIoDoNotBlockAndRemainPendingForTheNextBatch() throws Exception {
        var repository = mock(SeoHttpStatsRepository.class); var stats = new SeoHttpStats(repository);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; }).when(repository).persist(anyList());
        stats.record(404, "ordinary", false);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(stats::flush); assertTrue(entered.await(5, TimeUnit.SECONDS));
            stats.record(503, "ordinary", false); // would deadlock this test if recording waited on the writer
            release.countDown(); future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2, stats.snapshot().getFirst().totalRequests()); assertEquals(1, stats.snapshot().getFirst().serverErrors());
        stats.flush(); assertTrue(stats.snapshot().isEmpty());
    }

    @Test void arbitraryUserAgentsCannotCreateUnboundedKeysOrChangeAccessBehavior() {
        assertEquals("ordinary", SeoHttpStats.declaredClient(null));
        assertEquals("googlebot", SeoHttpStats.declaredClient("Mozilla/5.0 Googlebot/2.1"));
        assertEquals("bingbot", SeoHttpStats.declaredClient("bingbot"));
        assertEquals("baiduspider", SeoHttpStats.declaredClient("Baiduspider/2.0"));
        assertEquals("other_declared_crawler", SeoHttpStats.declaredClient("invented-test-bot"));
        assertEquals("ordinary", SeoHttpStats.declaredClient("X".repeat(1024) + "googlebot"));
        var stats = new SeoHttpStats(mock(SeoHttpStatsRepository.class));
        for (int i = 0; i < 10000; i++) stats.record(200, "attacker-" + i, false);
        assertEquals(1, stats.snapshot().size()); assertEquals("ordinary", stats.snapshot().getFirst().clientKind());
    }

    private static final class MutableClock extends Clock {
        Instant instant = Instant.parse("2026-10-08T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
