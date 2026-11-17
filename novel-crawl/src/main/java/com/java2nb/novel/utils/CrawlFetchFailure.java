package com.java2nb.novel.utils;

/** Controlled request diagnostics; response HTML, headers and URL secrets are never displayed. */
public record CrawlFetchFailure(Reason reason, int httpStatus, String url, long retryAfterSeconds,
                                boolean deferred) {
    public enum Reason { HTTP, TIMEOUT, CONNECTION, INVALID_PAGE }

    public boolean missing() { return reason == Reason.HTTP && (httpStatus == 404 || httpStatus == 410); }

    public String describe(String bookId, String bookName, String chapterId, String chapterName) {
        String code = reason == Reason.HTTP ? "HTTP_" + httpStatus : reason.name();
        String detail = switch (reason) {
            case HTTP -> missing() ? "源站章节不存在（HTTP " + httpStatus + "）" : "源站请求失败（HTTP " + httpStatus + "）";
            case TIMEOUT -> "源站章节请求超时";
            case CONNECTION -> "源站章节连接失败";
            case INVALID_PAGE -> "未取得有效正文页面或页面结构不匹配";
        };
        return "[CHAPTER_" + code + "] " + detail + "；书号=" + clean(bookId, 40)
            + "；书名=" + clean(bookName, 36) + "；章节=" + clean(chapterName, 48)
            + "；章号=" + clean(chapterId, 40) + "；地址=" + CrawlMetadataException.safeUrl(url)
            + (retryAfterSeconds > 0 ? "；本章延后约" + Math.max(1, (retryAfterSeconds + 59) / 60) + "分钟重试" : "");
    }

    private static String clean(String value, int length) {
        if (value == null || value.isBlank()) return "未知";
        String safe = value.replaceAll("[\\p{Cntrl}<>]", " ").strip();
        return safe.codePointCount(0, safe.length()) <= length ? safe : safe.substring(0, safe.offsetByCodePoints(0, length));
    }
}
