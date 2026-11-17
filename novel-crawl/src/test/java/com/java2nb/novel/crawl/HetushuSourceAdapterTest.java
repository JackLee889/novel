package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.HetushuSourceAdapter;
import com.java2nb.novel.utils.CrawlMetadataException;
import com.java2nb.novel.utils.CrawlRequestContext;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.utils.CrawlAccessChallengeException;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.utils.ImpersonatingHttpClient;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HetushuSourceAdapterTest {
    private static final String BOOK = "https://hetushu.com/book/4/index.html";
    private static final String DIR = "https://hetushu.com/book/4/dir.json";
    private static final String CHAPTER = "https://hetushu.com/book/4/2230.html";
    private static final String METADATA = """
        <div class="book_info finish"><img alt="测试书" src="/book/cover.pic/cover_4.jpg">
        <h2>测试书</h2><div>作者：<a href="/author/test.html">测试作者</a></div>
        <div class="intro"><p>第一段简介</p><p>第二段简介<script>bad()</script></p></div></div>
        """;
    private static final String DIRECTORY = """
        [["dt","第一卷"],["dd","第一章","2230"],["dt","第二卷"],["dd","第二章","2231"]]
        """;

    private static String token(String order) {
        return Base64.getEncoder().encodeToString(order.getBytes(StandardCharsets.US_ASCII));
    }

    private static String chapter(String paragraphs) {
        return "<body data-doctype=\"json\" data-randomtype=\"substep\"><div id=\"content\">"
            + "<div class=\"mask mask2\"></div><h2>卷名</h2><h2>章名</h2>"
            + paragraphs + "</div></body>";
    }

    private static HetushuSourceAdapter.PageReader reader(String page, String order) {
        return new HetushuSourceAdapter.PageReader() {
            @Override public String getPage(String url) { return page; }
            @Override public String getOrderToken(String url, String referer) {
                assertTrue(url.startsWith("https://hetushu.com/book/4/r2230.json?_="));
                assertEquals(CHAPTER, referer);
                return order;
            }
        };
    }

    private static RuleBean rule() throws Exception {
        return new ObjectMapper().readValue(
            new ClassPathResource("static/crawl-templates/hetushu.json").getInputStream(), RuleBean.class);
    }

    @Test
    void acceptsOnlyTheCanonicalPublicBookRoutes() {
        assertTrue(HetushuSourceAdapter.supports(BOOK));
        assertTrue(HetushuSourceAdapter.supports(DIR));
        assertTrue(HetushuSourceAdapter.supports(CHAPTER));
        assertTrue(HetushuSourceAdapter.supports("https://hetushu.com/book/index.php?type=x&page=2"));
        for (String invalid : List.of("https://hetushu.com.evil.test/book/4/2230.html",
            "https://user@hetushu.com/book/4/index.html", "http://hetushu.com/book/4/index.html",
            "https://hetushu.com:8443/book/4/index.html", "https://hetushu.com/book/cover.pic/cover_4.jpg",
            "https://hetushu.com/book/4/index.html#other", "https://hetushu.com/book/0/index.html")) {
            assertFalse(HetushuSourceAdapter.supports(invalid), invalid);
        }
        assertFalse(HetushuSourceAdapter.supports(null));
    }

    @Test
    void categoryDeduplicatesCoverLinksAndKeepsTheLastPage() throws Exception {
        String page = """
            <ul class="book_list"><li><a href="/book/4/index.html"><img src="cover.jpg"></a>
            <div class="name"><a href="/book/4/index.html">测试书</a></div></li>
            <li><a href="/book/5/index.html">另一书</a></li></ul>
            <div class="page"><span class="current">2</span><a href="index.php?page=3">3</a>
            <a href="index.php?type=x&amp;page=679">679</a></div>
            """;
        String html = HetushuSourceAdapter.getHtml("https://hetushu.com/book/index.php?type=x&page=2", reader(page, null));
        var ids = Pattern.compile(rule().getBookIdPatten()).matcher(html).results().map(m -> m.group(1)).toList();
        assertEquals(List.of("4", "5"), ids);
        assertTrue(html.contains("data-current-page=\"2\""));
        assertTrue(html.contains("data-total-pages=\"679\""));
    }

    @Test
    void metadataAndTemplateParseAuthorCoverAndCompletion() throws Exception {
        String html = HetushuSourceAdapter.getHtml(BOOK, reader(METADATA, null));
        var books = new ArrayList<Book>();
        var parser = new CrawlParser(new CrawlHttpClient() {
            @Override public String get(String url, String charset) { return html; }
        }, mock(StringRedisTemplate.class), CrawlTestStores.healthy());
        parser.parseBook(rule(), "4", books::add);
        Book book = books.getFirst();
        assertEquals("测试书", book.getBookName());
        assertEquals("测试作者", book.getAuthorName());
        assertEquals("https://hetushu.com/book/cover.pic/cover_4.jpg", book.getPicUrl());
        assertEquals((byte) 1, book.getBookStatus());
        assertTrue(book.getBookDesc().contains("第一段简介"));
        assertFalse(book.getBookDesc().contains("script"));
        assertEquals(RuleBean.NewBookStatus.ALL, rule().getNewBookStatus());
        assertEquals(13, rule().getCatIdRules().values().stream().mapToInt(List::size).sum());
    }

    @Test
    void metadataKeepsAnExplicitPendingAuthorAndContinues() throws Exception {
        String html = HetushuSourceAdapter.getHtml(BOOK, reader(METADATA.replace("测试作者", "佚名"), null));
        assertTrue(html.contains("<span class=\"author\">作者待核验</span>"));
    }

    @Test
    void anExistingBookKeepsItsAuthorWhenTheAuthorLinkDisappears() throws Exception {
        String page = METADATA.replace("<a href=\"/author/test.html\">测试作者</a>", "");
        try (var context = CrawlRequestContext.bound("4", "测试书", "测试作者")) {
            String html = HetushuSourceAdapter.getHtml(BOOK, reader(page, null));
            assertTrue(html.contains("<span class=\"author\">测试作者</span>"));
        }
    }

    @Test
    void authorMetadataCanRecoverAChangedAuthorLink() throws Exception {
        String page = METADATA.replace("<a href=\"/author/test.html\">测试作者</a>", "")
            + "<meta property=\"og:novel:author\" content=\"测试作者\">";
        assertTrue(HetushuSourceAdapter.getHtml(BOOK, reader(page, null))
            .contains("<span class=\"author\">测试作者</span>"));
    }

    @Test
    void anUnmarkedBookDoesNotBecomeSerializingByAssumption() throws Exception {
        String html = HetushuSourceAdapter.getHtml(BOOK, reader(METADATA.replace("book_info finish", "book_info"), null));
        new CrawlParser(new CrawlHttpClient() {
            @Override public String get(String url, String charset) { return html; }
        }, mock(StringRedisTemplate.class), CrawlTestStores.healthy()).parseBook(rule(), "4", book -> assertNull(book.getBookStatus()));
    }

    @Test
    void catalogUsesActualChapterIdsAndIgnoresVolumeRows() throws Exception {
        String html = HetushuSourceAdapter.getHtml(DIR, reader(DIRECTORY, null));
        var ids = Pattern.compile(rule().getIndexIdPatten()).matcher(html).results().map(m -> m.group(1)).toList();
        var names = Pattern.compile(rule().getIndexNamePatten()).matcher(html).results().map(m -> m.group(1)).toList();
        assertEquals(List.of("2230", "2231"), ids);
        assertEquals(List.of("第一章", "第二章"), names);
        assertFalse(html.contains("第一卷"));
    }

    @Test
    void catalogRejectsDuplicateIdsAndMalformedEntries() {
        for (String body : List.of("[]", "{}", "null", "<html>错误页</html>", "[invalid json]", "[[\"dd\",\"章名\",\"../other\"]]",
            "[[\"dd\",\"章名\",\"1\"],[\"dd\",\"另一章\",\"1\"]]", "[[\"dd\",\"章名\"]]")) {
            assertDoesNotThrow(() -> assertNull(HetushuSourceAdapter.getHtml(DIR, reader(body, null))));
        }
    }

    @Test
    void paragraphOrderMatchesTheIndependentBrowserGoldenPermutation() throws Exception {
        var golden = new ObjectMapper().readTree(new ClassPathResource("hetushu/order-golden.json").getInputStream());
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 51; i++) raw.append("<div>段落").append(i).append("</div>");
        String html = HetushuSourceAdapter.getHtml(CHAPTER, reader(chapter(raw.toString()), golden.get("token").asString()));
        List<String> expected = new ArrayList<>();
        for (var index : golden.get("rawIndices")) expected.add("段落" + index.asInt());
        var document = Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        String[] actual = document.getElementById("chapter-content").html().split("<br>");
        assertEquals(expected, List.of(actual));
        assertFalse(html.contains("卷名"));
        assertFalse(html.contains("mask"));
    }

    @Test
    void malformedOrIncompleteOrderNeverReturnsUnorderedText() {
        String page = chapter("<div>一</div><div>二</div>");
        for (String value : List.of("not-base64!", token("0"), token("0A%0"), token("0A%99"), token("0A%-1"))) {
            assertDoesNotThrow(() -> assertNull(HetushuSourceAdapter.getHtml(CHAPTER, reader(page, value))));
        }
        assertDoesNotThrow(() -> assertNull(HetushuSourceAdapter.getHtml(CHAPTER, reader(page, null))));
    }

    @Test
    void recoveredContentPreservesFormattingAndMakesIllustrationUrlsAbsolute() throws Exception {
        String page = chapter("<div>正文<b>加粗</b><img src=\"/book/4/picture.png\"><script>bad()</script></div>");
        String html = HetushuSourceAdapter.getHtml(CHAPTER, reader(page, token("0")));
        assertTrue(html.contains("<b>加粗</b>"));
        assertTrue(html.contains("https://hetushu.com/book/4/picture.png"));
        assertFalse(html.contains("script"));
    }

    @Test
    void removesTheSitesCssHiddenNoiseWithoutRemovingVisibleFormatting() throws Exception {
        StringBuilder paragraph = new StringBuilder("<div>真实正文<b>加粗</b><em>强调</em>");
        for (String tag : List.of("acronym", "bdo", "big", "cite", "code", "dfn", "kbd", "q",
            "s", "samp", "strike", "tt", "u", "var")) {
            paragraph.append('<').append(tag).append(">隐藏噪声</").append(tag).append('>');
        }
        String html = HetushuSourceAdapter.getHtml(CHAPTER, reader(chapter(paragraph + "</div>"), token("0")));
        assertEquals("真实正文加粗强调", Jsoup.parse(html).text());
        assertTrue(html.contains("<b>加粗</b>"));
        assertTrue(html.contains("<em>强调</em>"));
    }

    @Test
    void accessCooldownAndStopPropagateFromTheOrderRequest() {
        var restriction = new CrawlAccessChallengeException(10);
        var reader = new HetushuSourceAdapter.PageReader() {
            @Override public String getPage(String url) { return chapter("<div>正文</div>"); }
            @Override public String getOrderToken(String url, String referer) { throw restriction; }
        };
        assertSame(restriction, assertThrows(CrawlAccessChallengeException.class,
            () -> HetushuSourceAdapter.getHtml(CHAPTER, reader)));
        var stopped = new HetushuSourceAdapter.PageReader() {
            @Override public String getPage(String url) { return chapter("<div>正文</div>"); }
            @Override public String getOrderToken(String url, String referer) throws InterruptedException {
                throw new InterruptedException();
            }
        };
        assertThrows(InterruptedException.class, () -> HetushuSourceAdapter.getHtml(CHAPTER, stopped));
    }

    @Test
    @SuppressWarnings("unchecked")
    void productionEntrypointUsesTheAdapterAndCollectsTwoChaptersWithoutADatabase() throws Exception {
        var transport = mock(ImpersonatingHttpClient.class);
        when(transport.get(anyString(), eq("UTF-8"))).thenAnswer(call -> {
            String url = call.getArgument(0);
            if (url.equals(BOOK)) return METADATA;
            if (url.equals(DIR)) return DIRECTORY;
            if (url.endsWith("2230.html")) return chapter("<div>第一章正文内容。</div>");
            if (url.endsWith("2231.html")) return chapter("<div>第二章正文内容。</div>");
            throw new AssertionError(url);
        });
        when(transport.getAjaxToken(anyString(), anyString())).thenReturn(token("0"));
        var client = new CrawlHttpClient();
        ReflectionTestUtils.setField(client, "pageClient", transport);
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        var parser = new CrawlParser(client, redis, CrawlTestStores.healthy());
        var books = new ArrayList<Book>();
        parser.parseBook(rule(), "4", books::add);
        Book book = books.getFirst();
        book.setId(1000L);
        var saved = new ArrayList<BookContent>();
        assertTrue(parser.parseBookIndexAndContent("4", book, rule(), 999, Map.of(),
            batch -> saved.addAll(batch.getBookContentList()), null));
        assertEquals(2, saved.size());
        assertEquals("第一章正文内容。", saved.get(0).getContent());
        assertEquals("第二章正文内容。", saved.get(1).getContent());
        verify(transport).getAjaxToken(startsWith("https://hetushu.com/book/4/r2230.json?_="), eq(CHAPTER));
        verify(transport).getAjaxToken(startsWith("https://hetushu.com/book/4/r2231.json?_="), eq("https://hetushu.com/book/4/2231.html"));
    }
}
