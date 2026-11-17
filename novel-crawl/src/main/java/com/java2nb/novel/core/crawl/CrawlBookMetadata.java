package com.java2nb.novel.core.crawl;

import com.java2nb.novel.utils.CrawlMetadataException;
import com.java2nb.novel.utils.CrawlRequestContext;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.java2nb.novel.utils.CrawlMetadataException.Reason.*;

/** Common book metadata validation, with no database mutation or additional network requests. */
public final class CrawlBookMetadata {
    public static final String PENDING_AUTHOR = "作者待核验";
    private static final Set<String> PLACEHOLDERS = Set.of("佚名", "未知", "未知作者", "作者未知", "暂无",
        "暂无作者", "无", "不详", "不詳", "作者不详", "作者不詳", PENDING_AUTHOR, "unknown", "null", "undefined", "n/a", "-");

    private CrawlBookMetadata() { }

    public record Detail(String name, String author) { }

    public static String text(String value) {
        return value == null ? "" : Jsoup.parseBodyFragment(value).text().replace('\u00a0', ' ').strip();
    }

    public static String author(String value) {
        return text(value).replaceFirst("^(?:作[\\s\u3000]*者|筆\\s*名|笔\\s*名)\\s*[:：]\\s*", "").strip();
    }

    public static boolean usableAuthor(String value) {
        String normalized = author(value);
        return !normalized.isBlank() && !PLACEHOLDERS.contains(normalized.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\u3000]+", ""));
    }

    public static Detail read(RuleBean rule, String bookId, String url, String html) {
        if (html == null || html.isBlank()) throw new CrawlMetadataException(DETAIL_UNAVAILABLE, url, bookId, null);
        String name = text(match(rule.getBookNamePatten(), html));
        Document page = null;
        if (name.isBlank()) {
            page = Jsoup.parse(html, url);
            name = text(meta(page, "og:novel:book_name"));
        }
        if (name.isBlank()) throw new CrawlMetadataException(TITLE_MISSING, url, bookId, null);

        String bound = CrawlRequestContext.boundAuthor(bookId, name);
        if (bound != null) return new Detail(name, bound);
        String configured = author(match(rule.getAuthorNamePatten(), html));
        if (usableAuthor(configured)) return new Detail(name, configured);
        // UU's normalized detail includes the full catalogue. Its already-verified author and
        // ordinary valid rule matches must not allocate a second DOM for thousands of chapters.
        if (page == null) page = Jsoup.parse(html, url);
        String alternative = author(meta(page, "og:novel:author"));
        Element marked = page.selectFirst("[itemprop=author]");
        String structured = marked == null ? "" : marked.hasAttr("content") ? marked.attr("content") : marked.text();
        return new Detail(name, authorOrPending(url, bookId, name, configured, alternative, structured));
    }

    /** A missing author is metadata to enrich later, not a reason to stop downloading this source book. */
    public static String authorOrPending(String url, String bookId, String name, String... candidates) {
        try { return verifyAuthor(url, bookId, name, candidates); }
        catch (CrawlMetadataException failure) {
            if (failure.getReason() != AUTHOR_MISSING && failure.getReason() != AUTHOR_UNVERIFIED) throw failure;
            return PENDING_AUTHOR;
        }
    }

    public static boolean pendingAuthor(String value) { return PENDING_AUTHOR.equals(value); }

    public static String verifyAuthor(String url, String bookId, String name, String... candidates) {
        // The caller has checked this exact source/book binding. Keep its existing author unchanged.
        String bound = CrawlRequestContext.boundAuthor(bookId, name);
        if (bound != null) return bound;
        boolean supplied = false;
        for (String candidate : candidates) {
            String value = author(candidate);
            supplied |= !value.isBlank();
            if (usableAuthor(value)) return value;
        }
        throw new CrawlMetadataException(supplied ? AUTHOR_UNVERIFIED : AUTHOR_MISSING, url, bookId, name);
    }

    private static String meta(Document page, String key) {
        Element element = page.selectFirst("meta[property=\"" + key + "\"],meta[name=\"" + key + "\"]");
        return element == null ? "" : element.attr("content");
    }

    private static String match(String regex, String html) {
        if (regex == null || regex.isBlank()) return "";
        Matcher matcher = Pattern.compile(regex).matcher(html);
        return matcher.find() && matcher.groupCount() > 0 ? matcher.group(1) : "";
    }
}
