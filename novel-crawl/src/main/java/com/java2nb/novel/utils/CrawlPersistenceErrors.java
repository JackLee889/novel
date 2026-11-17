package com.java2nb.novel.utils;

import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Controlled database diagnostics. Never return SQL, bound values or arbitrary driver messages. */
public final class CrawlPersistenceErrors {
    private CrawlPersistenceErrors() { }

    private static final Map<String, String> FIELDS = Map.ofEntries(
        Map.entry("book_desc", "简介"), Map.entry("book_name", "书名"),
        Map.entry("author_name", "作者"), Map.entry("pic_url", "封面地址"),
        Map.entry("cat_name", "分类名"), Map.entry("last_index_name", "最新章节名"),
        Map.entry("index_name", "章节标题"), Map.entry("source_index_id", "来源章节号"),
        Map.entry("crawl_book_id", "来源书号"), Map.entry("index_num", "章节序号"),
        Map.entry("book_id", "图书ID"), Map.entry("index_id", "章节ID"),
        Map.entry("content", "章节正文"));
    private static final Pattern COLUMN = Pattern.compile("(?i)\\bcolumn\\s+['`\"]([a-z_]+)['`\"]");
    private static final Pattern KEY = Pattern.compile("(?i)\\bfor key\\s+['`\"]([a-z0-9_.]+)['`\"]");

    public static String describe(Throwable error) {
        if (error == null) return null;
        var pending = new ArrayDeque<Throwable>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(error);
        boolean integrity = false;
        while (!pending.isEmpty() && visited.size() < 24) {
            Throwable cause = pending.removeFirst();
            if (!visited.add(cause)) continue;
            integrity |= cause instanceof DataIntegrityViolationException;
            if (cause instanceof SQLException sql) {
                String field = field(sql.getMessage());
                String message = switch (sql.getErrorCode()) {
                    case 1406 -> "[DB_FIELD_TOO_LONG] 数据写入失败：字段内容超出容量；字段=" + field;
                    case 1048, 1364 -> "[DB_FIELD_REQUIRED] 数据写入失败：必填字段缺失；字段=" + field;
                    case 1264 -> "[DB_VALUE_OUT_OF_RANGE] 数据写入失败：数值超出字段范围；字段=" + field;
                    case 1062 -> "[DB_DUPLICATE_KEY] 数据写入失败：唯一键冲突；约束=" + uniqueKey(sql.getMessage());
                    case 1451, 1452 -> "[DB_REFERENCE_CONFLICT] 数据写入失败：关联记录约束冲突";
                    default -> null;
                };
                if (message != null) return message;
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            }
            if (cause.getCause() != null) pending.addLast(cause.getCause());
        }
        return integrity ? "[DB_DATA_CONSTRAINT] 数据写入失败：字段或约束校验未通过" : null;
    }

    public static String context(String message, String sourceBookId, String title) {
        return message + (sourceBookId == null ? "" : "；书号=" + clean(sourceBookId, 40))
            + (title == null ? "" : "；书名=" + clean(title, 48));
    }

    private static String field(String message) {
        if (message != null) {
            var match = COLUMN.matcher(message);
            if (match.find()) {
                String name = match.group(1).toLowerCase(java.util.Locale.ROOT);
                if (FIELDS.containsKey(name)) return FIELDS.get(name) + "(" + name + ")";
            }
        }
        return "未识别字段";
    }

    private static String uniqueKey(String message) {
        if (message != null) {
            var match = KEY.matcher(message);
            String key = "";
            while (match.find()) key = match.group(1);
            key = key.substring(key.lastIndexOf('.') + 1);
            if (key.equalsIgnoreCase("key_uq_bookId_indexNum")) return "同一本书的章节序号重复";
            if (key.equalsIgnoreCase("key_uq_bookName_authorName")) return "书名和作者重复";
        }
        return "唯一键重复";
    }

    private static String clean(String text, int limit) {
        String safe = text.replaceAll("[\\p{Cntrl}<>]", " ").strip();
        int end = safe.offsetByCodePoints(0, Math.min(limit, safe.codePointCount(0, safe.length())));
        return safe.substring(0, end);
    }
}
