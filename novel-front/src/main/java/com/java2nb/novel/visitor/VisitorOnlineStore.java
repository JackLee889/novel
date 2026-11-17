package com.java2nb.novel.visitor;

/** Redis holds only the short activity window; historical counters never depend on Redis. */
public interface VisitorOnlineStore {
    int WINDOW_SECONDS = 90;
    void touch(String visitorHash);
    long count();
}
