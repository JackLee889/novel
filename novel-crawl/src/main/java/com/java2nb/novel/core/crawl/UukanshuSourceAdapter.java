package com.java2nb.novel.core.crawl;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.web.util.HtmlUtils;
import com.java2nb.novel.utils.CrawlMetadataException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** UU 看书独立书源：列表作者优先，目录与正文仅取相应区块，不执行网站脚本。 */
@Slf4j
public final class UukanshuSourceAdapter {
    private static final String SITE = "https://uukanshu.cc";
    private static final Pattern LIST = Pattern.compile("/class_(?:[0-9]|10)_([1-9]\\d*)\\.html");
    private static final Pattern BOOK = Pattern.compile("/book/([1-9]\\d*)/");
    private static final Pattern CHAPTER = Pattern.compile("/book/([1-9]\\d*)/([1-9]\\d*)(?:_[1-9]\\d*)?\\.html");
    private static final Pattern PAGINATION = Pattern.compile("([1-9]\\d*)/([1-9]\\d*)");
    private static final Pattern LOCAL_CATEGORY = Pattern.compile("<span class=\"book-category\" data-local-id=\"([1-8])\"></span>");
    private static final int MAX_AUTHORS = 2048;
    private static final int MAX_AUTHOR_LOOKUP_PAGES = 3;
    // 每个客户端独立缓存，分类线程间共享；只保留近期列表的书名及作者。
    private final Map<String, ListingBook> authors = new LinkedHashMap<>(64, .75f, true);

    @FunctionalInterface
    public interface PageReader {
        String getPage(String url) throws InterruptedException;
    }

    public static boolean supports(String url) {
        URI uri = siteUri(url);
        return uri != null && (LIST.matcher(uri.getPath()).matches()
            || BOOK.matcher(uri.getPath()).matches() || CHAPTER.matcher(uri.getPath()).matches());
    }

    /** 全部书库扫描按详情页分类；没有分类的书归入本站“其他小说”。 */
    public static Integer categoryId(String normalizedHtml) {
        var match = LOCAL_CATEGORY.matcher(normalizedHtml);
        return match.find() ? Integer.valueOf(match.group(1)) : null;
    }

    public String getHtml(String url, PageReader reader) throws InterruptedException {
        if (!supports(url)) return null;
        String raw = reader.getPage(url);
        if (raw == null || raw.isBlank()) return null;
        try {
            Document document = Jsoup.parse(raw, url);
            document.outputSettings().prettyPrint(false);
            String path = URI.create(url).getPath();
            if (LIST.matcher(path).matches()) return listing(document);
            var book = BOOK.matcher(path);
            if (book.matches()) return book(document, book.group(1), reader);
            return chapter(document);
        } catch (IllegalArgumentException e) {
            log.warn("UU 看书页面结构无效：{} ({})", url, e.getMessage());
            var book = BOOK.matcher(URI.create(url).getPath());
            if (book.matches()) throw new CrawlMetadataException(CrawlMetadataException.Reason.DETAIL_INVALID,
                url, book.group(1), null);
            return null;
        }
    }

    private String listing(Document document) {
        var pages = pagination(document);
        var entries = listingBooks(document);
        require(!entries.isEmpty(), "分类页没有图书记录");
        StringBuilder html = new StringBuilder("<section data-current-page=\"")
            .append(pages[0]).append("\" data-total-pages=\"").append(pages[1]).append("\">");
        entries.forEach((id, book) -> html.append("<a href=\"/book/").append(id).append("/\">")
            .append(escape(book.name())).append("</a>"));
        return html.append("</section>").toString();
    }

    private Map<String, ListingBook> listingBooks(Document document) {
        Map<String, ListingBook> found = new LinkedHashMap<>();
        for (Element box : document.select(".bookbox")) {
            Element link = box.selectFirst(".bookname a[href]");
            Element author = box.selectFirst(".author");
            if (link == null || author == null) continue;
            URI target = siteUri(link.absUrl("href"));
            if (target == null) continue;
            var id = BOOK.matcher(target.getPath());
            if (!id.matches()) continue;
            String name = link.text().strip();
            String authorName = CrawlBookMetadata.author(author.text());
            if (name.isEmpty() || !CrawlBookMetadata.usableAuthor(authorName)) continue;
            found.put(id.group(1), new ListingBook(name, authorName));
        }
        synchronized (authors) {
            authors.putAll(found);
            while (authors.size() > MAX_AUTHORS) authors.remove(authors.keySet().iterator().next());
        }
        return found;
    }

