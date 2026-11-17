package com.java2nb.novel.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Request priority and a bounded cache live only for one owned crawl task. */
public final class CrawlRequestContext implements AutoCloseable {
    public enum Kind { REFRESH, MANUAL, BACKGROUND, SCAN }
    private static final ThreadLocal<CrawlRequestContext> CURRENT = new ThreadLocal<>();
    private final CrawlRequestContext previous;
    private final Kind kind;
    private final String bookId, title, author;
    private final Map<String,String> pages = new LinkedHashMap<>();

    private CrawlRequestContext(Kind kind, String bookId, String title, String author) {
        this.previous=CURRENT.get(); this.kind=kind; this.bookId=bookId; this.title=title; this.author=author;
        CURRENT.set(this);
    }
    public static CrawlRequestContext open(Kind kind) { return new CrawlRequestContext(kind,null,null,null); }
    public static CrawlRequestContext bound(String bookId,String title,String author) {
        return bound(Kind.REFRESH,bookId,title,author);
    }
    public static CrawlRequestContext bound(Kind kind,String bookId,String title,String author) {return new CrawlRequestContext(kind,bookId,title,author);}
    public static boolean refresh() { return CURRENT.get()!=null && CURRENT.get().kind==Kind.REFRESH; }
    public static int priority() {
        var c=CURRENT.get(); return c==null?1:switch(c.kind) {case REFRESH,MANUAL->0;case BACKGROUND->1;case SCAN->2;};
    }
    public static boolean yieldOnChallenge() { return CURRENT.get()!=null; }
    public static String boundAuthor(String bookId,String title) {
        var c=CURRENT.get(); return c!=null && Objects.equals(c.bookId,bookId) && Objects.equals(c.title,title)
            && c.author!=null && !c.author.isBlank()
            && !com.java2nb.novel.core.crawl.CrawlBookMetadata.pendingAuthor(c.author)?c.author:null;
    }
    public static String cached(String url,String charset) {
        var c=CURRENT.get();return c==null?null:c.pages.get(charset+"\n"+url);
    }
    public static void remember(String url,String charset,String body) {
        var c=CURRENT.get();
        // Two small pages cover the common detail==catalog case, without retaining chapter bodies.
        if(c!=null && (c.kind==Kind.REFRESH || c.bookId!=null) && c.pages.size()<2 && body!=null && body.length()<=1048576)
            c.pages.put(charset+"\n"+url,body);
    }
    @Override public void close() { pages.clear();if(previous==null)CURRENT.remove();else CURRENT.set(previous); }
}
