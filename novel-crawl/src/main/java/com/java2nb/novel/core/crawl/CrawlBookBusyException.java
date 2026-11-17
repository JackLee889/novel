package com.java2nb.novel.core.crawl;

/** Another collector owns this book; retry later without counting a failed download. */
public final class CrawlBookBusyException extends RuntimeException {
    public CrawlBookBusyException(Long bookId) {
        super("Book collection already in progress: " + bookId);
    }
}
