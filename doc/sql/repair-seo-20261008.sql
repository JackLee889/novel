-- Additive SEO metadata only. Apply to the selected deployment database after a backup.
-- Publishing books is a separate explicit operation; this migration never changes book.status.
ALTER TABLE website_info MODIFY COLUMN keyword VARCHAR(300) NOT NULL COMMENT 'SEO关键词';
CREATE TABLE IF NOT EXISTS seo_site_config (
  id TINYINT NOT NULL PRIMARY KEY,
  epoch BIGINT NOT NULL DEFAULT 1,
  index_enabled TINYINT NOT NULL DEFAULT 0,
  chapters_index_enabled TINYINT NOT NULL DEFAULT 0,
  chapter_sitemap_enabled TINYINT NOT NULL DEFAULT 0,
  default_title VARCHAR(200) NOT NULL DEFAULT '',
  default_description VARCHAR(500) NOT NULL DEFAULT '',
  keywords VARCHAR(300) NOT NULL DEFAULT '',
  baidu_verification VARCHAR(256) NOT NULL DEFAULT '',
  google_verification VARCHAR(256) NOT NULL DEFAULT '',
  modified_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
      
CREATE TABLE IF NOT EXISTS seo_book_override (
  book_id BIGINT NOT NULL PRIMARY KEY,
  index_enabled TINYINT NULL,
  title VARCHAR(200) NOT NULL DEFAULT '',
  description VARCHAR(500) NOT NULL DEFAULT '',
  keywords VARCHAR(300) NOT NULL DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS seo_book_state (
  book_id BIGINT NOT NULL PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 1,
  built_revision BIGINT NOT NULL DEFAULT 0,
  built_epoch BIGINT NOT NULL DEFAULT 0,
  modified_at DATETIME(3) NULL DEFAULT NULL,
  job_id VARCHAR(36) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NULL,
  lease_until DATETIME(3) NULL,
  lease_token VARCHAR(36) NULL,
  built_files TEXT NULL,
  built_origin VARCHAR(300) NULL,
  url_count BIGINT NOT NULL DEFAULT 0,
  chapter_count BIGINT NOT NULL DEFAULT 0,
  last_error VARCHAR(300) NULL,
  completed_at DATETIME(3) NULL,
  KEY seo_pending (status,next_attempt_at,book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS seo_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_id BIGINT NULL,
  action VARCHAR(40) NOT NULL,
  book_id BIGINT NULL,
  before_data TEXT NOT NULL,
  after_data TEXT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS seo_sitemap_control (
  id TINYINT NOT NULL PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 1,
  visibility_revision BIGINT NOT NULL DEFAULT 1,
  built_revision BIGINT NOT NULL DEFAULT 0,
  built_visibility_revision BIGINT NOT NULL DEFAULT 0,
  built_epoch BIGINT NOT NULL DEFAULT 0,
  built_origin VARCHAR(300) NULL,
  generation VARCHAR(36) NULL,
  index_file VARCHAR(200) NULL,
  core_files TEXT NULL,
  modified_at DATETIME(3) NULL,
  index_hash VARCHAR(64) NULL,
  url_count BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    
CREATE TABLE IF NOT EXISTS seo_book_visibility (
  book_id BIGINT NOT NULL PRIMARY KEY,
  published TINYINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
