package com.java2nb.novel.service;

import org.apache.commons.text.StringEscapeUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** A single bounded request to a server-configured origin; never follows redirects or carries user cookies. */
@Service
public class SeoPageInspector {
    static final int MAX_BYTES = 512 * 1024;
    private final HttpClient client;
    public SeoPageInspector() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    SeoPageInspector(HttpClient client) { this.client = client; }

    /** All expectations come from the fixed page type and database policy, never from user HTML or a URL. */
    public record ExpectedPage(String pageType, String bookId, String chapterId, boolean indexable,
                               int status, boolean contentExpected, boolean paidChapter,
                               String bookName, String authorName, String bookDescription, String chapterName) {}

    public Map<String, Object> inspect(URI uri, ExpectedPage expected) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("Accept", "text/html").header("Accept-Encoding", "identity")
                .header("User-Agent", "NovelPlus-SEO-Diagnosis/1.0").GET().build();
            pending = client.sendAsync(request, info -> new BoundedBodySubscriber());
            HttpResponse<byte[]> response = pending.get(8, TimeUnit.SECONDS);
                byte[] bytes = response.body();
                return analyze(uri, response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                    new String(bytes, 0, Math.min(bytes.length, MAX_BYTES), StandardCharsets.UTF_8), bytes.length > MAX_BYTES,
                    response.headers().allValues("X-Robots-Tag"), expected);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("诊断请求被中断", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("站点暂时无法完成诊断，请检查HTTPS与服务状态", ex);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    /** Stops transport at the ceiling and makes the whole response subject to the caller's deadline. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        public void onNext(List<ByteBuffer> chunks) {
            if (body.isDone()) return;
            for (ByteBuffer chunk : chunks) {
                int remaining = MAX_BYTES + 1 - bytes.size();
                int count = Math.min(remaining, chunk.remaining());
                byte[] copy = new byte[count]; chunk.get(copy); bytes.writeBytes(copy);
                if (bytes.size() == MAX_BYTES + 1) {
                    subscription.cancel(); body.complete(bytes.toByteArray()); return;
                }
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }

    static Map<String, Object> analyze(URI uri, int status, String contentType, String html, boolean truncated,
                                       List<String> headers, ExpectedPage expected) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", uri.toString()); result.put("status", status); result.put("bodyTruncated", truncated);
        result.put("pageType", expected.pageType()); result.put("expectedStatus", expected.status());
        String expectedRobots = expected.indexable() && expected.status() == 200 ? "index,follow" : "noindex,follow";
        result.put("expectedRobots", expectedRobots);
        var issues = new ArrayList<String>();
        if (status != expected.status()) issues.add("HTTP状态与公开策略不一致，预期" + expected.status());
        if (!contentType.toLowerCase(Locale.ROOT).contains("text/html")) issues.add("响应不是HTML");
        HtmlDocument document = HtmlDocument.parse(html);
        List<Element> canonicals = document.select(e -> e.tag.equals("link") && tokens(e.attr("rel")).contains("canonical"));
        String canonical = canonicals.isEmpty() ? "" : canonicals.getFirst().attr("href");
        if (canonicals.size() > 1) issues.add("存在重复canonical");
        if (canonicals.stream().anyMatch(e -> !e.inside("head"))) issues.add("canonical不在HTML head中");
        if (canonical.isBlank() && expected.status() == 200) issues.add("缺少canonical");
        else if (!canonical.isBlank() && !canonical.equals(uri.toString())) issues.add("canonical与诊断规范URL不一致");
        List<Element> metas = document.select(e -> e.tag.equals("meta") && e.attr("name").equalsIgnoreCase("robots"));
        String robots = String.join(",", metas.stream().map(e -> e.attr("content")).toList());
        if (metas.size() > 1) issues.add("存在重复robots标记");
        if (metas.stream().anyMatch(e -> !e.inside("head"))) issues.add("robots标记不在HTML head中");
        String xRobots = String.join(",", headers);
        result.put("xRobotsTag", xRobots);
        checkRobots(robots, "页面robots", expectedRobots, expected.status() == 200, issues);
        checkRobots(xRobots, "X-Robots-Tag", expectedRobots, true, issues);
        String scopedMetas = String.join(",", document.select(e -> e.tag.equals("meta") &&
            Set.of("googlebot", "bingbot").contains(e.attr("name").toLowerCase(Locale.ROOT)))
            .stream().map(e -> e.attr("content")).toList());
        RobotsRules effective = robots(robots + "," + xRobots + "," + scopedMetas);
        result.put("effectiveRobots", effective.value());
        if (!effective.value().equals(expectedRobots)) issues.add("有效robots规则与索引策略不一致");
        String description = document.select(e -> e.tag.equals("meta") && e.attr("name").equalsIgnoreCase("description"))
            .stream().map(e -> e.attr("content")).findFirst().orElse("");
        if (description.isBlank() && expected.status() == 200) issues.add("缺少页面摘要");
        String pageTitle = document.select(e -> e.tag.equals("title")).stream().map(Element::allText).findFirst().orElse("");
        result.put("title", pageTitle);
        if (pageTitle.isBlank()) issues.add("缺少页面标题");
        result.put("canonical", canonical); result.put("canonicalCount", canonicals.size());
        result.put("robots", robots); result.put("description", description);
        String visible = document.root.visibleText();
        result.put("visibleTextLength", visible.length());
        if (visible.isBlank()) issues.add("缺少服务端输出的可见文本");
        result.put("ssrLinks", (long) links(document.root, uri).size());
        checkContent(document, uri, expected, result, issues);
        int jsonLd = 0, invalidLd = 0;
        Set<String> schemaTypes = new LinkedHashSet<>();
        var scripts = Pattern.compile("<script\\b([^>]*)>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (scripts.find()) {
            if (!"application/ld+json".equalsIgnoreCase(attributes(scripts.group(1)).get("type"))) continue;
            jsonLd++;
            try {
                JsonNode tree = JsonMapper.builder().build().readTree(scripts.group(2));
                if (tree == null || (!tree.isObject() && !tree.isArray())) {
                    invalidLd++; issues.add("结构化数据必须为对象或对象数组");
                } else checkSchema(tree, false, uri, expected, visible, schemaTypes, issues);
            } catch (Exception ex) { invalidLd++; }
        }
        result.put("jsonLdBlocks", jsonLd); result.put("invalidJsonLdBlocks", invalidLd);
        result.put("jsonLdTypes", List.copyOf(schemaTypes));
        if (invalidLd > 0) issues.add("结构化数据JSON无效");
        if (expected.status() == 200 && !expected.pageType().equals("home")) {
            if (!schemaTypes.contains("BreadcrumbList")) issues.add("缺少BreadcrumbList结构化数据");
            if (expected.pageType().equals("book") && !schemaTypes.contains("Book")) issues.add("缺少Book结构化数据");
        }
        if (truncated) issues.add("响应超过512KiB，诊断仅检查前段内容");
        result.put("issues", issues.stream().distinct().toList()); result.put("passed", issues.isEmpty());
        return result;
    }

    private static void checkContent(HtmlDocument document, URI uri, ExpectedPage expected,
                                     Map<String, Object> result, List<String> issues) {
        Element main = document.select(e -> e.tag.equals("main") && e.visible()).stream().findFirst().orElse(null);
        result.put("mainTextLength", main == null ? 0 : main.visibleText().length());
        if (expected.status() != 200) {
            // An unavailable/unpublished page need not have ordinary content metadata or schema.
            if (main != null && document.select(e -> e.visible() && (e.hasClass("detail-hero") ||
                Set.of("reader-prose", "classic-content", "showReading", "chaptercontent").contains(e.attr("id"))))
                .stream().anyMatch(e -> !e.visibleText().isBlank())) issues.add("不可公开页面仍输出书籍或章节主体");
            result.put("contentLinks", 0L); return;
        }
        if (main == null) { issues.add("缺少服务端输出的页面主体main"); result.put("contentLinks", 0L); return; }
        String heading = main.select(e -> e.tag.equals("h1") && e.visible()).stream().map(Element::visibleText).findFirst().orElse("");
        String expectedHeading = expected.pageType().equals("chapter") ? clean(expected.chapterName()) :
            Set.of("book", "catalog").contains(expected.pageType()) ? clean(expected.bookName()) : "";
        if (heading.isBlank()) issues.add("页面主体缺少可见标题");
        else if (!expectedHeading.isBlank() && !heading.equals(expectedHeading)) issues.add("页面主体标题与数据库内容不一致");
        Element content = main;
        switch (expected.pageType()) {
            case "category", "ranking", "catalog" -> {
                String id = switch (expected.pageType()) { case "category" -> "book-results"; case "ranking" -> "ranking-results"; default -> "chapter-list"; };
                content = main.select(e -> e.attr("id").equals(id) && e.visible()).stream().findFirst().orElse(null);
                if (content == null) issues.add("缺少服务端输出的" + id + "内容区域");
            }
            case "home" -> {
                Element categories = main.select(e -> e.attr("id").equals("home-categories") && e.visible()).stream().findFirst().orElse(null);
                if (categories == null || links(categories, uri).stream().noneMatch(l -> l.uri().getPath().equals("/book/bookclass.html")))
                    issues.add("首页缺少可抓取的分类导航");
            }
            case "book" -> {
                Element hero = main.select(e -> e.hasClass("detail-hero") && e.visible()).stream().findFirst().orElse(null);
                if (hero == null || hero.visibleText().isBlank()) issues.add("缺少服务端输出的书籍信息");
                else if (!clean(expected.authorName()).isBlank() && !hero.visibleText().contains(clean(expected.authorName())))
                    issues.add("书籍作者未在主体中显示");
                String description = clean(expected.bookDescription());
                if (!description.isBlank() && main.select(e -> e.hasClass("prose") && e.visible()).stream()
                    .noneMatch(e -> e.visibleText().contains(description))) issues.add("真实书籍简介未在主体中显示");
            }
            case "chapter" -> {
                List<Element> proseNodes = main.select(e -> Set.of("reader-prose", "classic-content", "showReading", "chaptercontent").contains(e.attr("id")));
                Element prose = proseNodes.stream().filter(Element::visible).findFirst().orElse(null);
                String body = prose == null ? "" : prose.visibleText();
                result.put("chapterTextLength", body.length());
                if (expected.contentExpected() && body.isBlank()) issues.add("免费章节缺少服务端输出的正文");
                if (expected.paidChapter()) {
                    if (proseNodes.stream().anyMatch(e -> !e.allText().isBlank()) || paidBootstrapContainsText(document)) issues.add("收费章节向匿名诊断输出了正文");
                    if (main.select(e -> e.visible() && (e.attr("id").equals("access-message") || e.hasClass("state-panel")))
                        .stream().noneMatch(e -> e.visibleText().matches("(?s).*(登录|购买|付费).*"))) issues.add("收费章节缺少可见权限提示");
                }
                if (links(main, uri).stream().noneMatch(l -> l.uri().getPath().equals("/book/" + expected.bookId() + ".html") &&
                    (clean(expected.bookName()).isBlank() || l.text().equals(clean(expected.bookName()))))) issues.add("阅读页缺少对应书籍链接");
            }
            default -> issues.add("不支持的诊断页面类型");
        }
        final String pageType = expected.pageType();
        List<Link> contentLinks = content == null ? List.of() : links(content, uri).stream().filter(l -> switch (pageType) {
            case "home", "category", "ranking" -> l.uri().getPath().matches("/book/[1-9][0-9]{0,18}\\.html");
            case "catalog" -> l.uri().getPath().matches("/book/" + expected.bookId() + "/[1-9][0-9]{0,18}\\.html");
            default -> l.uri().getPath().equals("/book/indexList-" + expected.bookId() + ".html");
        }).toList();
        result.put("contentLinks", (long) contentLinks.size());
        if ((expected.contentExpected() || Set.of("book", "chapter").contains(pageType)) && contentLinks.isEmpty())
            issues.add("缺少对应内容的可抓取HTML链接");
        if (!expected.contentExpected() && Set.of("category", "ranking", "catalog").contains(pageType)) {
            String emptyText = content == null ? "" : content.visibleText();
            if (pageType.equals("catalog")) emptyText = main.select(e -> e.attr("id").equals("chapter-empty") && e.visible())
                .stream().map(Element::visibleText).findFirst().orElse("");
            if (emptyText.isBlank()) issues.add("空列表缺少服务端输出的空状态");
            if (!contentLinks.isEmpty()) issues.add("页面书目或章节与数据库空状态不一致");
        }
    }

    private static boolean paidBootstrapContainsText(HtmlDocument document) {
        return document.select(e -> e.tag.equals("script") && e.attr("id").equals("reader-data")).stream().anyMatch(e -> {
            try {
                JsonNode node = JsonMapper.builder().build().readTree(e.rawText());
                return node != null && node.path("paragraphs").isArray() && !node.path("paragraphs").isEmpty();
            } catch (Exception ex) { return !e.rawText().isBlank(); }
        });
    }

    private static void checkSchema(JsonNode node, boolean inheritedContext, URI uri, ExpectedPage expected,
                                    String visible, Set<String> types, List<String> issues) {
        if (node.isArray()) {
            if (node.isEmpty()) issues.add("结构化数据对象数组为空");
            for (JsonNode child : node) checkSchema(child, inheritedContext, uri, expected, visible, types, issues);
            return;
        }
        if (!node.isObject()) { issues.add("结构化数据图节点必须为对象"); return; }
        boolean context = inheritedContext;
        if (node.has("@context")) {
            String value = string(node, "@context").replaceAll("/+$", "");
            context = value.equals("https://schema.org") || value.equals("http://schema.org");
            if (!context) issues.add("结构化数据@context不是schema.org");
        } else if (!context) issues.add("结构化数据缺少schema.org上下文");
        if (node.has("@graph")) {
            if (!node.path("@graph").isArray() || node.path("@graph").isEmpty()) issues.add("结构化数据@graph必须为非空对象数组");
            else checkSchema(node.path("@graph"), context, uri, expected, visible, types, issues);
            return;
        }
        Set<String> nodeTypes = new LinkedHashSet<>();
        JsonNode type = node.path("@type");
        if (type.isString() && !type.asString().isBlank()) nodeTypes.add(type.asString());
        else if (type.isArray()) for (JsonNode item : type) if (item.isString() && !item.asString().isBlank()) nodeTypes.add(item.asString());
        if (nodeTypes.isEmpty()) issues.add("结构化数据缺少有效@type");
        types.addAll(nodeTypes);
        if (nodeTypes.contains("Book")) {
            String name = string(node, "name"), url = string(node, "url");
            if (name.isBlank() || !visible.contains(name) || (!clean(expected.bookName()).isBlank() && !name.equals(clean(expected.bookName()))))
                issues.add("Book名称缺失或与可见真实书名不一致");
            if (!url.equals(uri.toString())) issues.add("Book URL与canonical不一致");
            if (!string(node, "inLanguage").equals("zh-CN")) issues.add("Book缺少真实语言zh-CN");
            if (!node.path("description").isString() || (expected.bookDescription() != null &&
                !string(node, "description").equals(clean(expected.bookDescription())))) issues.add("Book简介缺失或与真实简介不一致");
            if (!clean(expected.authorName()).isBlank()) {
                JsonNode author = node.path("author");
                if (!string(author, "@type").equals("Person") || !string(author, "name").equals(clean(expected.authorName())) ||
                    !visible.contains(string(author, "name"))) issues.add("Book作者缺失或与可见真实作者不一致");
            }
            if (node.has("image") && absoluteUrl(string(node, "image")) == null) issues.add("Book封面URL无效");
        }
        if (nodeTypes.contains("BreadcrumbList")) {
            JsonNode items = node.path("itemListElement");
            if (!items.isArray() || items.size() < 2) { issues.add("BreadcrumbList缺少有效导航条目"); return; }
            int position = 1;
            for (JsonNode item : items) {
                String name = string(item, "name"), target = string(item, "item");
                if (!item.isObject() || !string(item, "@type").equals("ListItem") || !item.path("position").isIntegralNumber() ||
                    item.path("position").asInt() != position++) issues.add("BreadcrumbList条目类型或顺序无效");
                // The reader represents its home crumb with a labelled brand link, and calls its catalog link "目录".
                if (name.isBlank() || (!visible.contains(name) && !(name.equals("章节目录") && visible.contains("目录")) &&
                    !(name.equals("首页") && target.equals(uri.resolve("/").toString()))))
                    issues.add("BreadcrumbList名称缺失或未在页面显示");
                URI targetUri = absoluteUrl(target);
                if (targetUri == null || !sameOrigin(uri, targetUri) || targetUri.getFragment() != null) issues.add("BreadcrumbList导航URL无效或不同站");
            }
            if (!string(items.get(0), "item").equals(uri.resolve("/").toString()) ||
                !string(items.get(items.size() - 1), "item").equals(uri.toString())) issues.add("BreadcrumbList首尾URL与页面导航不一致");
        }
    }

    private static String string(JsonNode node, String field) { return node.path(field).isString() ? clean(node.path(field).asString()) : ""; }
    private static String clean(String text) { return text == null ? "" : StringEscapeUtils.unescapeHtml4(text).replaceAll("[\\p{Cntrl}\\s\\u00a0]+", " ").strip(); }
    private static Set<String> tokens(String source) { return new LinkedHashSet<>(java.util.Arrays.asList(source.toLowerCase(Locale.ROOT).strip().split("\\s+"))); }

    private record RobotsRules(boolean noindex, boolean nofollow, boolean valid) {
        String value() { return (noindex ? "noindex" : "index") + "," + (nofollow ? "nofollow" : "follow"); }
    }
    private static RobotsRules robots(String value) {
        boolean noindex = false, nofollow = false, known = false, valid = true;
        Set<String> allowed = Set.of("index", "noindex", "follow", "nofollow", "all", "none", "noarchive", "nosnippet", "noimageindex", "notranslate", "nocache", "indexifembedded");
        for (String part : value.toLowerCase(Locale.ROOT).split(",")) {
            String directive = part.strip(); if (directive.isEmpty()) continue;
            // A named crawler header is still a relevant search-engine restriction.
            if (directive.contains(":") && !directive.matches("(max-snippet|max-image-preview|max-video-preview|unavailable_after):.*"))
                directive = directive.substring(directive.indexOf(':') + 1).strip();
            if (directive.matches("(max-snippet|max-image-preview|max-video-preview|unavailable_after):.+")) { known = true; continue; }
            for (String token : directive.split("\\s+")) {
                if (!allowed.contains(token)) { valid = false; continue; }
                known = true; noindex |= token.equals("noindex") || token.equals("none"); nofollow |= token.equals("nofollow") || token.equals("none");
            }
        }
        return new RobotsRules(noindex, nofollow, known && valid);
    }
    private static void checkRobots(String value, String label, String expected, boolean required, List<String> issues) {
        if (value.isBlank()) { if (required) issues.add("缺少" + label); return; }
        RobotsRules rules = robots(value);
        if (!rules.valid()) issues.add(label + "指令无效");
        if (!rules.value().equals(expected)) issues.add(label + "与索引策略不一致");
    }

    private record Link(URI uri, String text) {}
    private static List<Link> links(Element element, URI origin) {
        var result = new ArrayList<Link>();
        for (Element anchor : element.select(e -> e.tag.equals("a") && e.visible())) {
            String href = anchor.attr("href"), text = anchor.visibleText();
            if (href.isBlank() || href.startsWith("#") || text.isBlank()) continue;
            try {
                URI target = origin.resolve(href).normalize();
                if (absoluteUrl(target.toString()) != null && sameOrigin(origin, target) && target.getFragment() == null)
                    result.add(new Link(target, text));
            } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }
    private static URI absoluteUrl(String value) {
        try {
            URI uri = URI.create(value);
            return Set.of("https", "http").contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT)) &&
                uri.getHost() != null && uri.getUserInfo() == null ? uri : null;
        } catch (IllegalArgumentException ex) { return null; }
    }
    private static boolean sameOrigin(URI a, URI b) {
        return Objects.equals(a.getScheme(), b.getScheme()) && Objects.equals(a.getHost(), b.getHost()) && a.getPort() == b.getPort();
    }

    /** A bounded HTML tree is sufficient for the server templates; scripts and hidden ancestors never count as SSR. */
    private static final class HtmlDocument {
        final Element root = new Element("document", Map.of(), null);
        List<Element> select(java.util.function.Predicate<Element> predicate) { return root.select(predicate); }
        static HtmlDocument parse(String html) {
            HtmlDocument document = new HtmlDocument();
            var stack = new ArrayDeque<Element>(); stack.push(document.root);
            var matcher = Pattern.compile("<!--.*?-->|<(script|style|template)\\b([^>]*)>(.*?)</\\1\\s*>|<[^>]+>|[^<]+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
            while (matcher.find()) {
                String token = matcher.group();
                if (token.startsWith("<!--") || token.startsWith("<!") || token.startsWith("<?")) continue;
                if (matcher.group(1) != null) {
                    Element child = new Element(matcher.group(1).toLowerCase(Locale.ROOT), attributes(matcher.group(2)), stack.peek());
                    child.parts.add(matcher.group(3)); stack.peek().parts.add(child); continue;
                }
                if (!token.startsWith("<")) { stack.peek().parts.add(token); continue; }
                var tag = Pattern.compile("^<\\s*(/?)\\s*([A-Za-z][A-Za-z0-9:-]*)").matcher(token);
                if (!tag.find()) continue;
                String name = tag.group(2).toLowerCase(Locale.ROOT);
                if (!tag.group(1).isEmpty()) {
                    if (stack.stream().anyMatch(e -> e.tag.equals(name))) while (stack.size() > 1) if (stack.pop().tag.equals(name)) break;
                    continue;
                }
                Element child = new Element(name, attributes(token.substring(tag.end())), stack.peek());
                stack.peek().parts.add(child);
                if (!Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr").contains(name) &&
                    !token.endsWith("/>") && stack.size() < 128) stack.push(child);
            }
            return document;
        }
    }
    private static final class Element {
        final String tag; final Map<String, String> attrs; final Element parent; final List<Object> parts = new ArrayList<>();
        Element(String tag, Map<String, String> attrs, Element parent) { this.tag = tag; this.attrs = attrs; this.parent = parent; }
        String attr(String name) { return attrs.getOrDefault(name, ""); }
        boolean inside(String name) { return parent != null && (parent.tag.equals(name) || parent.inside(name)); }
        boolean hasClass(String name) { return java.util.Arrays.asList(attr("class").split("\\s+")).contains(name); }
        boolean visible() {
            return !Set.of("head", "script", "style", "template", "title").contains(tag) && !attrs.containsKey("hidden") &&
                !attr("aria-hidden").equalsIgnoreCase("true") && !attr("style").matches("(?is).*(display\\s*:\\s*none|visibility\\s*:\\s*hidden|content-visibility\\s*:\\s*hidden).*" ) &&
                (parent == null || parent.visible());
        }
        String allText() { return clean(rawText()); }
        String rawText() { var text = new StringBuilder(); appendText(text, false); return text.toString(); }
        String visibleText() { var text = new StringBuilder(); appendText(text, true); return clean(text.toString()); }
        private void appendText(StringBuilder text, boolean visibleOnly) {
            if (visibleOnly && !visible()) return;
            for (Object part : parts) {
                if (part instanceof Element e) { text.append(' '); e.appendText(text, visibleOnly); text.append(' '); }
                else text.append(part);
            }
        }
        List<Element> select(java.util.function.Predicate<Element> predicate) {
            var found = new ArrayList<Element>(); var pending = new ArrayDeque<Element>(); pending.add(this);
            while (!pending.isEmpty()) {
                Element next = pending.removeFirst(); if (predicate.test(next)) found.add(next);
                for (Object part : next.parts) if (part instanceof Element e) pending.addLast(e);
            }
            return found;
        }
    }
    private static Map<String, String> attributes(String source) {
        Map<String, String> attrs = new LinkedHashMap<>();
        var matcher = Pattern.compile("([A-Za-z_:][A-Za-z0-9:_.-]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?", Pattern.DOTALL).matcher(source);
        while (matcher.find()) attrs.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), StringEscapeUtils.unescapeHtml4(
            Objects.toString(matcher.group(2), Objects.toString(matcher.group(3), Objects.toString(matcher.group(4), "")))));
        return attrs;
    }
}
