-- Additive migration. Existing books, chapters, contents and original server data are untouched.
CREATE TABLE IF NOT EXISTS crawl_collection_state (
 book_id BIGINT NOT NULL PRIMARY KEY,
 source_id INT NOT NULL,
 source_book_id VARCHAR(128) NOT NULL,
 catalog_hash CHAR(64) NOT NULL,
 expected_count INT NOT NULL,
 ready_count INT NOT NULL DEFAULT 0,
 missing_count INT NOT NULL DEFAULT 0,
 unchecked_count INT NOT NULL DEFAULT 0,
 failed_count INT NOT NULL DEFAULT 0,
 deferred_count INT NOT NULL DEFAULT 0,
 ready_bits MEDIUMBLOB NOT NULL,
 checked_bits MEDIUMBLOB NOT NULL,
 source_completed TINYINT NOT NULL DEFAULT 0,
 collection_status VARCHAR(16) NOT NULL,
 validation_started_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 KEY ix_collection_status (collection_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS crawl_discovery_cursor (
 source_id INT NOT NULL,
 cat_id INT NOT NULL,
 rule_hash CHAR(64) NOT NULL,
 next_page INT NOT NULL DEFAULT 1,
 total_pages INT NOT NULL DEFAULT 1,
 revision BIGINT NOT NULL DEFAULT 0,
 next_scan_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY (source_id, cat_id, rule_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS crawl_discovery_job (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 source_id INT NOT NULL,
 source_book_id VARCHAR(128) NOT NULL,
 cat_id INT NOT NULL,
 policy_hash CHAR(64) NOT NULL,
 book_id BIGINT NULL,
 job_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 consecutive_failures INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(3) NOT NULL,
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(3) NULL,
 last_error VARCHAR(200) NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_discovery_book (source_id, source_book_id),
 KEY ix_discovery_due (job_status, next_attempt_at, id),
 KEY ix_discovery_lease (job_status, lease_until),
 KEY ix_discovery_source (source_id, job_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
