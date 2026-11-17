package com.java2nb.novel.core.crawl;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.safety.Safelist;
import org.springframework.web.util.HtmlUtils;
import com.java2nb.novel.utils.CrawlMetadataException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** 和图书独立书源：目录取公开 JSON，正文按网页排序接口恢复，不执行远端 JavaScript。 */
@Slf4j
public final class HetushuSourceAdapter {
    private static final String SITE = "https://hetushu.com";
    private static final Pattern BOOK = Pattern.compile("/book/([1-9]\\d*)/index\\.html");
    private static final Pattern CATALOG = Pattern.compile("/book/([1-9]\\d*)/dir\\.json");
    private static final Pattern CHAPTER = Pattern.compile("/book/([1-9]\\d*)/([1-9]\\d*)\\.html");
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9]\\d*");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PARAGRAPHS = 5000;
    // /command/section.css 在 #content 内将这些标签设为 display:none，内容为混淆噪声。
    private static final String HIDDEN_READING_TAGS =
        "acronym,bdo,big,cite,code,dfn,kbd,q,s,samp,strike,tt,u,var";

    private HetushuSourceAdapter() {}

    public interface PageReader {
        String getPage(String url) throws InterruptedException;
        String getOrderToken(String url, String referer) throws InterruptedException;
    }

    public static boolean supports(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && "hetushu.com".equals(uri.getHost())
                && uri.getUserInfo() == null && uri.getFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && ("/book/index.php".equals(uri.getPath()) || BOOK.matcher(uri.getPath()).matches()
                    || CATALOG.matcher(uri.getPath()).matches() || CHAPTER.matcher(uri.getPath()).matches());
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    public static String getHtml(String url, PageReader reader) throws InterruptedException {
        if (!supports(url)) return null;
        String body = reader.getPage(url);
        if (body == null || body.isBlank()) return null;
        try {
            URI uri = URI.create(url);
            if (CATALOG.matcher(uri.getPath()).matches()) return catalog(body);
            Document page = Jsoup.parse(body, url);
            if ("/book/index.php".equals(uri.getPath())) return category(page);
            if (BOOK.matcher(uri.getPath()).matches()) return metadata(page);
            return chapter(uri, page, reader);
        } catch (IllegalArgumentException | IllegalStateException | JacksonException e) {
            log.warn("和图书页面结构或排序数据无效：{}，{}", URI.create(url).getPath(), e.getClass().getSimpleName());
            return null;
        }
    }

    private static String category(Document page) {
        Element list = required(page.selectFirst("ul.book_list"));
        Element current = required(page.selectFirst(".page .current"));
        int currentPage = positiveNumber(current.text());
        int totalPages = currentPage;
        for (Element link : page.select(".page a[href]")) {
            var match = Pattern.compile("(?:[?&])page=(\\d+)").matcher(link.attr("href"));
            if (match.find()) totalPages = Math.max(totalPages, positiveNumber(match.group(1)));
        }
        var books = new LinkedHashMap<String, String>();
        for (Element link : list.select("a[href]")) {
            var match = BOOK.matcher(URI.create(link.absUrl("href")).getPath());
            if (match.matches()) {
                String id = match.group(1);
                if (!books.containsKey(id) || !link.text().isBlank()) books.put(id, link.text());
            }
        }
        StringBuilder result = new StringBuilder("<span data-current-page=\"").append(currentPage)
            .append("\" data-total-pages=\"").append(totalPages).append("\"></span><ul>");
        books.forEach((id, name) -> result.append("<li><a href=\"/book/").append(id)
            .append("/index.html\">").append(escape(name)).append("</a></li>"));
        return result.append("</ul>").toString();
    }

    private static String metadata(Document page) {
        var match = BOOK.matcher(URI.create(page.baseUri()).getPath());
        String bookId = match.matches() ? match.group(1) : null;
        Element info = page.selectFirst(".book_info");
        if (info == null) throw new CrawlMetadataException(CrawlMetadataException.Reason.DETAIL_INVALID,
            page.baseUri(), bookId, null);
        Element heading = info.selectFirst("h2");
        String name = heading == null ? "" : heading.text();
        if (name.isBlank()) throw new CrawlMetadataException(CrawlMetadataException.Reason.TITLE_MISSING,
            page.baseUri(), bookId, null);
        Element author = info.selectFirst("a[href^=/author/]");
        Element ogAuthor = page.selectFirst("meta[property=og:novel:author]");
        String verifiedAuthor = CrawlBookMetadata.authorOrPending(page.baseUri(), bookId, name,
            author == null ? "" : author.text(), ogAuthor == null ? "" : ogAuthor.attr("content"));
        String status = info.hasClass("finish") ? "已完结" : "未知";
        StringBuilder result = new StringBuilder("<h1>").append(escape(name)).append("</h1>")
            .append("<span class=\"author\">").append(escape(verifiedAuthor)).append("</span>")
            .append("<span class=\"book-status\">").append(status).append("</span>");
        Element image = info.selectFirst("img[src]");
        if (image != null && !image.absUrl("src").isBlank()) {
            String cover = image.absUrl("src");
            if (cover.startsWith("https://") || cover.startsWith("http://")) {
                result.append("<img class=\"book-cover\" src=\"").append(escape(cover)).append("\"/>");
            }
        }
        Element intro = info.selectFirst(".intro");
        result.append("<section id=\"book-description\">");
        if (intro != null) result.append(clean(intro.html(), page.baseUri()));
        return result.append("</section>").toString();
    }

    private static String catalog(String body) {
        JsonNode data = JSON.readTree(body);
        if (!data.isArray() || data.isEmpty() || data.size() > 30000) {
            throw new IllegalArgumentException("Invalid catalog");
        }
        StringBuilder result = new StringBuilder("<dl id=\"chapters\">");
        var ids = new HashSet<String>();
        for (JsonNode item : data) {
            if (!item.isArray() || item.size() < 2 || !item.get(0).isString() || !item.get(1).isString()) {
                throw new IllegalArgumentException("Invalid catalog row");
            }
            String tag = item.get(0).asString();
            if ("dt".equals(tag)) continue;
            if (!"dd".equals(tag) || item.size() != 3 || !item.get(2).isString()) {
                throw new IllegalArgumentException("Invalid chapter row");
            }
            String id = item.get(2).asString();
            String name = item.get(1).asString();
            if (!POSITIVE_ID.matcher(id).matches() || name.isBlank() || !ids.add(id)) {
                throw new IllegalArgumentException("Invalid or duplicate chapter");
            }
            result.append("<dd><a href=\"").append(id).append(".html\">")
                .append(escape(name)).append("</a></dd>");
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Empty catalog");
        return result.append("</dl>").toString();
    }

    private static String chapter(URI uri, Document page, PageReader reader) throws InterruptedException {
        if (!"json".equals(page.body().attr("data-doctype"))
            || !"substep".equals(page.body().attr("data-randomtype"))) {
            throw new IllegalArgumentException("Unsupported reading protocol");
        }
        Element content = required(page.getElementById("content"));
        required(content.selectFirst("h2"));
        var match = CHAPTER.matcher(uri.getPath());
        if (!match.matches()) throw new IllegalArgumentException("Invalid chapter URL");
        // 原始 r*.json 可被 CDN 缓存成 404；唯一查询参数获取当前页面的真实 Token 响应。
        String orderUrl = SITE + "/book/" + match.group(1) + "/r" + match.group(2)
            + ".json?_=" + System.nanoTime();
        String token = reader.getOrderToken(orderUrl, uri.toString());
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Missing order token");
        return restoreParagraphs(content, token, uri.toString());
    }

    /** 精确复现 section.content.load 的索引计算，任何缺失或重复均拒绝返回正文。 */
    static String restoreParagraphs(Element content, String token, String baseUrl) {
        Element mask = content.selectFirst(".mask");
        if (mask != null && mask.parent() == content) mask.remove();
        List<Node> nodes = new ArrayList<>(content.childNodes());
        int start = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) instanceof Element node) {
                if ("h2".equals(node.tagName())) start = i + 1;
                if ("div".equals(node.tagName()) && !node.hasClass("chapter")) break;
            }
        }
        List<Node> paragraphs = nodes.subList(start, nodes.size());
        if (paragraphs.isEmpty() || paragraphs.size() > MAX_PARAGRAPHS || token.length() > 80000) {
            throw new IllegalArgumentException("Invalid paragraph count");
        }
        String order = new String(Base64.getDecoder().decode(token.strip()), StandardCharsets.US_ASCII);
        String[] positions = order.split("[A-Z]+%", -1);
        if (positions.length != paragraphs.size()) throw new IllegalArgumentException("Order length mismatch");
        Element[] sorted = new Element[positions.length];
        int fixed = 0;
        for (int i = 0; i < positions.length; i++) {
            if (!positions[i].matches("\\d{1,5}") || !(paragraphs.get(i) instanceof Element paragraph)
                || !"div".equals(paragraph.tagName())) {
                throw new IllegalArgumentException("Invalid order or paragraph");
            }
            int position = Integer.parseInt(positions[i]);
            int target;
            if (position < 5) {
                target = position;
                fixed++;
            } else {
                target = position - fixed;
            }
            if (target < 0 || target >= sorted.length || sorted[target] != null) {
                throw new IllegalArgumentException("Invalid order permutation");
            }
            sorted[target] = paragraph;
        }
        StringBuilder result = new StringBuilder("<article id=\"chapter-content\">");
        for (Element paragraph : sorted) {
            if (paragraph == null) throw new IllegalArgumentException("Missing paragraph");
            Element visible = paragraph.clone();
            visible.select(HIDDEN_READING_TAGS).remove();
            result.append(clean(visible.html(), baseUrl)).append("<br/>");
        }
        return result.append("</article>").toString();
    }

    private static String clean(String html, String baseUrl) {
        return Jsoup.clean(html, baseUrl, Safelist.basicWithImages(),
            new Document.OutputSettings().prettyPrint(false));
    }

    private static Element required(Element element) {
        if (element == null) throw new IllegalArgumentException("Missing page element");
        return element;
    }

    private static int positiveNumber(String value) {
        int number = Integer.parseInt(value);
        if (number < 1 || number > 100000) throw new IllegalArgumentException("Invalid page number");
        return number;
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
