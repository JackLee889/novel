-- Apply once after backup and schema checks. Keep the existing charset, collation and nullability.
-- MySQL 8.0 uses a table copy for this type change; concurrent reads remain available.
-- Application rollback must retain TEXT: narrowing after longer descriptions arrive would lose data.
SET SESSION lock_wait_timeout = 5;
ALTER TABLE novel_plus.book
    MODIFY COLUMN book_desc TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '书籍描述',
    ALGORITHM=COPY, LOCK=SHARED;