    private String book(Document document, String bookId, PageReader reader) throws InterruptedException {
        if (!bookId.equals(meta(document, "og:book_id")))
            throw new CrawlMetadataException(CrawlMetadataException.Reason.SOURCE_ID_MISMATCH,
                document.baseUri(), bookId, null);
        Element heading = document.selectFirst("h1.booktitle");
        if (heading == null || heading.text().isBlank())
            throw new CrawlMetadataException(CrawlMetadataException.Reason.TITLE_MISSING,
                document.baseUri(), bookId, null);
        String name = heading.text().strip();
        String author = com.java2nb.novel.utils.CrawlRequestContext.boundAuthor(bookId,name);
        if(author==null) author=meta(document, "og:novel:author").strip();
        // 当前源站详情页普遍错误地显示“佚名”，分类/搜索列表仍有真实作者。
        if (!CrawlBookMetadata.usableAuthor(author)
            && com.java2nb.novel.utils.CrawlRequestContext.boundAuthor(bookId,name)==null) {
            ListingBook entry = cachedAuthor(bookId, name);
            if (entry == null) entry = findAuthor(document, bookId, name, reader);
            if (entry != null) author = entry.author();
        }
        author = CrawlBookMetadata.authorOrPending(document.baseUri(), bookId, name, author);
        Element intro = document.selectFirst(".bookintro");
        require(intro != null, "缺少简介区块");
        intro = intro.clone();
        intro.select("img,script,style,iframe,ins").remove();
        String status = switch (meta(document, "og:novel:status").strip()) {
            case "全本" -> "已完结";
            case "連載" -> "连载中";
            default -> "";
        };
        String cover = absoluteImage(meta(document, "og:image"), document.baseUri());
        String html = "<span class=\"book-category\" data-local-id=\"" + localCategory(document) + "\"></span>"
            + "<h1>" + escape(name) + "</h1><span class=\"author\">" + escape(author)
            + "</span><span class=\"book-status\">" + status + "</span><time>"
            + escape(meta(document, "og:novel:update_time")) + "</time>"
            + (cover.isEmpty() ? "" : "<img class=\"book-cover\" src=\"" + escape(cover) + "\">")
            + "<section id=\"book-description\">" + clean(intro.html(), document.baseUri()) + "</section>";
        return html + catalog(document, bookId);
    }

    private static int localCategory(Document document) {
        for (Element link : document.select(".breadcrumb a[href]")) {
            URI uri = siteUri(link.absUrl("href"));
            if (uri == null || !LIST.matcher(uri.getPath()).matches()) continue;
            int source = Integer.parseInt(uri.getPath().split("_")[1]);
            return switch (source) {
                case 1, 2, 3, 4, 6 -> source;
                case 5, 7 -> 5;
                case 8 -> 7;
                default -> 8;
            };
        }
        return 8;
    }

    private ListingBook cachedAuthor(String bookId, String name) {
        synchronized (authors) {
            ListingBook entry = authors.get(bookId);
            return entry != null && entry.name().equals(name) ? entry : null;
        }
    }

    private ListingBook findAuthor(Document detail, String bookId, String name, PageReader reader)
        throws InterruptedException {
        // 单本任务或重启后的更新没有刚读过的分类列表，先核对该分类第一页。
        Document category = null;
        String categoryUrl = null;
        for (Element link : detail.select(".breadcrumb a[href]")) {
            URI uri = siteUri(link.absUrl("href"));
            if (uri == null || !LIST.matcher(uri.getPath()).matches()) continue;
            String raw = reader.getPage(uri.toString());
            if (raw != null) {
                categoryUrl = uri.toString();
                category = Jsoup.parse(raw, categoryUrl);
                listingBooks(category);
            }
            ListingBook entry = cachedAuthor(bookId, name);
            if (entry != null) return entry;
            break;
        }
        // A query with one result redirects to the anonymous detail page; a single Chinese character
        // is rejected by the site's minimum byte length. Try other two-character fragments of the
        // same title, sharing the original three-page budget rather than multiplying requests.
        var keywords = new LinkedHashSet<String>();
        int length = name.codePointCount(0, name.length());
        for (int position = 0; position + 1 < length; position += 2) {
            String keyword = name.substring(name.offsetByCodePoints(0, position), name.offsetByCodePoints(0, position + 2));
            if (keyword.getBytes(StandardCharsets.UTF_8).length >= 4) keywords.add(keyword);
        }
        if (length >= 2) {
            String suffix = name.substring(name.offsetByCodePoints(0, length - 2));
            if (suffix.getBytes(StandardCharsets.UTF_8).length >= 4) keywords.add(suffix);
        }
        var visited = new HashSet<String>();
        int requests = 0;
        for (String keyword : keywords) {
          String next = SITE + "/search?searchkey=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&searchtype=all";
          while (requests < MAX_AUTHOR_LOOKUP_PAGES && next != null && visited.add(next)) {
            requests++;
            String raw = reader.getPage(next);
            if (raw == null) break;
            Document search = Jsoup.parse(raw, next);
            listingBooks(search);
            ListingBook entry = cachedAuthor(bookId, name);
            if (entry != null) return entry;
            next = null;
            for (Element link : search.select("#pagelink a[href]")) {
                String text = link.text().strip();
                if (!text.equals(">") && !text.equals("下一頁") && !text.equals("下一页")) continue;
                String endpoint = link.absUrl("href");
                URI uri = siteUri(endpoint);
                if (uri != null && uri.getPath().startsWith("/search/")) next = endpoint;
                break;
            }
          }
        }
        // 两字书名没有其他合法搜索片段，唯一结果又会跳回“佚名”详情页。
        // 使用尚未消耗的查找预算核对同一分类后续页；总请求数保持原有上限。
        String next = category == null ? null : nextCategoryPage(category, categoryUrl);
        while (requests < MAX_AUTHOR_LOOKUP_PAGES && next != null && visited.add(next)) {
            requests++;
            String raw = reader.getPage(next);
            if (raw == null) break;
            Document page = Jsoup.parse(raw, next);
            listingBooks(page);
            ListingBook entry = cachedAuthor(bookId, name);
            if (entry != null) return entry;
            next = nextCategoryPage(page, next);
        }
        return null;
    }

