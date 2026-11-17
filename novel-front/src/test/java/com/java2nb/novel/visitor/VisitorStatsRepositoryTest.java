package com.java2nb.novel.visitor;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VisitorStatsRepositoryTest {
    VisitorStatsRepository repository;
    JdbcTemplate db;
    @BeforeEach void setup() {
        JdbcDataSource source = source(); db = new JdbcTemplate(source); schema(db);
        repository = new VisitorStatsRepository(source, Clock.fixed(Instant.parse("2026-10-09T06:00:00Z"), ZoneOffset.UTC));
    }
    static JdbcDataSource source() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:visitor_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return source;
    }
    static void schema(JdbcTemplate db) {
        db.execute("CREATE TABLE site_visitor_stats(id TINYINT PRIMARY KEY,visitors BIGINT NOT NULL DEFAULT 0,page_views BIGINT NOT NULL DEFAULT 0,started_at TIMESTAMP(3))");
        db.execute("CREATE TABLE site_visitor_seen(visitor_hash CHAR(64) PRIMARY KEY,first_seen_at TIMESTAMP(3) NOT NULL)");
        db.execute("CREATE TABLE site_visitor_pageview(pageview_hash CHAR(64) PRIMARY KEY,visitor_hash CHAR(64) NOT NULL,created_at TIMESTAMP(3) NOT NULL)");
        db.update("INSERT INTO site_visitor_stats(id) VALUES(1)");
    }
    static String hash() { return VisitorStatsService.hashId(UUID.randomUUID().toString()); }

    @Test void visitAndRetryAreAtomicAndIdentifiersAreOnlyStoredAsHashes() {
        String visitor = hash(), page = hash();
        assertNull(repository.totals().startedAt());
        repository.visit(visitor, page); repository.visit(visitor, page);
        repository.visit(visitor, hash());
        var totals = repository.totals();
        assertEquals(1, totals.visitors()); assertEquals(2, totals.pageViews());
        assertEquals("2026-10-09T06:00", totals.startedAt().toString());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM site_visitor_seen", Integer.class));
        assertEquals(visitor, db.queryForObject("SELECT visitor_hash FROM site_visitor_seen", String.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM site_visitor_pageview", Integer.class));
    }
    @Test void concurrentIdenticalVisitsAcrossRepositoryInstancesCountOnce() throws Exception {
        String visitor = hash(), page = hash();
        var source = source(); var otherDb = new JdbcTemplate(source); schema(otherDb);
        var first = new VisitorStatsRepository(source); var second = new VisitorStatsRepository(source);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 24; i++) {
                var target = i % 2 == 0 ? first : second;
                tasks.add(pool.submit(() -> { gate.await(); target.visit(visitor, page); return null; }));
            }
            gate.countDown(); for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, first.totals().visitors()); assertEquals(1, first.totals().pageViews());
        assertEquals(1, otherDb.queryForObject("SELECT COUNT(*) FROM site_visitor_seen", Integer.class));
        assertEquals(1, otherDb.queryForObject("SELECT COUNT(*) FROM site_visitor_pageview", Integer.class));
    }
    @Test void concurrentDistinctPagesForOneVisitorCountOneUvAndAllPages() throws Exception {
        String visitor = hash(); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) tasks.add(pool.submit(() -> { gate.await(); repository.visit(visitor, hash()); return null; }));
            gate.countDown(); for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, repository.totals().visitors()); assertEquals(20, repository.totals().pageViews());
    }
    @Test void pageIdReuseWithAnotherVisitorCannotCreateASecondVisitor() {
        String page = hash(), original = hash(), impostor = hash(); repository.visit(original, page);
        assertThrows(IllegalArgumentException.class, () -> repository.visit(impostor, page));
        assertFalse(repository.hasVisitor(impostor)); assertEquals(1, repository.totals().visitors()); assertEquals(1, repository.totals().pageViews());
    }
    @Test void failedVisitRollsBackBothMarkersAndCounters() {
        String visitor = hash(), page = hash();
        db.execute("ALTER TABLE site_visitor_stats ADD CONSTRAINT fail_visit CHECK(page_views=0)");
        assertThrows(RuntimeException.class, () -> repository.visit(visitor, page));
        assertFalse(repository.hasVisitor(visitor));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM site_visitor_pageview", Integer.class));
        assertEquals(0, repository.totals().pageViews());
    }
}
