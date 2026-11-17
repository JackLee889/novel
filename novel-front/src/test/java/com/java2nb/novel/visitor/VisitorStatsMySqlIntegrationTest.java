package com.java2nb.novel.visitor;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in only: the URL must name a dedicated local novel_visitor_qa_<digits> schema. Never deletes rows. */
class VisitorStatsMySqlIntegrationTest {
    @Test void actualMySqlMigrationConcurrencyPersistenceAndIdempotence() throws Exception {
        String url = System.getenv("VISITOR_STATS_MYSQL_TEST_URL");
        assumeTrue(url != null && !url.isBlank(), "Dedicated visitor MySQL integration database not configured");
        assertTrue(url.startsWith("jdbc:mysql://"), "Only a dedicated MySQL QA database is permitted");
        URI uri = URI.create(url.substring("jdbc:".length()));
        assertTrue("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()), "QA server must be local");
        String catalog = uri.getPath().substring(1);
        assertTrue(catalog.matches("novel_visitor_qa_[0-9]+"), "QA schema name must be novel_visitor_qa_<digits>");
        var source = new DriverManagerDataSource(url, System.getenv().getOrDefault("VISITOR_STATS_MYSQL_TEST_USER", "root"),
            System.getenv().getOrDefault("VISITOR_STATS_MYSQL_TEST_PASSWORD", ""));
        try (var connection = source.getConnection()) { assertEquals(catalog, connection.getCatalog(), "Verify target before applying additive DDL"); }
        var jdbc = new JdbcTemplate(source);
        Path migration = Path.of("doc/sql/add-visitor-stats.sql");
        if (!Files.exists(migration)) migration = Path.of("../doc/sql/add-visitor-stats.sql");
        String ddl = Files.readString(migration).replaceAll("(?m)^\\s*--.*$", "");
        for (int attempt = 0; attempt < 2; attempt++) for (String statement : ddl.split(";")) if (!statement.isBlank()) jdbc.execute(statement.trim());
        var repository = new VisitorStatsRepository(source); var baseline = repository.totals();
        String visitor = VisitorStatsService.hashId(UUID.randomUUID().toString()), page = VisitorStatsService.hashId(UUID.randomUUID().toString());
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 24; i++) tasks.add(pool.submit(() -> { gate.await(); new VisitorStatsRepository(source).visit(visitor, page); return null; }));
            gate.countDown(); for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        assertEquals(baseline.visitors() + 1, repository.totals().visitors());
        assertEquals(baseline.pageViews() + 1, repository.totals().pageViews());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM site_visitor_seen WHERE visitor_hash=?", Integer.class, visitor));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM site_visitor_pageview WHERE pageview_hash=?", Integer.class, page));
        assertTrue(new VisitorStatsRepository(source).hasVisitor(visitor));
        new VisitorStatsRepository(source).visit(visitor, page);
        assertEquals(baseline.pageViews() + 1, repository.totals().pageViews());
        assertThrows(IllegalArgumentException.class, () -> repository.visit(VisitorStatsRepositoryTest.hash(), page));
        assertEquals(baseline.visitors() + 1, repository.totals().visitors());
    }
}
