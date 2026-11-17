package com.java2nb.novel.visitor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Durable anonymous aggregates. The singleton row serializes visits across all application instances. */
@Repository
public class VisitorStatsRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired public VisitorStatsRepository(DataSource source) { this(source, Clock.systemUTC()); }
    public VisitorStatsRepository(DataSource source, Clock clock) {
        this.jdbc = new JdbcTemplate(source);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.clock = clock;
    }

    public Totals totals() {
        // Return DATETIME as database text before ShardingSphere can convert it through a JVM-zone Timestamp.
        return jdbc.query("SELECT visitors,page_views,CAST(started_at AS CHAR(26)) AS started_at FROM site_visitor_stats WHERE id=1", (row, n) ->
            new Totals(row.getLong("visitors"), row.getLong("page_views"),
                datetime(row.getString("started_at")))).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Apply add-visitor-stats.sql before enabling visitor statistics"));
    }

    private static LocalDateTime datetime(String value) {
        return value == null ? null : LocalDateTime.parse(value.trim().replace(' ', 'T'));
    }

    public Totals visit(String visitorHash, String pageViewHash) {
        validateHash(visitorHash); validateHash(pageViewHash);
        return transaction.execute(status -> {
            // This lock precedes every visit row, so different tabs and processes cannot race UV/PV updates.
            jdbc.queryForObject("SELECT id FROM site_visitor_stats WHERE id=1 FOR UPDATE", Integer.class);
            var existing = jdbc.query("SELECT visitor_hash FROM site_visitor_pageview WHERE pageview_hash=?",
                (row, n) -> row.getString(1), pageViewHash);
            if (!existing.isEmpty()) {
                if (!visitorHash.equals(existing.getFirst())) throw new IllegalArgumentException("pageViewId belongs to another visitor");
                return totals();
            }
            var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            jdbc.update("INSERT INTO site_visitor_pageview(pageview_hash,visitor_hash,created_at) VALUES(?,?,?)",
                pageViewHash, visitorHash, now);
            boolean firstVisitor = !hasVisitor(visitorHash);
            if (firstVisitor) jdbc.update("INSERT INTO site_visitor_seen(visitor_hash,first_seen_at) VALUES(?,?)", visitorHash, now);
            jdbc.update("UPDATE site_visitor_stats SET visitors=visitors+?,page_views=page_views+1,started_at=COALESCE(started_at,?) WHERE id=1",
                firstVisitor ? 1 : 0, now);
            return totals();
        });
    }

    public boolean hasVisitor(String visitorHash) {
        validateHash(visitorHash);
        return !jdbc.query("SELECT visitor_hash FROM site_visitor_seen WHERE visitor_hash=?", (row, n) -> row.getString(1), visitorHash).isEmpty();
    }

    private static void validateHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid anonymous identifier hash");
    }

    public record Totals(long visitors, long pageViews, LocalDateTime startedAt) {}
}
