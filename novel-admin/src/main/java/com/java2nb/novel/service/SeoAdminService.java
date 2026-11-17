package com.java2nb.novel.service;

import com.java2nb.novel.seo.SeoBookOverride;
import com.java2nb.novel.seo.SeoHttpStatsRepository;
import com.java2nb.novel.seo.SeoRepository;
import com.java2nb.novel.seo.SeoSiteSettings;
import com.java2nb.novel.seo.SeoText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SeoAdminService {
    private final SeoRepository repository;
    private final SeoPageInspector inspector;
    private final SeoHttpStatsRepository httpStats;
    private final String siteUrl;
    @Value("${seo.enabled:true}") private boolean seoEnabled = true;

    public SeoAdminService(SeoRepository repository, SeoPageInspector inspector, String siteUrl) {
        this(repository, inspector, null, siteUrl);
    }

    @Autowired
    public SeoAdminService(SeoRepository repository, SeoPageInspector inspector, SeoHttpStatsRepository httpStats,
                           @Value("${seo.site-url:}") String siteUrl) {
        this.repository = repository; this.inspector = inspector; this.httpStats = httpStats; this.siteUrl = siteUrl;
    }

    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", repository.settings());
        result.put("siteUrl", siteUrl);
        result.put("siteUrlValid", validSiteUri() != null);
        result.put("domainEditable", false);
        return result;
    }

    public Map<String, Object> updateConfig(ConfigChange change, long actorId) {
        requireFlags(change.indexEnabled(), change.chaptersIndexEnabled(), change.chapterSitemapEnabled());
        if (Boolean.TRUE.equals(change.indexEnabled()) && validSiteUri() == null) {
            throw new IllegalArgumentException("启用索引前必须由部署配置提供有效的 HTTPS 站点根地址");
        }
        repository.updateSettings(new SeoSiteSettings(version(change.expectedEpoch()), change.indexEnabled(),
            change.chaptersIndexEnabled(), change.chapterSitemapEnabled(),
            text(change.defaultTitle(), 160), text(change.defaultDescription(), 500), text(change.keywords(), 300),
            verification(change.baiduVerification()), verification(change.googleVerification())), actorId);
        return config();
    }

    public List<Map<String, Object>> books(String query, int offset, int limit) {
        if (offset < 0 || offset > 100000 || limit < 1 || limit > 100) throw new IllegalArgumentException("每页数量须为1至100");
        return repository.listBooks(text(query, 100), offset, limit);
    }

    public Map<String, Object> preview(long bookId, Long chapterId) {
        if (bookId <= 0 || (chapterId != null && chapterId <= 0)) throw new IllegalArgumentException("书籍或章节ID无效");
        Map<String, Object> result;
        try { result = repository.bookPreview(bookId, chapterId); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "书籍或章节不存在"); }
        if (result == null || result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "书籍或章节不存在");
        Map<String, Object> view = new LinkedHashMap<>(result);
        URI origin = validSiteUri();
        String path = chapterId == null ? "/book/" + bookId + ".html" : "/book/" + bookId + "/" + chapterId + ".html";
        view.put("canonical", origin == null ? "" : origin.resolve(path).toString());
        view.put("siteUrlValid", origin != null);
        if (origin == null) {
            view.put("indexable", false); view.put("excludedReason", "invalid_site_url");
            if (chapterId != null) view.put("chapterIndexable", false);
        }
        if (!seoEnabled) {
            view.put("indexable", false); view.put("excludedReason", "seo_disabled");
            if (chapterId != null) view.put("chapterIndexable", false);
        }
        view.put("robots", Boolean.TRUE.equals(view.get("indexable")) ? "index,follow" : "noindex,follow");
        view.put("state", repository.state(bookId));
        return view;
    }

    public Map<String, Object> updateBook(long bookId, BookChange change, long actorId) {
        preview(bookId, null);
        Boolean enabled = switch (change.indexMode() == null ? "" : change.indexMode()) {
            case "INHERIT" -> null;
            case "ALLOW" -> true;
            case "DENY" -> false;
            default -> throw new IllegalArgumentException("单书索引模式必须为继承、允许或禁止");
        };
        repository.saveBookOverride(new SeoBookOverride(bookId, enabled, text(change.title(), 160),
            text(change.description(), 500), text(change.keywords(), 300)), version(change.expectedRevision()), actorId);
        return preview(bookId, null);
    }

    public Map<String, Object> sitemaps() {
        Map<String, Object> result = new LinkedHashMap<>(repository.diagnostics());
        result.put("jobs", repository.jobs(50));
        result.put("siteUrl", siteUrl);
        result.put("siteUrlValid", validSiteUri() != null);
        if (httpStats != null) result.put("httpStats", httpStats.recentOverview());
        return result;
    }

    public String enqueue(Rebuild request) {
        if ("all".equals(request.scope()) && request.bookId() == null) return repository.enqueueAll();
        if ("book".equals(request.scope()) && request.bookId() != null) {
            long bookId = id(request.bookId()); preview(bookId, null);
            return repository.enqueueBook(bookId);
        }
        throw new IllegalArgumentException("重建范围必须为单书或全站");
    }

    public Map<String, Object> diagnose(Diagnosis request) {
        URI origin = validSiteUri();
        if (origin == null) throw new IllegalArgumentException("请先配置可信的 HTTPS SEO_SITE_URL");
        Map<String, Object> policy = Map.of();
        String path;
        String bookIdText = null, chapterIdText = null;
        boolean indexable, contentExpected, paidChapter = false;
        int expectedStatus = 200;
        String bookName = "", authorName = "", bookDescription = null, chapterName = "";
        String type = request.pageType() == null ? "" : request.pageType();
        switch (type) {
            case "home", "ranking", "category" -> {
                path = type.equals("home") ? "/" : type.equals("ranking") ? "/book/book_ranking.html" : "/book/bookclass.html";
                contentExpected = repository.publicBookCount(null, null) > 0;
                indexable = seoEnabled && repository.settings().indexEnabled() && (type.equals("home") || contentExpected);
                policy = Map.of("indexable", indexable, "robots", indexable ? "index,follow" : "noindex,follow", "contentExpected", contentExpected);
            }
            case "book", "catalog", "chapter" -> {
                long bookId = id(request.bookId());
                Long chapterId = "chapter".equals(type) ? id(request.chapterId()) : null;
                bookIdText = Long.toString(bookId); chapterIdText = chapterId == null ? null : chapterId.toString();
                policy = preview(bookId, chapterId);
                bookName = Objects.toString(policy.get("bookName"), ""); authorName = Objects.toString(policy.get("authorName"), "");
                chapterName = Objects.toString(policy.get("indexName"), "");
                indexable = Boolean.TRUE.equals(policy.get("indexable"));
                boolean published = policy.get("status") instanceof Number value && value.intValue() == 1;
                paidChapter = chapterId != null && policy.get("chapterVip") instanceof Number value && value.intValue() == 1;
                if (paidChapter) indexable = false;
                // Preview does not validate bodies. The explicit HTTP diagnosis observes the real page.
                if (!published) { expectedStatus = 404; indexable = false; }
                contentExpected = type.equals("catalog") ? repository.chapterCount(bookId) > 0 : chapterId == null || !paidChapter;
                if (type.equals("book")) {
                    var book = repository.book(bookId);
                    if (book != null) bookDescription = SeoText.plain(book.getBookDesc());
                }
                path = switch (type) {
                    case "book" -> "/book/" + bookId + ".html";
                    case "catalog" -> "/book/indexList-" + bookId + ".html";
                    default -> "/book/" + bookId + "/" + chapterId + ".html";
                };
            }
            default -> throw new IllegalArgumentException("不支持的页面类型");
        }
        var expected = new SeoPageInspector.ExpectedPage(type, bookIdText, chapterIdText, indexable,
            expectedStatus, contentExpected, paidChapter, bookName, authorName, bookDescription, chapterName);
        Map<String, Object> result = new LinkedHashMap<>(inspector.inspect(origin.resolve(path), expected));
        Map<String, Object> expectedPolicy = new LinkedHashMap<>(policy);
        expectedPolicy.put("canonical", origin.resolve(path).toString()); expectedPolicy.put("expectedStatus", expectedStatus);
        expectedPolicy.put("indexable", indexable); expectedPolicy.put("robots", indexable ? "index,follow" : "noindex,follow");
        expectedPolicy.put("contentExpected", contentExpected);
        result.put("policy", expectedPolicy);
        return result;
    }

    URI validSiteUri() {
        try {
            URI uri = URI.create(siteUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getFragment() != null || uri.getPort() != -1
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) return null;
            String host = uri.getHost();
            if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost") || !host.contains(".")
                || host.matches("[0-9.]+") || host.contains(":")) return null;
            return URI.create("https://" + host + "/");
        } catch (IllegalArgumentException ex) { return null; }
    }

    static long id(String value) {
        long parsed = version(value); if (parsed == 0) throw new IllegalArgumentException("ID必须大于0"); return parsed;
    }
    static long version(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) throw new IllegalArgumentException("版本或ID无效，请刷新页面");
        try { return Long.parseLong(value); } catch (NumberFormatException ex) { throw new IllegalArgumentException("版本或ID超出范围"); }
    }
    static String text(String value, int maximum) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("\\s+", " ");
        if (clean.codePointCount(0, clean.length()) > maximum || clean.indexOf('<') >= 0 || clean.indexOf('>') >= 0
            || clean.codePoints().anyMatch(c -> Character.isISOControl(c))) throw new IllegalArgumentException("覆盖值须为有长度限制的纯文本");
        return clean;
    }
    static String verification(String value) {
        String token = text(value, 200);
        if (!token.matches("[A-Za-z0-9._-]*")) throw new IllegalArgumentException("站点验证仅支持验证token，不能包含HTML或脚本");
        return token;
    }
    private static void requireFlags(Boolean... flags) {
        for (Boolean flag : flags) if (flag == null) throw new IllegalArgumentException("请提供全部索引开关");
    }

    public record ConfigChange(String expectedEpoch, Boolean indexEnabled, Boolean chaptersIndexEnabled,
        Boolean chapterSitemapEnabled, String defaultTitle, String defaultDescription, String keywords,
        String baiduVerification, String googleVerification) {}
    public record BookChange(String expectedRevision, String indexMode, String title, String description, String keywords) {}
    public record Rebuild(String scope, String bookId) {}
    public record Diagnosis(String pageType, String bookId, String chapterId) {}
}
