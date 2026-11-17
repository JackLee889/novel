package com.java2nb.novel.core.crawl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.java2nb.novel.core.utils.RestTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * bqg616 的公开网页通过 JavaScript 读取 JSON，先转换为现有规则可解析的 HTML。
 * token 算法来自公开 read.js 的 enaes，不涉及账号或登录凭据。
 */
@Slf4j
public class Bqg616SourceAdapter {
    private static final String API = "https://apibi.cc/api/";
    private static final String SITE = "https://www.bqg616.cc";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BOOK_ROUTE = Pattern.compile("^/book/(\\d+)/(\\d+\\.html)?$");
    private static final Set<String> CATEGORIES =
        Set.of("xuanhuan", "wuxia", "dushi", "lishi", "kehuan", "wangyou", "mm");
    private static final int PAGE_SIZE = 15;
    private static final long CACHE_TTL_NANOS = 60_000_000_000L;
    private static final Bqg616SourceAdapter INSTANCE =
        new Bqg616SourceAdapter(RestTemplates.newInstance("UTF-8"), 2000L);

    private final RestTemplate client;
    private final long intervalMs;
    private final Map<String, CachedResponse> cache = new LinkedHashMap<>();
    private long lastRequestNanos;

    public Bqg616SourceAdapter(RestTemplate client, long intervalMs) {
        this.client = client;
        this.intervalMs = Math.max(0L, intervalMs);
    }

    public static boolean supports(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                && Set.of("www.bqg616.cc", "bqg616.cc").contains(uri.getHost())
                && "/".equals(uri.getPath()) && uri.getFragment() != null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    public static String get(String url) throws InterruptedException {
        return INSTANCE.getHtml(url);
    }

    public String getHtml(String url) throws InterruptedException {
        if (!supports(url)) {
            return null;
        }
        try {
            String[] parts = URI.create(url).getFragment().split("\\?", 2);
            String route = parts[0];
            Map<String, String> query = new HashMap<>();
            if (parts.length == 2) {
                for (String item : parts[1].split("&")) {
                    String[] pair = item.split("=", 2);
                    query.put(pair[0], pair.length == 2 ? pair[1] : "");
                }
            }
            String category = route.startsWith("/") ? route.substring(1) : route;
            if (CATEGORIES.contains(category)) {
                int page = Integer.parseInt(query.getOrDefault("page", "1"));
                JsonNode data = request("sort", null, category).get("data");
                return renderCategory(data, page);
            }
            Matcher book = BOOK_ROUTE.matcher(route);
            if (!book.matches()) {
                throw new IllegalArgumentException("不支持的书源地址");
            }
            String id = book.group(1);
            if (book.group(2) != null) {
                int chapter = Integer.parseInt(book.group(2).replace(".html", ""));
                ObjectNode payload = JSON.createObjectNode().put("id", Integer.parseInt(id))
                    .put("chapterid", chapter);
                return renderChapter(request("chapter", payload, null));
            }
            ObjectNode payload = JSON.createObjectNode().put("id", id);
            JsonNode metadata = request("book", payload, null);
            if ("1".equals(query.get("catalog"))) {
                // 网页使用 dirid 获取目录；部分书的目录 ID 与地址 ID 不同。
                ObjectNode catalogPayload = JSON.createObjectNode().put("id", metadata.get("dirid").asText());
                return renderCatalog(request("booklist", catalogPayload, null).get("list"));
            }
            return renderMetadata(metadata, id);
        } catch (InterruptedException e) {
            throw e;
        } catch (com.java2nb.novel.utils.CrawlMetadataException e) {
            throw e;
        } catch (Exception e) {
            log.warn("笔趣阁书源读取失败：{}，{}", url, e.getMessage());
            return null;
        }
    }

    /** 全部分类线程和规则测试共享限速，避免各线程分别等待仍同时请求源站。 */
    private synchronized JsonNode request(String type, ObjectNode payload, String category) throws Exception {
        String cacheKey = type + ":" + (payload == null ? category : payload.toString());
        boolean cacheable = "sort".equals(type) || "book".equals(type);
        CachedResponse cached = cache.get(cacheKey);
        if (cacheable && cached != null && System.nanoTime() - cached.createdNanos < CACHE_TTL_NANOS) {
            return cached.data;
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        long waitNanos = intervalMs * 1_000_000L - (System.nanoTime() - lastRequestNanos);
        if (lastRequestNanos != 0L && waitNanos > 0) {
            Thread.sleep((waitNanos + 999_999L) / 1_000_000L);
        }
        String query = payload == null ? "sort=" + category : "token=" + encodeToken(payload.toString());
        URI apiUrl = URI.create(API + type + "?" + query);
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.67 Safari/537.36");
        headers.set("Referer", SITE + "/");
        lastRequestNanos = System.nanoTime();
        // URI 重载保留 token 中的 %2B，String 重载会二次编码成 %252B 并导致 HTTP 403。
        String body = client.exchange(apiUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("接口返回空内容");
        }
        JsonNode data = JSON.readTree(body);
        validate(type, data, payload);
        if (cacheable) {
            if (cache.size() >= 128 && !cache.containsKey(cacheKey)) {
                cache.remove(cache.keySet().iterator().next());
            }
            cache.put(cacheKey, new CachedResponse(data, System.nanoTime()));
        }
        return data;
    }

    /** 与网页一致：MD5(book@token.html) 的前后 16 个 UTF-8 字符分别为 IV、密钥。 */
    public static String encodeToken(String payload) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
            .digest("book@token.html".getBytes(StandardCharsets.UTF_8)));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
            new SecretKeySpec(digest.substring(16).getBytes(StandardCharsets.UTF_8), "AES"),
            new IvParameterSpec(digest.substring(0, 16).getBytes(StandardCharsets.UTF_8)));
        String encoded = Base64.getEncoder().encodeToString(cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        return URLEncoder.encode(encoded, StandardCharsets.UTF_8);
    }

