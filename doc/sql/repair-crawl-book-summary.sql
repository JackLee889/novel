-- MySQL 8: repair only crawler-book summaries from chapters already present locally.
-- Stop the affected crawler workers before running; keep application/database backups.
-- This does not fetch, insert, delete or rewrite chapter content.
-- Preview affected rows first (the SELECT below also runs when executing this file).
SELECT b.id, b.book_name, b.last_index_id AS previous_id, b.last_index_name AS previous_name,
       i.id AS persisted_latest_id, i.index_name AS persisted_latest_name,
       b.word_count AS previous_word_count, t.total_word_count
FROM book b
JOIN (SELECT book_id, MAX(index_num) AS latest_num, COALESCE(SUM(word_count),0) AS total_word_count
      FROM book_index GROUP BY book_id) t ON t.book_id = b.id
JOIN book_index i ON i.book_id = t.book_id AND i.index_num = t.latest_num
WHERE b.crawl_source_id IS NOT NULL
  AND (NOT (b.last_index_id <=> i.id)
       OR NOT (b.last_index_name <=> IF(CHAR_LENGTH(i.index_name)>50,
           CONCAT(LEFT(i.index_name,49),'…'),i.index_name))
       OR b.last_index_update_time IS NULL
       OR NOT (b.word_count <=> t.total_word_count));

-- Preserve the original summaries once; rerunning never overwrites these backup rows.
CREATE TABLE IF NOT EXISTS crawl_book_summary_backup_20261004 (
    id BIGINT NOT NULL PRIMARY KEY,
    last_index_id BIGINT NULL,
    last_index_name VARCHAR(100) NULL,
    last_index_update_time DATETIME NULL,
    word_count INT NULL,
    update_time DATETIME NULL,
    backup_time DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;

INSERT IGNORE INTO crawl_book_summary_backup_20261004
    (id,last_index_id,last_index_name,last_index_update_time,word_count,update_time,backup_time)
SELECT b.id,b.last_index_id,b.last_index_name,b.last_index_update_time,b.word_count,b.update_time,NOW()
FROM book b
JOIN (SELECT book_id,MAX(index_num) AS latest_num,COALESCE(SUM(word_count),0) AS total_word_count
      FROM book_index GROUP BY book_id) t ON t.book_id=b.id
JOIN book_index i ON i.book_id=t.book_id AND i.index_num=t.latest_num
WHERE b.crawl_source_id IS NOT NULL
  AND (NOT (b.last_index_id <=> i.id)
       OR NOT (b.last_index_name <=> IF(CHAR_LENGTH(i.index_name)>50,
           CONCAT(LEFT(i.index_name,49),'…'),i.index_name))
       OR b.last_index_update_time IS NULL
       OR NOT (b.word_count <=> t.total_word_count));

UPDATE book b
JOIN (SELECT book_id,MAX(index_num) AS latest_num,COALESCE(SUM(word_count),0) AS total_word_count
      FROM book_index GROUP BY book_id) t ON t.book_id=b.id
JOIN book_index i ON i.book_id=t.book_id AND i.index_num=t.latest_num
SET b.last_index_update_time=IF(b.last_index_id <=> i.id,
        COALESCE(b.last_index_update_time,i.create_time,i.update_time,b.create_time,NOW()),
        COALESCE(i.create_time,i.update_time,b.last_index_update_time,b.create_time,NOW())),
    b.last_index_id=i.id,
    b.last_index_name=IF(CHAR_LENGTH(i.index_name)>50,CONCAT(LEFT(i.index_name,49),'…'),i.index_name),
    b.word_count=t.total_word_count,
    b.update_time=NOW()
WHERE b.crawl_source_id IS NOT NULL
  AND (NOT (b.last_index_id <=> i.id)
       OR NOT (b.last_index_name <=> IF(CHAR_LENGTH(i.index_name)>50,
           CONCAT(LEFT(i.index_name,49),'…'),i.index_name))
       OR b.last_index_update_time IS NULL
       OR NOT (b.word_count <=> t.total_word_count));

SELECT ROW_COUNT() AS repaired_books;
COMMIT;

-- After commit, remove ONLY these front-end cache keys from the matching Redis database:
-- indexUpdateBookKey, indexNewBookKey, indexClickBankBookKey, indexBookSettingsKey:v2.
-- Alternatively let the cache TTL expire. Do not flush the whole Redis database.
-- This script repairs metadata visibility, not missing chapter bodies or source-ID mappings.
