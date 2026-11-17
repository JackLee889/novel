-- Additive and idempotent. Apply after repair-seo-20261008.sql; no business rows are changed.
-- All date/time values are explicitly UTC, independent of the database session timezone.
-- Only aggregates are stored: no URL, UA string, IP, cookie, session or request/response body.
-- History is retained. No DELETE/TRUNCATE, scheduled retention cleanup, or disk operation.
CREATE TABLE IF NOT EXISTS seo_http_hourly (
    producer_id VARCHAR(36) NOT NULL COMMENT 'Random application-process identity, never a visitor identity',
    bucket_hour DATETIME NOT NULL COMMENT 'UTC hour; 1970-01-01 is an explicitly unbucketed outage aggregate',
    client_kind VARCHAR(24) NOT NULL COMMENT 'Finite UA-declared hints; crawler identity has not been verified',
    first_observed_hour DATETIME NOT NULL,
    last_observed_hour DATETIME NOT NULL,
    total_requests BIGINT UNSIGNED NOT NULL DEFAULT 0,
    not_found BIGINT UNSIGNED NOT NULL DEFAULT 0,
    server_errors BIGINT UNSIGNED NOT NULL DEFAULT 0,
    unresolved_exceptions BIGINT UNSIGNED NOT NULL DEFAULT 0,
    flushed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (producer_id, bucket_hour, client_kind),
    KEY idx_seo_http_hour (bucket_hour),
    KEY idx_seo_http_flushed (flushed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Anonymous HTTP hourly counters; cumulative idempotent batch synchronization';
