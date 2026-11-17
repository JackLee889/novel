package com.java2nb.novel.utils;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class CrawlPersistenceErrorsTest {
    @Test
    void longDescriptionReportsItsFieldWithoutSqlOrValues() {
        String message = CrawlPersistenceErrors.describe(new DataIntegrityViolationException(
            "INSERT INTO book VALUES ('private')", new SQLException("Data too long for column 'book_desc' at row 1", "22001", 1406)));
        assertTrue(message.contains("DB_FIELD_TOO_LONG"));
        assertTrue(message.contains("简介(book_desc)"));
        assertFalse(message.contains("INSERT"));
        assertFalse(message.contains("private"));
    }

    @Test
    void nullAndMissingDefaultsIdentifyTheRequiredColumn() {
        for (int code : new int[]{1048, 1364}) {
            String message = CrawlPersistenceErrors.describe(new SQLException("Column 'author_name' cannot be null", "23000", code));
            assertTrue(message.contains("DB_FIELD_REQUIRED"));
            assertTrue(message.contains("作者(author_name)"));
        }
    }

    @Test
    void outOfRangeAndReferenceConflictsHaveSpecificCodes() {
        assertTrue(CrawlPersistenceErrors.describe(new SQLException("Out of range value for column 'index_num' at row 1", "22003", 1264)).contains("章节序号(index_num)"));
        for (int code : new int[]{1451, 1452}) {
            assertTrue(CrawlPersistenceErrors.describe(new SQLException("secret SQL", "23000", code)).contains("DB_REFERENCE_CONFLICT"));
        }
    }

    @Test
    void chapterAndBookDuplicatesDoNotExposeTheConflictingValue() {
        for (String key : new String[]{"key_uq_bookId_indexNum", "key_uq_bookName_authorName"}) {
            String message = CrawlPersistenceErrors.describe(new SQLException("Duplicate entry 'secret' for key 'book." + key + "'", "23000", 1062));
            assertTrue(message.contains("DB_DUPLICATE_KEY"));
            assertFalse(message.contains("secret"));
            assertFalse(message.contains("唯一键重复"));
        }
        String unknown = CrawlPersistenceErrors.describe(new SQLException("Duplicate entry 'secret key_uq_bookId_indexNum' for key 'private_key'", "23000", 1062));
        assertTrue(unknown.contains("唯一键重复"));
        assertFalse(unknown.contains("章节序号"));
        assertFalse(unknown.contains("private_key"));
    }

    @Test
    void unknownColumnsAndRawDatabaseTextRemainPrivate() {
        String message = CrawlPersistenceErrors.describe(new SQLException("Data too long for column 'password' https://secret?token=private", "22001", 1406));
        assertTrue(message.contains("未识别字段"));
        assertFalse(message.contains("password"));
        assertFalse(message.contains("https://"));
        assertFalse(message.contains("private"));
    }

    @Test
    void batchNextExceptionsAreClassifiedAndCauseCyclesAreBounded() {
        var batch = new SQLException("batch private");
        batch.setNextException(new SQLException("Data too long for column 'index_name' at row 1", "22001", 1406));
        assertTrue(CrawlPersistenceErrors.describe(new RuntimeException("wrapper", batch)).contains("章节标题(index_name)"));
        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second);
        assertNull(CrawlPersistenceErrors.describe(first));
    }

    @Test
    void unknownIntegrityFailuresAreSafeAndLockFailuresKeepTheirExistingHandling() {
        assertTrue(CrawlPersistenceErrors.describe(new DataIntegrityViolationException("SQL secret")).contains("DB_DATA_CONSTRAINT"));
        assertNull(CrawlPersistenceErrors.describe(new SQLException("deadlock", "40001", 1213)));
        assertNull(CrawlPersistenceErrors.describe(new SQLException("timeout", "HY000", 1205)));
        assertNull(CrawlPersistenceErrors.describe(new IllegalArgumentException("secret")));
        assertNull(CrawlPersistenceErrors.describe(null));
    }

    @Test
    void contextIsBoundedAndDoesNotSplitSupplementaryCharacters() {
        String message = CrawlPersistenceErrors.context("[DB_FIELD_TOO_LONG]", "530222\n<script>", "字😀".repeat(60));
        assertFalse(message.contains("\n"));
        assertFalse(message.contains("<"));
        String title = message.substring(message.indexOf("；书名=") + 4);
        assertEquals(48, title.codePointCount(0, title.length()));
        assertFalse(Character.isHighSurrogate(title.charAt(title.length() - 1)));
    }
}
