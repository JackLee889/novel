package com.java2nb.novel.crawl;

import tools.jackson.databind.ObjectMapper;
import com.java2nb.novel.core.crawl.Bqg616SourceAdapter;
import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.utils.CrawlMetadataException;
import com.java2nb.novel.utils.CrawlRequestContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class Bqg616SourceAdapterTest {
    private static final String BOOK_URL = "https://www.bqg616.cc/#/book/2530/";
    private static final String BOOK_API =
        "https://apibi.cc/api/book?token=E8XyciA4TcACcVXRS0wY%2Bw%3D%3D";
    private static final String CATALOG_API =
        "https://apibi.cc/api/booklist?token=E8XyciA4TcACcVXRS0wY%2Bw%3D%3D";
    private static final String CHAPTER_API =
        "https://apibi.cc/api/chapter?token=b%2BvXnT3wjuXQsxBmZh033ZjqwezLEinKfOakcVaiDx0%3D";
    // 公开接口的实际详情响应；短 JSON 也必须能被正确解析。
    private static final String METADATA = """
        {"id":"2530","title":"万相之王","sortname":"玄幻","author":"天蚕土豆","full":"连载",
        "intro":"天地间有万相，我李洛，终将成为那万相之王。","lastchapterid":"1851",
        "lastchapter":"秦漪(番外篇)！！","lastupdate":"2026-09-29","dirid":"2530"}
        """;
    private Bqg616SourceAdapter adapter;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestTemplate client = new RestTemplate();
        server = MockRestServiceServer.bindTo(client).build();
        adapter = new Bqg616SourceAdapter(client, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"佚名", "未知作者", ""})
    void newBookApiMetadataKeepsAnExplicitPendingAuthorAndContinues(String author) throws Exception {
        server.expect(requestTo(BOOK_API)).andRespond(withSuccess(
            METADATA.replace("天蚕土豆", author), MediaType.APPLICATION_JSON));
        assertTrue(adapter.getHtml(BOOK_URL).contains("<span class=\"author\">作者待核验</span>"));
        server.verify();
    }

    @Test
    void existingApiBookRetainsItsAuthorWhenTheSourceFieldBecomesEmpty() {
        server.expect(requestTo(BOOK_API)).andRespond(withSuccess(
            METADATA.replace("天蚕土豆", ""), MediaType.APPLICATION_JSON));
        try (var request = CrawlRequestContext.bound("2530", "万相之王", "天蚕土豆")) {
            assertTrue(adapter.getHtml(BOOK_URL).contains("<span class=\"author\">天蚕土豆</span>"));
        } catch (InterruptedException e) { throw new AssertionError(e); }
        server.verify();
    }

    @Test
    void tokensMatchObservedBrowserRequests() throws Exception {
        assertEquals("E8XyciA4TcACcVXRS0wY%2Bw%3D%3D",
            Bqg616SourceAdapter.encodeToken("{\"id\":\"2530\"}"));
        assertEquals("b%2BvXnT3wjuXQsxBmZh033ZjqwezLEinKfOakcVaiDx0%3D",
            Bqg616SourceAdapter.encodeToken("{\"id\":2530,\"chapterid\":1}"));
        assertEquals("b%2BvXnT3wjuXQsxBmZh033a8LdBYyF6XlnrEXhbbymGg%3D",
            Bqg616SourceAdapter.encodeToken("{\"id\":2530,\"chapterid\":1851}"));
    }

    @Test
    void readsShortMetadataAndEntireCatalogWithStablePositionalIds() throws Exception {
        server.expect(requestTo(BOOK_API)).andExpect(header("Referer", "https://www.bqg616.cc/"))
            .andRespond(withSuccess(METADATA, MediaType.APPLICATION_JSON));
        server.expect(requestTo(CATALOG_API)).andRespond(withSuccess(
            "{\"list\":[\"第1章 我有三个相宫\",\"第二章 不想退婚的未婚妻\",\"秦漪(番外篇)！！\"]}",
            MediaType.APPLICATION_JSON));
        String metadata = adapter.getHtml(BOOK_URL);
        assertTrue(metadata.contains("<h1>万相之王</h1>"));
        assertTrue(metadata.contains("<span class=\"author\">天蚕土豆</span>"));
        assertTrue(metadata.contains("https://www.bqg616.cc/bookimg/2/2530.jpg"));
        assertTrue(metadata.contains("<time>2026-09-29</time>"));
        String catalog = adapter.getHtml(BOOK_URL + "?catalog=1");
        assertTrue(catalog.contains("<dd><a href=\"1.html\">第1章 我有三个相宫</a></dd>"));
        assertTrue(catalog.contains("<dd><a href=\"3.html\">秦漪(番外篇)！！</a></dd>"));
        server.verify();
    }

    @Test
    void usesDiridRatherThanUrlIdForTheCatalog() throws Exception {
        server.expect(requestTo(BOOK_API)).andRespond(withSuccess(
            METADATA.replace("\"dirid\":\"2530\"", "\"dirid\":\"999\""), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://apibi.cc/api/booklist?token="
            + Bqg616SourceAdapter.encodeToken("{\"id\":\"999\"}")))
            .andRespond(withSuccess("{\"list\":[\"第一章\"]}", MediaType.APPLICATION_JSON));
        assertTrue(adapter.getHtml(BOOK_URL + "?catalog=1").contains("1.html"));
        server.verify();
    }

    @Test
    void canonicalTemplateMapsTheObservedCompletedStatus() throws Exception {
        server.expect(requestTo(BOOK_API)).andRespond(withSuccess(
            METADATA.replace("\"full\":\"连载\"", "\"full\":\"已经完本\""), MediaType.APPLICATION_JSON));
        String metadata = adapter.getHtml(BOOK_URL);
        RuleBean rule = new ObjectMapper().readValue(
            new ClassPathResource("static/crawl-templates/bqg616.json").getInputStream(), RuleBean.class);
        CrawlHttpClient bounded = new CrawlHttpClient() {
            @Override public String get(String url, String charset) { return metadata; }
        };
        new CrawlParser(bounded, new StringRedisTemplate(), CrawlTestStores.healthy()).parseBook(rule, "2530",
            book -> assertEquals(Byte.valueOf((byte) 1), book.getBookStatus()));
        server.verify();
    }

    @Test
    void paginatesTheCompleteApiCategoryWithoutRefetchingIt() throws Exception {
        List<Map<String, String>> books = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            books.add(Map.of("id", String.valueOf(i), "title", "小说" + i));
        }
        String category = new ObjectMapper().writeValueAsString(Map.of("data", books));
        server.expect(requestTo("https://apibi.cc/api/sort?sort=xuanhuan"))
            .andRespond(withSuccess(category, MediaType.APPLICATION_JSON));
        String first = adapter.getHtml("https://www.bqg616.cc/#/xuanhuan?page=1");
        String last = adapter.getHtml("https://www.bqg616.cc/#/xuanhuan?page=2");
        assertTrue(first.contains("data-total=\"2\""));
        assertTrue(first.contains("book/15/"));
        assertFalse(first.contains("book/16/"));
        assertTrue(last.contains("book/16/"));
        assertFalse(last.contains("book/15/"));
        assertNull(adapter.getHtml("https://www.bqg616.cc/#/xuanhuan?page=0"));
        server.verify();
    }

    @Test
    void rendersOnlyTheReturnedTextAndEscapesHtml() throws Exception {
        server.expect(requestTo(CHAPTER_API)).andRespond(withSuccess("""
            {"id":2530,"chapterid":1,"chaptername":"第1章 我有三个相宫",
             "txt":"第一段\\n第二段 <script>unexpected()</script> & 符号"}
            """, MediaType.APPLICATION_JSON));
        String html = adapter.getHtml(BOOK_URL + "1.html");
        assertTrue(html.contains("第一段<br/>第二段"));
        assertTrue(html.contains("&lt;script&gt;unexpected()&lt;/script&gt; &amp;"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("请收藏本站"));
        server.verify();
    }

    @Test
    void rejectsWrongChapterOrApiErrorsInsteadOfPersistingThem() throws Exception {
        server.expect(requestTo(CHAPTER_API)).andRespond(withSuccess("""
            {"id":2530,"chapterid":2,"chaptername":"错误章节","txt":"错误正文"}
            """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOK_API)).andRespond(withSuccess(
            "{\"error\":\"接口异常\"}", MediaType.APPLICATION_JSON));
        assertNull(adapter.getHtml(BOOK_URL + "1.html"));
        assertNull(adapter.getHtml(BOOK_URL));
        server.verify();
    }

    @Test
    void preservesInterruptionAndIgnoresUnrelatedOrMalformedHosts() {
        assertFalse(Bqg616SourceAdapter.supports("https://www.bqg616.cc.evil.test/#/book/2530/"));
        assertFalse(Bqg616SourceAdapter.supports("https://www.piaotia.com/bookinfo/16/16238.html"));
        assertFalse(Bqg616SourceAdapter.supports("https://www.bqg616.cc/bookimg/2/2530.jpg"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> adapter.getHtml(BOOK_URL));
        } finally {
            Thread.interrupted();
        }
    }
}
