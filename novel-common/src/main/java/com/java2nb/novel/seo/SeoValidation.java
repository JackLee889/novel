package com.java2nb.novel.seo;

import java.net.URI;

public final class SeoValidation {
    private SeoValidation() {}
    public static String publicOrigin(String value) {
        if (value == null || value.isBlank()) return "";
        URI uri;
        try { uri = URI.create(value.strip()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("SEO_SITE_URL must be an HTTPS origin"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("SEO_SITE_URL must be an HTTPS origin without path, credentials or query");
        }
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("0.0.0.0") || host.equals("127.0.0.1") || host.equals("::1"))
            throw new IllegalArgumentException("SEO_SITE_URL must use the public site hostname");
        return "https://" + host + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
    }
    public static String text(String value, int max) {
        String clean = value == null ? "" : value.strip();
        if (clean.codePointCount(0, clean.length()) > max || clean.codePoints().anyMatch(c -> c < 32 && c != '\n' && c != '\t'))
            throw new IllegalArgumentException("SEO text exceeds its limit or contains control characters");
        return clean;
    }
    public static String verification(String value) {
        String clean = text(value, 256);
        if (!clean.matches("[A-Za-z0-9_=-]*")) throw new IllegalArgumentException("Verification must be a token, not HTML or JavaScript");
        return clean;
    }
}
