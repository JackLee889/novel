package com.java2nb.novel.seo;

import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Anonymous HTTP aggregates only. Never persists request paths, user agents or user identity. */
@Repository
public class SeoHttpStatsRepository {
    public static final int WINDOW_HOURS = 168;
    public static final LocalDateTime OVERFLOW_HOUR = LocalDateTime.of(1970, 1, 1, 0, 0);
    public static final List<String> CLIENT_KINDS = List.of("ordinary", "googlebot", "bingbot", "baiduspider", "other_declared_crawler");
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired public SeoHttpStatsRepository(DataSource source) { this(source, Clock.systemUTC()); }
    public SeoHttpStatsRepository(DataSource source, Clock clock) { this.jdbc = new JdbcTemplate(source); this.clock = clock; }

    /** Absolute process counters make a retried batch safe even when the commit result was lost. */
    @Transactional
    public void persist(List<Bucket> buckets) {
        if (buckets.isEmpty()) return;
        if (buckets.size() > (WINDOW_HOURS + 1) * CLIENT_KINDS.size()) throw new IllegalArgumentException("HTTP aggregate batch exceeds its fixed bound");
        LocalDateTime now = utcNow();
        jdbc.batchUpdate("INSERT INTO seo_http_hourly(producer_id,bucket_hour,client_kind,first_observed_hour,last_observed_hour,total_requests,not_found,server_errors,unresolved_exceptions,flushed_at) VALUES(?,?,?,?,?,?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE first_observed_hour=LEAST(first_observed_hour,VALUES(first_observed_hour)),last_observed_hour=GREATEST(last_observed_hour,VALUES(last_observed_hour)),"
            + "total_requests=GREATEST(total_requests,VALUES(total_requests)),not_found=GREATEST(not_found,VALUES(not_found)),server_errors=GREATEST(server_errors,VALUES(server_errors)),unresolved_exceptions=GREATEST(unresolved_exceptions,VALUES(unresolved_exceptions)),flushed_at=VALUES(flushed_at)",
            buckets, buckets.size(), (statement, b) -> {
                if (!CLIENT_KINDS.contains(b.clientKind()) || b.totalRequests() < 0 || b.notFound() < 0 || b.serverErrors() < 0 || b.unresolvedExceptions() < 0) throw new IllegalArgumentException("Invalid anonymous HTTP aggregate");
                statement.setString(1, b.producerId()); statement.setObject(2, b.hour()); statement.setString(3, b.clientKind());
                statement.setObject(4, b.firstHour()); statement.setObject(5, b.lastHour());
                statement.setLong(6, b.totalRequests()); statement.setLong(7, b.notFound()); statement.setLong(8, b.serverErrors());
                statement.setLong(9, b.unresolvedExceptions()); statement.setObject(10, now);
            });
    }

    /** 168 UTC calendar-hour buckets (current partial hour included); older aggregates remain intact. */
    public Map<String, Object> recentOverview() {
        LocalDateTime now = utcNow(), end = now.truncatedTo(ChronoUnit.HOURS).plusHours(1), start = end.minusHours(WINDOW_HOURS);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowHours", WINDOW_HOURS); result.put("timezone", "UTC"); result.put("windowStart", iso(start)); result.put("windowEndExclusive", iso(end));
        result.put("semantics", "前台所有 HTTP 请求（含静态资源、HEAD、探测）；404/5xx 按请求结束时的最终响应状态统计。UA 声明的爬虫未经身份验证，不代表收录或独立访客。");
        result.put("flushIntervalSeconds", 30); result.put("historicalAggregatesPreserved", true);
        try {
            String sums = "COALESCE(SUM(total_requests),0) total_requests,COALESCE(SUM(not_found),0) not_found,COALESCE(SUM(server_errors),0) server_errors,COALESCE(SUM(unresolved_exceptions),0) unresolved_exceptions";
            result.put("totals", jdbc.query("SELECT " + sums + " FROM seo_http_hourly WHERE bucket_hour>=? AND bucket_hour<?", (r, n) -> counts(r), start, end).getFirst());
            result.put("hourly", jdbc.query("SELECT bucket_hour," + sums + " FROM seo_http_hourly WHERE bucket_hour>=? AND bucket_hour<? GROUP BY bucket_hour ORDER BY bucket_hour DESC", (r, n) -> {
                Map<String, Object> row = counts(r); row.put("hour", iso(r.getObject("bucket_hour", LocalDateTime.class))); return row;
            }, start, end));
            result.put("clients", jdbc.query("SELECT client_kind," + sums + " FROM seo_http_hourly WHERE bucket_hour>=? AND bucket_hour<? GROUP BY client_kind ORDER BY client_kind", (r, n) -> {
                Map<String, Object> row = counts(r); row.put("clientKind", r.getString("client_kind")); return row;
            }, start, end));
            // A multi-day outage may overflow the fixed memory ring. These are deliberately separate:
            // their time range can cross the dashboard window and cannot be apportioned accurately.
            result.put("unbucketedOutageTotals", jdbc.query("SELECT " + sums + ",MIN(first_observed_hour) first_hour,MAX(last_observed_hour) last_hour FROM seo_http_hourly WHERE bucket_hour=?", (r, n) -> {
                Map<String, Object> row = counts(r); row.put("firstHour", iso(r.getObject("first_hour", LocalDateTime.class))); row.put("lastHour", iso(r.getObject("last_hour", LocalDateTime.class))); return row;
            }, OVERFLOW_HOUR).getFirst());
            LocalDateTime flushed = jdbc.queryForObject("SELECT MAX(flushed_at) FROM seo_http_hourly", LocalDateTime.class);
            result.put("lastFlushedAt", iso(flushed)); result.put("flushAgeSeconds", flushed == null ? null : Math.max(0, ChronoUnit.SECONDS.between(flushed, now)));
            result.put("available", true);
        } catch (DataAccessException ex) {
            result.put("available", false); result.put("message", "HTTP 聚合统计暂不可用，请检查增量迁移与数据库连接；此状态不影响小说页面。");
        }
        return result;
    }

    private LocalDateTime utcNow() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private static String iso(LocalDateTime value) { return value == null ? null : value.toString() + "Z"; }
    private static Map<String, Object> counts(ResultSet row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("requests", row.getLong("total_requests")); result.put("notFound", row.getLong("not_found"));
        result.put("serverErrors", row.getLong("server_errors")); result.put("unresolvedExceptions", row.getLong("unresolved_exceptions")); return result;
    }

    public record Bucket(String producerId, LocalDateTime hour, String clientKind, LocalDateTime firstHour, LocalDateTime lastHour,
                         long totalRequests, long notFound, long serverErrors, long unresolvedExceptions) {}
}
