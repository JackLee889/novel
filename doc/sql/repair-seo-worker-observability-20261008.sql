-- Additive MySQL 8 worker diagnostics and reconciliation baseline.
-- Apply AFTER repair-seo-20261008.sql, on both fresh and existing deployments.
-- Repeatable: checks every column; preserves books, content, publication flags,
-- SEO revisions, previous manifests and their modification clocks. No rebuild is queued.
SELECT book_id,revision FROM seo_book_state LIMIT 0;
SELECT id,revision FROM seo_sitemap_control LIMIT 0;
SET @seo_worker_lock_name=CONCAT('novel-seo-worker-v1:',MD5(DATABASE()));
SET @seo_worker_lock=GET_LOCK(@seo_worker_lock_name,10);
SET @seo_worker_ddl=IF(@seo_worker_lock=1,'SELECT 1','SELECT * FROM information_schema.seo_worker_migration_lock_refused');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;

SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_book_state' AND column_name='started_at'),'SELECT 1','ALTER TABLE seo_book_state ADD COLUMN started_at DATETIME(3) NULL');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;
SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_book_state' AND column_name='duration_ms'),'SELECT 1','ALTER TABLE seo_book_state ADD COLUMN duration_ms BIGINT NULL');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;
SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_book_state' AND column_name='observed_hash'),'SELECT 1','ALTER TABLE seo_book_state ADD COLUMN observed_hash VARCHAR(64) NULL');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;

SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_sitemap_control' AND column_name='status'),'SELECT 1','ALTER TABLE seo_sitemap_control ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''idle''');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;
SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_sitemap_control' AND column_name='started_at'),'SELECT 1','ALTER TABLE seo_sitemap_control ADD COLUMN started_at DATETIME(3) NULL');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;
SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_sitemap_control' AND column_name='completed_at'),'SELECT 1','ALTER TABLE seo_sitemap_control ADD COLUMN completed_at DATETIME(3) NULL');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;
SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_sitemap_control' AND column_name='duration_ms'),'SELECT 1','ALTER TABLE seo_sitemap_control ADD COLUMN duration_ms BIGINT NULL');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;
SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_sitemap_control' AND column_name='last_error'),'SELECT 1','ALTER TABLE seo_sitemap_control ADD COLUMN last_error VARCHAR(300) NULL');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;
SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_sitemap_control' AND column_name='next_attempt_at'),'SELECT 1','ALTER TABLE seo_sitemap_control ADD COLUMN next_attempt_at DATETIME(3) NULL');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;
SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_sitemap_control' AND column_name='lease_until'),'SELECT 1','ALTER TABLE seo_sitemap_control ADD COLUMN lease_until DATETIME(3) NULL');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;
SET @seo_worker_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='seo_sitemap_control' AND column_name='lease_token'),'SELECT 1','ALTER TABLE seo_sitemap_control ADD COLUMN lease_token VARCHAR(36) NULL');
PREPARE seo_worker_statement FROM @seo_worker_ddl;
EXECUTE seo_worker_statement;
DEALLOCATE PREPARE seo_worker_statement;

SELECT RELEASE_LOCK(@seo_worker_lock_name);
