package com.java2nb.novel.utils;

/** 验证页或限流不能交给正文解析器，也不能立即重试。 */
public class CrawlAccessChallengeException extends RuntimeException {
    private final long retryAfterSeconds;

    public CrawlAccessChallengeException(long retryAfterSeconds) {
        super("源站要求验证或限制访问，暂停请求 " + retryAfterSeconds + " 秒后再试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
