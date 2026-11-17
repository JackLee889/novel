package com.java2nb.novel.utils;

import java.net.URI;

/** A controlled diagnostic: never include response HTML, headers or URL credentials. */
public final class CrawlMetadataException extends RuntimeException {
    public enum Reason {
        DETAIL_UNAVAILABLE("详情获取失败：未取得有效页面"),
        DETAIL_INVALID("详情解析失败：页面缺少必要结构"),
        SOURCE_ID_MISMATCH("详情核验失败：源站书号不匹配"),
        TITLE_MISSING("书名核验失败：规则和页面元数据均未提供书名"),
        AUTHOR_MISSING("作者核验失败：未找到作者字段"),
        AUTHOR_UNVERIFIED("作者核验失败：页面只提供了占位作者"),
        AUTHOR_LOOKUP_FAILED("作者核验失败：分类和搜索未能确认同书作者"),
        BOOK_IDENTITY_CONFLICT("书目身份冲突：同名待核验图书已绑定其他源站书号，保留原书并等待人工核对"),
        CATALOG_UNAVAILABLE("目录获取失败：未取得有效页面"),
        CATALOG_PAIR_MISMATCH("目录解析失败：章节 ID 与标题未能逐项配对");

        private final String description;
        Reason(String description) { this.description = description; }
    }

    private final Reason reason;

    public CrawlMetadataException(Reason reason, String url, String bookId, String title) {
        super("[" + reason.name() + "] " + reason.description + "；书号=" + clean(bookId, 40)
            + "；书名=" + clean(title, 32) + "；地址=" + safeUrl(url));
        this.reason = reason;
    }

    public Reason getReason() { return reason; }

    public static String safeUrl(String value) {
        try {
            URI url = URI.create(value);
            if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                || url.getHost() == null) return "地址无效";
            String fragment = url.getFragment();
            // The 616 source uses a public route in its fragment. Never retain arbitrary fragment/query data.
            String route = fragment != null && fragment.matches("/book/\\d+") ? "#" + fragment : "";
            return clean(url.getScheme() + "://" + url.getHost()
                + (url.getPort() < 0 ? "" : ":" + url.getPort())
                + (url.getRawPath() == null ? "" : url.getRawPath()) + route, 140);
        } catch (RuntimeException ignored) { return "地址无效"; }
    }

    private static String clean(String value, int limit) {
        if (value == null || value.isBlank()) return "未知";
        String safe = value.replaceAll("[\\p{Cntrl}<>]", " ").strip();
        return safe.length() > limit ? safe.substring(0, limit) : safe;
    }
}