    private static String nextCategoryPage(Document document, String currentUrl) {
        URI current = siteUri(currentUrl);
        if (current == null) return null;
        var page = LIST.matcher(current.getPath());
        if (!page.matches()) return null;
        final int number;
        try { number = Integer.parseInt(page.group(1)); }
        catch (NumberFormatException invalid) { return null; }
        String expected = current.getPath().substring(0, current.getPath().lastIndexOf('_') + 1)
            + ((long) number + 1) + ".html";
        for (Element link : document.select("#pagelink a[href]")) {
            String text = link.text().strip();
            if (!text.equals(">") && !text.equals("下一頁") && !text.equals("下一页")) continue;
            final URI target;
            try { target = siteUri(current.resolve(link.attr("href")).toString()); }
            catch (IllegalArgumentException invalid) { return null; }
            return target != null && expected.equals(target.getPath())
                && target.getRawQuery() == null && target.getRawFragment() == null
                ? SITE + target.getPath() : null;
        }
        return null;
    }

    private String catalog(Document document, String bookId) {
        Element block = document.getElementById("list-chapterAll");
        require(block != null, "缺少完整目录区块");
        StringBuilder html = new StringBuilder("<dl id=\"chapters\">");
        var seen = new HashSet<String>();
        for (Element link : block.select("dd a[href]")) {
            URI uri = siteUri(link.absUrl("href"));
            if (uri == null) continue;
            var chapter = CHAPTER.matcher(uri.getPath());
            if (!chapter.matches() || !bookId.equals(chapter.group(1))) continue;
            // 目录只接受章节首页，避免章内分页误计为另一章。
            require(uri.getPath().endsWith("/" + chapter.group(2) + ".html"), "目录含章内分页链接");
            require(seen.add(chapter.group(2)), "目录章节 ID 重复");
            require(!link.text().isBlank(), "章节标题为空");
            html.append("<dd><a href=\"").append(chapter.group(2)).append(".html\">")
                .append(escape(link.text())).append("</a></dd>");
        }
        require(!seen.isEmpty(), "目录为空");
        return html.append("</dl>").toString();
    }

    private String chapter(Document document) {
        Element content = document.selectFirst(".readcotent");
        require(content != null, "缺少正文区块");
        content = content.clone();
        content.select("script,style,iframe,ins,.ads,.ad,.pubadx-slot").remove();
        require(!content.text().isBlank(), "正文为空");
        StringBuilder html = new StringBuilder("<article id=\"chapter-content\">")
            .append(clean(content.html(), document.baseUri())).append("</article>");
        for (Element link : document.select(".toolbar a[href],.mulu-box a[href]")) {
            if (!link.text().strip().matches("下一[頁页]")) continue;
            URI uri = siteUri(link.absUrl("href"));
            if (uri != null && CHAPTER.matcher(uri.getPath()).matches()) {
                html.append("<a class=\"next-page\" href=\"").append(escape(uri.toString())).append("\">下一页</a>");
                break;
            }
        }
        return html.toString();
    }

    private static int[] pagination(Document document) {
        Element stats = document.getElementById("pagestats");
        var match = PAGINATION.matcher(stats == null ? "" : stats.text().strip());
        require(match.matches(), "分页标记无效");
        int current = Integer.parseInt(match.group(1));
        int total = Integer.parseInt(match.group(2));
        require(current <= total, "当前页超出总页数");
        return new int[]{current, total};
    }

    private static String meta(Document document, String property) {
        Element element = document.selectFirst("meta[property=\"" + property + "\"]");
        return element == null ? "" : element.attr("content");
    }

    private static URI siteUri(String url) {
        try {
            URI uri = URI.create(url);
            return "uukanshu.cc".equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && "https".equalsIgnoreCase(uri.getScheme()) ? uri : null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private static String absoluteImage(String image, String base) {
        if (image.isBlank()) return "";
        URI uri = URI.create(base).resolve(image);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null ? uri.toString() : "";
    }

    private static String clean(String html, String base) {
        return Jsoup.clean(html, base, Safelist.basicWithImages(),
            new Document.OutputSettings().prettyPrint(false));
    }

    private static String escape(String value) { return HtmlUtils.htmlEscape(value, "UTF-8"); }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    private record ListingBook(String name, String author) {}
}
