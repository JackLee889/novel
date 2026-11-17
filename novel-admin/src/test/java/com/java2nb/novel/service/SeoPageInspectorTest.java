package com.java2nb.novel.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeoPageInspectorTest {
    static final String ORIGIN = "https://xs001.xyz";
    static final String BOOK_ID = "9007199254740993", CHAPTER_ID = "9007199254740995";

    @Test void everyContractedPageTypePassesWithActualSsrContentAndSchema() {
        for (String type : List.of("home", "category", "ranking", "book", "catalog", "chapter")) {
            var expected = expected(type, true, true, false, 200);
            var result = analyze(page(expected), expected);
            assertEquals(true, result.get("passed"), type + ": " + result.get("issues"));
            assertTrue((long) result.get("contentLinks") > 0, type);
            assertFalse(result.containsKey("html"));
        }
    }
    @Test void validNoindexPagesStillCheckSsrAndSchemaWithoutDemandingIndexPermission() {
        for (String type : List.of("home", "category", "ranking", "book", "catalog", "chapter")) {
            var expected = expected(type, false, true, false, 200);
            var result = analyze(page(expected), expected);
            assertEquals(true, result.get("passed"), type + ": " + result.get("issues"));
            assertEquals("noindex,follow", result.get("effectiveRobots"));
        }
    }
    @Test void emptyCategoryRankingAndCatalogHaveValidNoindexEmptyStates() {
        for (String type : List.of("category", "ranking", "catalog")) {
            var expected = expected(type, false, false, false, 200);
            var result = analyze(page(expected), expected);
            assertEquals(true, result.get("passed"), type + ": " + result.get("issues"));
            assertEquals(0L, result.get("contentLinks"));
        }
    }
    @Test void homeDoesNotInventARequirementForWebsiteSchema() {
        var expected = expected("home", true, true, false, 200);
        var result = analyze(page(expected), expected);
        assertEquals(0, result.get("jsonLdBlocks")); assertEquals(true, result.get("passed"));
        assertEquals("中文 & 标题", result.get("title"));
    }
    @Test void unpublishedOrMissingBodyCanPassOnlyWithExpected404AndNoindex() {
        var expected = expected("chapter", false, false, false, 404);
        String html = "<html><head><title>页面未找到</title></head><body><main><h1>暂时找不到这一页</h1></main></body></html>";
        var result = analyze(html, expected);
        assertEquals(true, result.get("passed"), result.get("issues").toString());
        assertEquals("", result.get("canonical"));
        var wrongStatus = SeoPageInspector.analyze(uri(expected), 200, "text/html", html, false, List.of("noindex,follow"), expected);
        assertIssue(wrongStatus, "HTTP状态与公开策略不一致，预期404");
    }
    @Test void private404CannotReturnIndexOrOrdinaryBookContent() {
        var expected = expected("book", false, false, false, 404);
        String html = "<head><title>404</title><meta name='robots' content='index,follow'></head><main><section class='detail-hero'><h1>雪山</h1></section></main>";
        var result = analyze(html, expected);
        assertIssue(result, "页面robots与索引策略不一致");
        assertIssue(result, "不可公开页面仍输出书籍或章节主体");
    }
    @Test void allHeaderValuesAffectTheFinalDecisionBeforePassedIsComputed() {
        var expected = expected("home", true, true, false, 200);
        var result = SeoPageInspector.analyze(uri(expected), 200, "text/html", page(expected), false,
            List.of("index,follow", "googlebot: noindex"), expected);
        assertIssue(result, "X-Robots-Tag与索引策略不一致");
        assertIssue(result, "有效robots规则与索引策略不一致");
        assertEquals("noindex,follow", result.get("effectiveRobots"));
    }
    @Test void mismatchingMetaCannotHideBehindARestrictiveHeader() {
        var expected = expected("chapter", false, true, false, 200);
        var result = analyze(page(expected).replace("content='noindex,follow'", "content='index,follow'"), expected);
        assertIssue(result, "页面robots与索引策略不一致");
        assertEquals("noindex,follow", result.get("effectiveRobots"));
    }
    @Test void missingOrMalformedRobotsAndNofollowAreFailures() {
        var expected = expected("home", true, true, false, 200);
        assertIssue(SeoPageInspector.analyze(uri(expected), 200, "text/html", page(expected), false, List.of(), expected), "缺少X-Robots-Tag");
        assertIssue(analyze(page(expected).replace("content='index,follow'", "content='please-index'"), expected), "页面robots指令无效");
        assertIssue(analyze(page(expected).replace("content='index,follow'", "content='none'"), expected), "页面robots与索引策略不一致");
    }
    @Test void crawlerSpecificMetaRestrictionsParticipateInEffectiveRules() {
        var expected = expected("home", true, true, false, 200);
        assertIssue(analyze(page(expected).replace("</head>", "<meta name='googlebot' content='noindex'></head>"), expected), "有效robots规则与索引策略不一致");
    }
    @Test void duplicateCanonicalFailsEvenWhenBothTargetsAreCorrect() {
        var expected = expected("home", true, true, false, 200);
        var result = analyze(page(expected).replace("</head>", "<link rel='alternate canonical' href='" + uri(expected) + "'></head>"), expected);
        assertIssue(result, "存在重复canonical"); assertEquals(2, result.get("canonicalCount"));
    }
    @Test void boilerplateTextAndFooterLinksCannotSubstituteForThePageBody() {
        var expected = expected("home", true, true, false, 200);
        String html = head(expected, "") + "<header>书城导航</header><main></main><footer><a href='/book/42.html'>小说</a></footer></body></html>";
        var result = analyze(html, expected);
        assertIssue(result, "页面主体缺少可见标题"); assertIssue(result, "缺少对应内容的可抓取HTML链接");
    }
    @Test void scriptsHiddenTextAndNonCrawlableHrefsCannotSubstituteForBookResults() {
        var expected = expected("category", true, true, false, 200);
        String replacement = "<div id='book-results'><article hidden><a href='/book/42.html'>隐藏小说</a></article>" +
            "<a href='javascript:loadBooks()'>动态小说</a><a href='#books'>锚点小说</a><a href='https://evil.example/book/42.html'>外站小说</a>" +
            "<a href='/book/43.html' style='display:none'>隐藏小说</a><a href='/book/44.html'></a>" +
            "<script>document.write(\"<a href='/book/45.html'>脚本小说</a>\")</script></div>";
        String html = page(expected).replace("<div id='book-results'>" + bookLink() + "</div>", replacement);
        var result = analyze(html, expected);
        assertIssue(result, "缺少对应内容的可抓取HTML链接"); assertEquals(0L, result.get("contentLinks"));
    }
    @Test void emptyDeclaredPageCannotExposeUnaccountedBookRows() {
        var expected = expected("category", false, false, false, 200);
        assertIssue(analyze(page(expected).replace("还没有找到合适的作品", bookLink()), expected), "页面书目或章节与数据库空状态不一致");
    }
    @Test void catalogLinksMustBelongToTheRequestedBookAndKeepSnowflakeStrings() {
        var expected = expected("catalog", true, true, false, 200);
        assertIssue(analyze(page(expected).replace("href='/book/" + BOOK_ID + "/" + CHAPTER_ID + ".html'", "href='/book/42/9.html'"), expected), "缺少对应内容的可抓取HTML链接");
        var good = analyze(page(expected), expected);
        assertTrue(((String) good.get("url")).contains(BOOK_ID)); assertEquals(1L, good.get("contentLinks"));
    }
    @Test void freeReaderRequiresRealVisibleProseAndTheCorrespondingTitle() {
        var expected = expected("chapter", false, true, false, 200);
        assertIssue(analyze(page(expected).replace("<article id='reader-prose'>", "<article id='reader-prose' hidden>"), expected), "免费章节缺少服务端输出的正文");
        assertIssue(analyze(page(expected).replace("<h1>第一章</h1>", "<h1>不属于此章</h1>"), expected), "页面主体标题与数据库内容不一致");
    }
    @Test void paidReaderIsAValidNoindexShellButCannotLeakProseOrBootstrapParagraphs() {
        var expected = expected("chapter", false, false, true, 200);
        var good = analyze(page(expected), expected);
        assertEquals(true, good.get("passed"), good.get("issues").toString());
        assertIssue(analyze(page(expected).replace("<article id='reader-prose'></article>", "<article id='reader-prose' hidden>收费正文</article>"), expected), "收费章节向匿名诊断输出了正文");
        assertIssue(analyze(page(expected).replace("</body>", "<script id='reader-data' type='application/json'>{\"paragraphs\":[{\"text\":\"收费正文\"}]}</script></body>"), expected), "收费章节向匿名诊断输出了正文");
    }
    @Test void scalarEmptyOrWronglyShapedJsonLdNeverCountsAsValidSchema() {
        var expected = expected("book", true, true, false, 200);
        for (String json : List.of("null", "42", "{}", "[]", "{\"@context\":\"https://schema.org\",\"@graph\":[]}", "{\"@context\":\"https://schema.org\",\"@graph\":[42]}")) {
            var result = analyze(page(expected).replace(schema(expected), json), expected);
            assertEquals(false, result.get("passed"), json); assertIssue(result, "缺少Book结构化数据");
        }
    }
    @Test void expectedSchemaTypesAreRequiredEvenWhenAnotherJsonObjectParses() {
        var expected = expected("book", true, true, false, 200);
        var result = analyze(page(expected).replace(schema(expected), "{\"@context\":\"https://schema.org\",\"@type\":\"WebSite\"}"), expected);
        assertIssue(result, "缺少Book结构化数据"); assertIssue(result, "缺少BreadcrumbList结构化数据");
    }
    @Test void bookSchemaRequiresRealContextNameAuthorDescriptionLanguageAndUrl() {
        var expected = expected("book", true, true, false, 200);
        for (String[] change : List.of(new String[]{"https://schema.org", "https://evil.example"}, new String[]{"\"name\":\"雪山\"", "\"name\":\"虚构书名\""},
            new String[]{"\"name\":\"作者\"", "\"name\":\"假作者\""}, new String[]{"\"description\":\"故事简介\"", "\"description\":\"虚构简介\""},
            new String[]{"\"inLanguage\":\"zh-CN\"", "\"inLanguage\":\"\""}, new String[]{"\"url\":\"" + uri(expected) + "\"", "\"url\":\"https://evil.example/\""})) {
            assertEquals(false, analyze(page(expected).replace(change[0], change[1]), expected).get("passed"), change[0]);
        }
    }
    @Test void breadcrumbPositionsAndCanonicalEndpointAreValidated() {
        var expected = expected("catalog", true, true, false, 200);
        assertIssue(analyze(page(expected).replace("\"position\":2", "\"position\":7"), expected), "BreadcrumbList条目类型或顺序无效");
        assertIssue(analyze(page(expected).replace("\"item\":\"" + uri(expected) + "\"", "\"item\":\"" + ORIGIN + "/book/42.html\""), expected), "BreadcrumbList首尾URL与页面导航不一致");
    }
    @Test void unquotedAttributesAndEntitiesAreParsedAsActualHtml() {
        var expected = expected("home", true, true, false, 200);
        var result = analyze(page(expected).replace("rel='canonical'", "rel=canonical").replace("id='home-categories'", "id=home-categories"), expected);
        assertEquals(true, result.get("passed"), result.get("issues").toString()); assertEquals("中文 & 标题", result.get("title"));
    }
    @Test void redirectsNonHtmlInvalidJsonAndOversizeAreVisibleFailures() {
        var expected = expected("book", true, true, false, 200);
        var result = SeoPageInspector.analyze(uri(expected), 302, "application/json", page(expected).replace(schema(expected), "{invalid}"), true, List.of("index,follow"), expected);
        assertEquals(false, result.get("passed")); assertEquals(1, result.get("invalidJsonLdBlocks"));
        assertIssue(result, "响应不是HTML"); assertIssue(result, "响应超过512KiB，诊断仅检查前段内容");
    }
    @Test void httpTransportFeedsHeadersIntoAnalysisWithoutCarryingCookies() {
        var expected = expected("home", true, true, false, 200);
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(page(expected).getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/html"), "X-Robots-Tag", List.of("noindex,follow")), (a, b) -> true));
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
            .thenReturn(CompletableFuture.completedFuture(response));
        var result = new SeoPageInspector(client).inspect(uri(expected), expected);
        assertIssue(result, "X-Robots-Tag与索引策略不一致"); assertEquals("noindex,follow", result.get("xRobotsTag"));
        var capture = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(capture.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertTrue(capture.getValue().headers().firstValue("Cookie").isEmpty());
        assertEquals(8, capture.getValue().timeout().orElseThrow().toSeconds());
    }
    @Test void boundedSubscriberCancelsAtCeilingInsteadOfBufferingFullBody() {
        var subscriber = new SeoPageInspector.BoundedBodySubscriber(); Flow.Subscription transport = mock(Flow.Subscription.class);
        subscriber.onSubscribe(transport); subscriber.onNext(List.of(ByteBuffer.wrap(new byte[SeoPageInspector.MAX_BYTES + 4096])));
        verify(transport).cancel(); assertEquals(SeoPageInspector.MAX_BYTES + 1, subscriber.getBody().toCompletableFuture().join().length);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[100])));
        assertEquals(SeoPageInspector.MAX_BYTES + 1, subscriber.getBody().toCompletableFuture().join().length);
    }
    @Test void transportFailureDoesNotProducePassingDiagnosis() {
        var subscriber = new SeoPageInspector.BoundedBodySubscriber(); subscriber.onError(new IllegalStateException("fixture"));
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    private static SeoPageInspector.ExpectedPage expected(String type, boolean indexable, boolean content, boolean paid, int status) {
        return new SeoPageInspector.ExpectedPage(type, BOOK_ID, type.equals("chapter") ? CHAPTER_ID : null, indexable, status, content, paid,
            "雪山", "作者", "故事简介", "第一章");
    }
    private static URI uri(SeoPageInspector.ExpectedPage expected) {
        return URI.create(ORIGIN + switch (expected.pageType()) {
            case "home" -> "/"; case "category" -> "/book/bookclass.html"; case "ranking" -> "/book/book_ranking.html";
            case "book" -> "/book/" + BOOK_ID + ".html"; case "catalog" -> "/book/indexList-" + BOOK_ID + ".html";
            default -> "/book/" + BOOK_ID + "/" + CHAPTER_ID + ".html";
        });
    }
    private static String head(SeoPageInspector.ExpectedPage expected, String schema) {
        return "<html><head><title>中文 &amp; 标题</title><link rel='canonical' href='" + uri(expected) + "'>" +
            "<meta name='robots' content='" + (expected.indexable() ? "index" : "noindex") + ",follow'><meta name='description' content='摘要'>" +
            (schema.isBlank() ? "" : "<script type='application/ld+json'>" + schema + "</script>") + "</head><body><header><a href='/'>首页</a></header>";
    }
    private static String bookLink() { return "<article><h3><a href='/book/" + BOOK_ID + ".html'>雪山</a></h3><p>作者 · 故事简介</p></article>"; }
    private static String page(SeoPageInspector.ExpectedPage expected) {
        String catalog = "<a href='/book/indexList-" + BOOK_ID + ".html'>目录</a>";
        String body = switch (expected.pageType()) {
            case "home" -> "<h1>发现小说</h1><nav id='home-categories'><a href='/book/bookclass.html'>全部作品</a></nav>" + (expected.contentExpected() ? bookLink() : "暂无作品");
            case "category" -> "<h1>全部作品</h1><div id='book-results'>" + (expected.contentExpected() ? bookLink() : "还没有找到合适的作品") + "</div>";
            case "ranking" -> "<h1>点击榜单</h1><div id='ranking-results'>" + (expected.contentExpected() ? bookLink() : "这个榜单还没有作品") + "</div>";
            case "book" -> "<section class='detail-hero'><h1>雪山</h1><p>作者 著</p>" + catalog + "</section><div class='prose'>故事简介</div>";
            case "catalog" -> "<h1>雪山</h1><p>章节目录</p><div id='chapter-list'>" + (expected.contentExpected() ?
                "<a href='/book/" + BOOK_ID + "/" + CHAPTER_ID + ".html'>第一章</a>" : "") + "</div>" +
                (expected.contentExpected() ? "" : "<div id='chapter-empty'>还没有章节</div>");
            default -> "<a href='/book/" + BOOK_ID + ".html'>雪山</a><h1>第一章</h1><article id='reader-prose'>" +
                (expected.paidChapter() ? "" : "这是服务端输出的第一段小说正文。") + "</article>" +
                (expected.paidChapter() ? "<div id='access-message'>付费章节，请登录后订阅。</div>" : "") + catalog;
        };
        return head(expected, schema(expected)) + "<main>" + body + "</main></body></html>";
    }
    private static String schema(SeoPageInspector.ExpectedPage expected) {
        if (expected.pageType().equals("home")) return "";
        var graph = new ArrayList<Map<String, Object>>();
        if (expected.pageType().equals("book")) {
            var book = new LinkedHashMap<String, Object>(); book.put("@type", "Book"); book.put("name", "雪山");
            book.put("author", Map.of("@type", "Person", "name", "作者")); book.put("description", "故事简介");
            book.put("inLanguage", "zh-CN"); book.put("url", uri(expected).toString()); graph.add(book);
        }
        String name = switch (expected.pageType()) { case "category" -> "全部作品"; case "ranking" -> "点击榜单"; case "chapter" -> "第一章"; case "catalog" -> "章节目录"; default -> "雪山"; };
        graph.add(Map.of("@type", "BreadcrumbList", "itemListElement", List.of(
            Map.of("@type", "ListItem", "position", 1, "name", "首页", "item", ORIGIN + "/"),
            Map.of("@type", "ListItem", "position", 2, "name", name, "item", uri(expected).toString()))));
        return JsonMapper.builder().build().writeValueAsString(Map.of("@context", "https://schema.org", "@graph", graph));
    }
    private static Map<String, Object> analyze(String html, SeoPageInspector.ExpectedPage expected) {
        return SeoPageInspector.analyze(uri(expected), expected.status(), "text/html;charset=UTF-8", html, false,
            List.of(expected.indexable() ? "index,follow" : "noindex,follow"), expected);
    }
    private static void assertIssue(Map<String, Object> result, String issue) {
        assertEquals(false, result.get("passed")); assertTrue(((List<?>) result.get("issues")).contains(issue), result.get("issues").toString());
    }
}
