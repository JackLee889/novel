package com.java2nb.novel.seo;

import com.java2nb.novel.entity.Book;
import org.springframework.web.util.HtmlUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Pure metadata rules shared by public pages and the administration preview. */
public final class SeoText {
    private SeoText() {}

    public static String plain(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1\\s*>", "")
            .replaceAll("(?i)<br\\s*/?>|</(?:p|div|li|h[1-6])\\s*>", " ")
            .replaceAll("(?s)<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(clean).replaceAll("[\\p{Cntrl}\\s\\u00a0]+", " ").strip();
    }

    public static String excerpt(String value) {
        return plain(value).codePoints().limit(160)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    /** A blank siteName returns the base title for a caller that adds its own suffix. */
    public static String bookTitle(Book book, SeoBookOverride override, String siteName) {
        String title = plain(value(override == null ? null : override.title(),
            String.join(" - ", nonBlank(book.getBookName(), book.getAuthorName()))));
        String name = plain(siteName);
        return name.isBlank() ? title : title + " - " + name;
    }

    public static String bookDescription(Book book, SeoBookOverride override) {
        return excerpt(value(override == null ? null : override.description(), book.getBookDesc()));
    }

    public static String bookKeywords(Book book, SeoBookOverride override) {
        return plain(value(override == null ? null : override.keywords(),
            String.join(",", nonBlank(book.getBookName(), book.getAuthorName(), book.getCatName(), "小说阅读"))));
    }

    private static String value(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : Objects.toString(fallback, "");
    }

    private static List<String> nonBlank(String... values) {
        return Arrays.stream(values).filter(Objects::nonNull).map(SeoText::plain)
            .filter(text -> !text.isBlank()).distinct().toList();
    }
}
