package com.java2nb.novel.visitor;

import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.driver.jdbc.core.datasource.ShardingSphereDataSource;
import org.apache.shardingsphere.infra.config.mode.ModeConfiguration;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.mode.repository.standalone.StandalonePersistRepositoryConfiguration;
import org.apache.shardingsphere.single.config.SingleRuleConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses existing additive-migration tables in a dedicated local QA schema; never creates or deletes tables/rows. */
class VisitorStatsShardingIntegrationTest {
    @Test void actualSingleWildcardRoutesAllVisitorTablesAndPersistsOneIdempotentVisit() throws Exception {
        String url = System.getenv("VISITOR_STATS_MYSQL_TEST_URL");
        assumeTrue(url != null && !url.isBlank(), "Dedicated visitor MySQL integration database not configured");
        assertTrue(url.startsWith("jdbc:mysql://"), "Only a dedicated MySQL QA database is permitted");
        URI uri = URI.create(url.substring("jdbc:".length()));
        assertNull(uri.getUserInfo(), "Credentials must be passed separately from the QA URL");
        assertTrue("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()), "QA server must be local");
        assertNotNull(uri.getPath(), "QA URL requires a dedicated database name");
        String catalog = uri.getPath().substring(1);
        assertTrue(catalog.matches("novel_visitor_qa_[0-9]+"), "QA schema name must be novel_visitor_qa_<digits>");
        String query = uri.getRawQuery();
        assertTrue(query == null || !query.matches("(?i).*(?:^|&)(?:password|user|username)=.*"),
            "Credentials must be passed separately from the QA URL");

        var physical = new DriverManagerDataSource(url,
            System.getenv().getOrDefault("VISITOR_STATS_MYSQL_TEST_USER", "root"),
            System.getenv().getOrDefault("VISITOR_STATS_MYSQL_TEST_PASSWORD", ""));
        try (var connection = physical.getConnection()) {
            assertEquals(catalog, connection.getCatalog(), "Verify target before any visitor write");
        }
        var directJdbc = new JdbcTemplate(physical);
        // The root applies the additive migration beforehand; this test deliberately contains no DDL.
        for (String table : List.of("site_visitor_stats", "site_visitor_seen", "site_visitor_pageview"))
            assertNotNull(directJdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class), "Apply the visitor migration to the dedicated QA schema first");

        Map<String, DataSource> sources = Map.of("ds_1", physical);
        List<RuleConfiguration> rules = List.of(new SingleRuleConfiguration(List.of("*.*"), null));
        var properties = new Properties();
        properties.setProperty("sql-show", "false");
        // Programmatic configuration keeps credentials out of YAML, files and statement logging.
        // Match Standalone + JDBC metadata persistence while isolating metadata from every existing store.
        // This unique in-memory H2 URL never selects the user's default ~/.shardingsphere repository.
        var metadata = new Properties();
        metadata.setProperty("provider", "H2");
        metadata.setProperty("jdbc_url", "jdbc:h2:mem:visitor_metadata_" + UUID.randomUUID().toString().replace("-", "")
            + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false;MODE=MYSQL");
        metadata.setProperty("username", "sa");
        metadata.setProperty("password", "");
        var mode = new ModeConfiguration("Standalone", new StandalonePersistRepositoryConfiguration("JDBC", metadata));
        DataSource created = ShardingSphereDataSourceFactory.createDataSource(
            "visitor_qa_" + UUID.randomUUID().toString().replace("-", ""), mode, sources, rules, properties);
        assertInstanceOf(ShardingSphereDataSource.class, created);
        try (var sharding = (ShardingSphereDataSource) created) {
            var routedJdbc = new JdbcTemplate(sharding);
            for (String table : List.of("site_visitor_stats", "site_visitor_seen", "site_visitor_pageview"))
                assertEquals(directJdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
                    routedJdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class), "SINGLE wildcard must route each existing visitor table");

            var repository = new VisitorStatsRepository(sharding);
            var before = repository.totals();
            String visitor = VisitorStatsService.hashId(UUID.randomUUID().toString());
            String page = VisitorStatsService.hashId(UUID.randomUUID().toString());
            var added = repository.visit(visitor, page);
            assertEquals(before.visitors() + 1, added.visitors());
            assertEquals(before.pageViews() + 1, added.pageViews());
            assertNotNull(added.startedAt());
            if (before.startedAt() != null) assertEquals(before.startedAt(), added.startedAt());
            assertTrue(repository.hasVisitor(visitor));

            // A fresh repository and another connection must see the committed markers and retry without increments.
            var retried = new VisitorStatsRepository(sharding).visit(visitor, page);
            assertEquals(added, retried);
            assertEquals(added, new VisitorStatsRepository(physical).totals(), "The routed commit must persist in the physical QA database");
            assertEquals(1L, directJdbc.queryForObject("SELECT COUNT(*) FROM site_visitor_seen WHERE visitor_hash=?", Long.class, visitor));
            assertEquals(visitor, directJdbc.queryForObject("SELECT visitor_hash FROM site_visitor_pageview WHERE pageview_hash=?", String.class, page));
            assertEquals(1L, routedJdbc.queryForObject("SELECT COUNT(*) FROM site_visitor_pageview WHERE pageview_hash=?", Long.class, page));
        }
    }
}
