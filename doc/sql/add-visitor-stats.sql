-- Apply to the existing novel business database before starting the updated front service.
-- Additive only: no existing tables/data/configuration are changed. Idempotent to reapply.
-- UTC values are written by the application; started_at marks the first successful browser visit.
CREATE TABLE IF NOT EXISTS site_visitor_stats (
    id TINYINT NOT NULL PRIMARY KEY,
    visitors BIGINT NOT NULL DEFAULT 0,
    page_views BIGINT NOT NULL DEFAULT 0,
    started_at DATETIME(3) NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS site_visitor_seen (
    visitor_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    first_seen_at DATETIME(3) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS site_visitor_pageview (
    pageview_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    visitor_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY ix_site_visitor_pageview_created (created_at)
) ENGINE=InnoDB;

    
