-- Additive scheduling metadata. No existing book, chapter or queue row is rewritten.
CREATE TABLE IF NOT EXISTS crawl_book_refresh (
 book_id BIGINT NOT NULL PRIMARY KEY,
 source_id INT NOT NULL,
 source_book_id VARCHAR(128) NOT NULL,
 active_serial TINYINT NOT NULL DEFAULT 1,
 priority_rank TINYINT NOT NULL DEFAULT 2,
 next_check_at DATETIME(3) NOT NULL,
 last_enqueued_at DATETIME(3) NOT NULL,
 last_attempted_at DATETIME(3) NULL,
 last_checked_at DATETIME(3) NULL,
 last_saved_at DATETIME(3) NULL,
 observed_count INT NOT NULL DEFAULT 0,
 catalog_hash CHAR(64) NULL,
 pending_from INT NULL,
 unchanged_checks INT NOT NULL DEFAULT 0,
 failures INT NOT NULL DEFAULT 0,
 KEY ix_refresh_source_due (source_id,priority_rank,next_check_at,book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
