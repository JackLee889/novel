package com.java2nb.novel.seo;

/** null indexing inherits the site policy; an override can never publish an unpublished book. */
public record SeoBookOverride(long bookId, Boolean indexEnabled, String title, String description, String keywords) {
    public static SeoBookOverride inherited(long bookId) { return new SeoBookOverride(bookId, null, "", "", ""); }
}
