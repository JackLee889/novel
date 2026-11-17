package com.java2nb.novel.seo;

/** Versioned settings. The public origin is deployment configuration, never a request Host header. */
public record SeoSiteSettings(long epoch, boolean indexEnabled, boolean chaptersIndexEnabled,
    boolean chapterSitemapEnabled, String defaultTitle, String defaultDescription, String keywords,
    String baiduVerification, String googleVerification) {
    public static SeoSiteSettings disabled() {
        return new SeoSiteSettings(0, false, false, false, "", "", "", "", "");
    }
}
