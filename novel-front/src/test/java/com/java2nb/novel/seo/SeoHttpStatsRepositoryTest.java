package com.java2nb.novel.seo;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SeoHttpStatsRepositoryTest {
    JdbcTemplate db; SeoHttpStatsRepository repository;
    final LocalDateTime hour = LocalDateTime.of(2026, 10, 8, 10, 0);
    @BeforeEach void create() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:http_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(source); repository = new SeoHttpStatsRepository(source, Clock.fixed(Instant.parse("2026-10-08T10:20:00Z"), ZoneOffset.UTC));
        db.execute("CREATE TABLE seo_http_hourly(producer_id VARCHAR(36),bucket_hour TIMESTAMP,client_kind VARCHAR(24),first_observed_hour TIMESTAMP,last_observed_hour TIMESTAMP,total_requests BIGINT,not_found BIGINT,server_errors BIGINT,unresolved_exceptions BIGINT,flushed_at TIMESTAMP,PRIMARY KEY(producer_id,bucket_hour,client_kind))");
    }
    @Test void retriedAndOutOfOrderSnapshotsAreIdempotentAndMultipleProcessesAddCorrectly() {
        var first = bucket("producer-one", hour, 5, 2, 1); repository.persist(List.of(first)); repository.persist(List.of(first));
        repository.persist(List.of(bucket("producer-one", hour, 3, 1, 0))); repository.persist(List.of(bucket("producer-two", hour, 4, 1, 2)));
        var overview = repository.recentOverview(); assertEquals(true, overview.get("available"));
        var totals = (Map<?, ?>) overview.get("totals"); assertEquals(9L, totals.get("requests")); assertEquals(3L, totals.get("notFound")); assertEquals(3L, totals.get("serverErrors"));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM seo_http_hourly", Integer.class));
    }
    @Test void oldHistoryAndUnbucketedOutageTotalsArePreservedButNotMisrepresentedAsTheRecentWindow() {
        repository.persist(List.of(bucket("producer", hour, 7, 1, 1), bucket("producer", hour.minusDays(30), 20, 10, 3), bucket("producer", SeoHttpStatsRepository.OVERFLOW_HOUR, 100, 4, 8)));
        var overview = repository.recentOverview();
        assertEquals(7L, ((Map<?, ?>) overview.get("totals")).get("requests"));
        assertEquals(100L, ((Map<?, ?>) overview.get("unbucketedOutageTotals")).get("requests"));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM seo_http_hourly", Integer.class));
        assertEquals(1, ((List<?>) overview.get("hourly")).size()); assertEquals(168, overview.get("windowHours"));
    }
    @Test void missingMigrationReturnsAnExplicitUnavailableStatusWithoutFailingTheDashboard() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:missing_" + UUID.randomUUID());
        assertEquals(false, new SeoHttpStatsRepository(source).recentOverview().get("available"));
    }
    private SeoHttpStatsRepository.Bucket bucket(String producer, LocalDateTime bucketHour, long total, long missing, long errors) {
        return new SeoHttpStatsRepository.Bucket(producer, bucketHour, "googlebot", hour, hour, total, missing, errors, 0);
    }
}
