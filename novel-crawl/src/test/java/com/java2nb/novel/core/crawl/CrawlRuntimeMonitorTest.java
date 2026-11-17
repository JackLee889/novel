package com.java2nb.novel.core.crawl;

import com.java2nb.novel.utils.CrawlAccessChallengeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CrawlRuntimeMonitorTest {
    private final AtomicLong nanos = new AtomicLong();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC);
    private final CrawlRuntimeMonitor monitor = new CrawlRuntimeMonitor(clock, nanos::get);

    @Test
    void rollingMinuteExpiresWithoutLosingSinceStartOrLastSave() {
        monitor.saved(1, 25);
        nanos.set(TimeUnit.SECONDS.toNanos(59));
        monitor.saved(1, 3);
        assertEquals(28, monitor.snapshot(1).savedLastMinute());
        nanos.set(TimeUnit.SECONDS.toNanos(60));
        assertEquals(3, monitor.snapshot(1).savedLastMinute());
        nanos.set(TimeUnit.SECONDS.toNanos(119));
        assertEquals(0, monitor.snapshot(1).savedLastMinute());
        assertEquals(28, monitor.snapshot(1).savedSinceStart());
        assertEquals(clock.millis(), monitor.snapshot(1).lastSavedAt());
        assertEquals(0, monitor.snapshot(2).savedSinceStart());
    }

    @Test
    void separateSourcesAndNestedScopesKeepCorrectActiveTaskAndOutcome() {
        try (var scan = monitor.beginScan(1, 8, 2, 15)) {
            assertEquals(1, monitor.snapshot(1).activeScans());
            try (var book = monitor.beginBook(1, "UPDATE", "23125")) {
                monitor.title("已有书"); monitor.catalog(100); monitor.chapter(5, "第五章"); monitor.saved(1, 2);
                var active = monitor.snapshot(1);
                assertEquals(1, active.activeDownloads()); assertEquals(0, active.activeScans());
                assertEquals("已有书", active.tasks().getFirst().bookName());
                assertEquals(5, active.tasks().getFirst().processed());
                assertEquals(2, active.tasks().getFirst().saved());
                book.result("PARTIAL");
            }
            assertEquals("PARTIAL", monitor.snapshot(1).lastTask().outcome());
            assertEquals(1, monitor.snapshot(1).activeScans());
            try (var other = monitor.beginBook(14, "DOWNLOAD", "99")) {
                monitor.title("另一来源"); monitor.saved(14, 4);
                assertEquals(1, monitor.snapshot(14).activeDownloads());
            }
        }
        assertTrue(monitor.snapshot(1).tasks().isEmpty());
        assertTrue(monitor.snapshot(14).tasks().isEmpty());
        assertEquals(2, monitor.snapshot(1).savedSinceStart());
        assertEquals(4, monitor.snapshot(14).savedSinceStart());
    }

    @Test
    @Timeout(10)
    void twentyFourConcurrentWorkersHaveExactTotalsAndLeaveNoGhostTasks() throws Exception {
        int threads = 24;
        var ready = new CountDownLatch(threads);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int book = i;
                executor.submit(() -> {
                    try (var activity = monitor.beginBook(12, "UPDATE", String.valueOf(book))) {
                        for (int n = 0; n < 100; n++) monitor.saved(12, 1);
                        ready.countDown();
                        release.await();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
            try {
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                var snapshot = monitor.snapshot(12);
                assertEquals(24, snapshot.activeDownloads());
                assertEquals(2400, snapshot.savedSinceStart());
                assertEquals(2400, snapshot.savedLastMinute());
                assertThrows(UnsupportedOperationException.class, () -> snapshot.tasks().clear());
            } finally { release.countDown(); }
        }
        assertTrue(monitor.snapshot(12).tasks().isEmpty());
    }

    @Test
    void errorsAreBoundedAndNeverExposeExceptionCredentialsOrUrls() {
        for (int i = 0; i < 20; i++) monitor.failure(1, new IllegalStateException("password=secret https://site/book?token=private"));
        var snapshot = monitor.snapshot(1);
        assertEquals(5, snapshot.errors().size()); assertEquals(20, snapshot.errorsLastMinute());
        assertFalse(snapshot.errors().toString().contains("secret"));
        assertFalse(snapshot.errors().toString().contains("https://"));
        monitor.failure(1, new RuntimeException("wrapped", new CrawlAccessChallengeException(300)));
        assertEquals(clock.millis() + 300000, monitor.snapshot(1).retryAt());
        assertTrue(monitor.snapshot(1).errors().getFirst().message().contains("验证"));
        nanos.set(TimeUnit.SECONDS.toNanos(60));
        assertEquals(0, monitor.snapshot(1).errorsLastMinute());
        assertEquals(5, snapshot.errors().size(), "已返回的快照不随后续异常变化");
    }

    @Test
    void databaseFailureKeepsTheFailedBookContextAfterTheTaskCloses() {
        try (var activity = monitor.beginBook(12, "DOWNLOAD", "530222")) {
            monitor.title("绑定邪神聊天群后");
            monitor.failure(12, new org.springframework.dao.DataIntegrityViolationException("SQL private",
                new java.sql.SQLException("Data too long for column 'book_desc' at row 1", "22001", 1406)));
        }
        String message = monitor.snapshot(12).errors().getFirst().message();
        assertTrue(message.contains("简介(book_desc)"));
        assertTrue(message.contains("书号=530222"));
        assertTrue(message.contains("书名=绑定邪神聊天群后"));
        assertFalse(message.contains("private"));
        assertTrue(monitor.snapshot(12).tasks().isEmpty());
    }

    @Test
    void databaseFailureWithoutAnActiveBookDoesNotBorrowThePreviousBook() {
        try (var activity = monitor.beginBook(12, "DOWNLOAD", "previous")) { monitor.title("previous"); }
        monitor.failure(12, new java.sql.SQLException("Column 'author_name' cannot be null", "23000", 1048));
        assertFalse(monitor.snapshot(12).errors().getFirst().message().contains("previous"));
        assertDoesNotThrow(() -> monitor.failure(null, new java.sql.SQLException("private", "22001", 1406)));
    }

    @Test
    void emptyAndDisabledMonitoringDoesNotInventActivity() {
        assertTrue(monitor.snapshot(null).tasks().isEmpty());
        assertNull(monitor.snapshot(99).lastActivityAt());
        monitor.saved(1, 0); monitor.saved(1, -1);
        assertNull(monitor.snapshot(1).lastSavedAt());
        var disabled = CrawlRuntimeMonitor.disabled();
        try (var activity = disabled.beginBook(1, "DOWNLOAD", "1")) {
            disabled.saved(1, 99); disabled.failure(1, "ignored");
        }
        assertEquals(0, disabled.snapshot(1).savedSinceStart());
        assertTrue(disabled.snapshot(1).errors().isEmpty());
    }
}