    private void validate(String type, JsonNode data, ObjectNode payload) {
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException("接口返回格式错误");
        }
        switch (type) {
            case "book" -> {
                if (!payload.get("id").asText().equals(data.path("id").asText())
                    || !data.path("dirid").asText().matches("\\d+")) {
                    throw new IllegalArgumentException("书籍 ID 或目录 ID 不匹配");
                }
            }
            case "chapter" -> {
                requireText(data, "chaptername");
                requireText(data, "txt");
                if (!payload.get("id").asText().equals(data.path("id").asText())
                    || !payload.get("chapterid").asText().equals(data.path("chapterid").asText())) {
                    throw new IllegalArgumentException("章节 ID 不匹配");
                }
            }
            case "booklist" -> {
                JsonNode list = data.get("list");
                if (list == null || !list.isArray() || list.isEmpty()) {
                    throw new IllegalArgumentException("接口没有章节目录");
                }
                for (JsonNode title : list) {
                    if (!title.isTextual() || title.asText().isBlank()) {
                        throw new IllegalArgumentException("目录含无效章节标题");
                    }
                }
            }
            case "sort" -> {
                JsonNode list = data.get("data");
                if (list == null || !list.isArray() || list.isEmpty()) {
                    throw new IllegalArgumentException("接口没有分类列表");
                }
                for (JsonNode item : list) {
                    requireText(item, "title");
                    if (!item.path("id").asText().matches("\\d+")) {
                        throw new IllegalArgumentException("分类含无效书籍 ID");
                    }
                }
            }
            default -> throw new IllegalArgumentException("接口类型错误");
        }
    }

    private void requireText(JsonNode data, String field) {
        if (!data.path(field).isTextual() || data.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("接口缺少 " + field);
        }
    }

    private String renderCategory(JsonNode list, int page) {
        int total = (list.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page < 1 || page > total) {
            throw new IllegalArgumentException("分类页码超出范围");
        }
        StringBuilder html = new StringBuilder("<div id=\"books\" data-page=\"")
            .append(page).append("\" data-total=\"").append(total).append("\">");
        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(page * PAGE_SIZE, list.size()); i++) {
            JsonNode item = list.get(i);
            html.append("<a href=\"/#/book/").append(item.get("id").asText()).append("/\">")
                .append(escape(item.get("title").asText())).append("</a>");
        }
        return html.append("</div>").toString();
    }

    private String renderMetadata(JsonNode data, String id) {
        String url = SITE + "/#/book/" + id;
        String name = CrawlBookMetadata.text(data.path("title").asText());
        if (name.isBlank()) throw new com.java2nb.novel.utils.CrawlMetadataException(
            com.java2nb.novel.utils.CrawlMetadataException.Reason.TITLE_MISSING, url, id, null);
        String author = CrawlBookMetadata.authorOrPending(url, id, name, data.path("author").asText());
        return "<div id=\"bookinfo\"><h1>" + escape(name)
            + "</h1><span class=\"author\">" + escape(author)
            + "</span><img src=\"" + SITE + "/bookimg/" + Long.parseLong(id) / 1000 + "/" + id
            + ".jpg\"><span class=\"status\">" + escape(data.path("full").asText())
            + "</span><time>" + escape(data.path("lastupdate").asText())
            + "</time><div id=\"intro\">" + paragraphs(data.path("intro").asText()) + "</div></div>";
    }

    private String renderCatalog(JsonNode list) {
        StringBuilder html = new StringBuilder("<dl id=\"catalog\">");
        for (int i = 0; i < list.size(); i++) {
            // 网页把列表的位置 + 1 作为章节 ID，不能从标题中的章节号推算。
            html.append("<dd><a href=\"").append(i + 1).append(".html\">")
                .append(escape(list.get(i).asText())).append("</a></dd>");
        }
        return html.append("</dl>").toString();
    }

    private String renderChapter(JsonNode data) {
        return "<h1>" + escape(data.get("chaptername").asText()) + "</h1><div id=\"chaptercontent\">"
            + paragraphs(data.get("txt").asText()) + "</div>";
    }

    private String paragraphs(String text) {
        return escape(text.replace("\r\n", "\n").replace('\r', '\n')).replace("\n", "<br/>");
    }

    private String escape(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8");
    }

    private record CachedResponse(JsonNode data, long createdNanos) {}
}
