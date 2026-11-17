package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.CrawlBookHandler;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.core.crawl.UukanshuSourceAdapter;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookCategory;
import com.java2nb.novel.entity.CrawlSingleTask;
import com.java2nb.novel.mapper.CrawlSingleTaskMapper;
import com.java2nb.novel.mapper.CrawlSourceMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import com.java2nb.novel.utils.CrawlAccessChallengeException;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.utils.CrawlMetadataException;
import com.java2nb.novel.utils.ImpersonatingHttpClient;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用人工 HTML 验证源站协议与正式解析入口，不请求外网或数据库。 */
class UukanshuSourceAdapterTest {
    private static final String LIST = "https://uukanshu.cc/class_1_1.html";
    private static final String BOOK = "https://uukanshu.cc/book/42/";
    private static final String CHAPTER = BOOK + "100.html";

    private RuleBean rule() throws Exception {
        return new ObjectMapper().readValue(new ClassPathResource("static/crawl-templates/uukanshu.json")
            .getInputStream(), RuleBean.class);
    }

    private static String listing(String stats, String author) {
        return """
            <a href="/book/999/">站外推荐区</a>
            <div class="bookbox"><h4 class="bookname"><a href="/book/42/">测试书</a></h4>
            <div class="author">作者：<a href="/author/test.html">%s</a></div>
            <div class="author">字数：10000</div><div class="cat"><a href="/book/42/101.html">最新章节</a></div>
            <a href="/book/42/">阅读</a></div><em id="pagestats">%s</em>
            """.formatted(author, stats);
    }

    private static String detail(String author, String status) {
        return """
            <meta property="og:book_id" content="42"><meta property="og:novel:author" content="%s">
            <meta property="og:image" content="//image.uukanshu.cc/0/42/42s.jpg">
            <meta property="og:novel:status" content="%s">
            <meta property="og:novel:update_time" content="2026-10-11 12:00:00">
            <ol class="breadcrumb"><li><a href="/class_1_1.html">玄幻奇幻</a></li></ol>
            <h1 class="booktitle">测试书</h1>
            <p class="bookintro"><img src="/nocover.jpg">正文简介<br>第二段<script>坏广告()</script></p>
            <a class="bookchapter" href="/book/42/101.html">最新章节</a>
            <div id="list-chapterAll"><h2>全部章节</h2><dd><a href="/book/42/100.html">第一章</a></dd>
            <dd><a href="https://uukanshu.cc/book/42/101.html">第二章</a></dd>
            <dd><a href="https://other.invalid/book/42/999.html">站外链接</a></dd></div>
            <div><a href="/book/999/1.html">底部推荐</a></div>
            """.formatted(author, status);
    }

    private static String chapter(String text, String next, String label) {
        return "<h1>第一章</h1><div class=\"readcotent bbb font-normal\">" + text
            + "<script>坏广告()</script><iframe src=\"https://ads.invalid\">坏广告</iframe>"
            + "<ins class=\"pubadx-slot\">坏广告</ins></div><div class=\"mulu-box\">"
            + "<a href=\"" + next + "\">" + label + "</a></div>";
    }

    private static List<String> matches(String regex, String html) {
        return Pattern.compile(regex).matcher(html).results().map(match -> match.group(1)).toList();
    }

    @Test
    void handlesOnlyItsOwnCanonicalRoutes() {
        for (String url : List.of(LIST, "https://uukanshu.cc/class_10_2.html", BOOK, CHAPTER, BOOK + "100_2.html"))
            assertTrue(UukanshuSourceAdapter.supports(url), url);
        for (String url : List.of("https://hetushu.com/book/42/index.html", "https://uukanshu.cc.evil.invalid/book/42/",
            "https://user@uukanshu.cc/book/42/", "https://uukanshu.cc:444/book/42/", "https://uukanshu.cc/class_11_1.html",
            "https://uukanshu.cc/book/0/", "http://uukanshu.cc/book/42/", "not a url"))
            assertFalse(UukanshuSourceAdapter.supports(url), url);
        assertFalse(UukanshuSourceAdapter.supports(null));
    }

    @Test
    void templateScansTheWholeLibraryOnceIncludingUnclassifiedBooksAndDefaultsToAll() throws Exception {
        RuleBean rule = rule();
        for (int local = 1; local <= 7; local++) assertTrue(rule.resolveCategoryRules(local).isEmpty());
        assertEquals(List.of("0"), rule.resolveCategoryRules(8));
        assertEquals("https://uukanshu.cc/class_0_1.html", rule.getBookListUrl()
            .replace("{catId}", rule.resolveCategoryRules(8).getFirst()).replace("{page}", "1"));
        assertNull(rule.getCatIdRule());
        assertEquals(RuleBean.NewBookStatus.ALL, rule.getNewBookStatus());
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,2", "3,3", "4,4", "5,5", "6,6", "7,5", "8,7", "9,8", "10,8", "0,8", "11,8"})
    void allTenSourceCategoriesMapFromDetailWithUnclassifiedFallback(int source, int local) throws Exception {
        String html = new UukanshuSourceAdapter().getHtml(BOOK,
            url -> detail("真实作者", "全本").replace("class_1_1.html", "class_" + source + "_1.html"));
        assertEquals(local, UukanshuSourceAdapter.categoryId(html));
    }

    @Test
    void listingExcludesLatestChapterAndDuplicateReadLinksAndKeepsExactPagination() throws Exception {
        String html = new UukanshuSourceAdapter().getHtml(LIST, url -> listing("1/902", "真实作者"));
        assertEquals(List.of("42"), matches(rule().getBookIdPatten(), html));
        assertEquals(List.of("1"), matches(rule().getPagePatten(), html));
        assertEquals(List.of("902"), matches(rule().getTotalPagePatten(), html));
        String last = new UukanshuSourceAdapter().getHtml("https://uukanshu.cc/class_1_902.html",
            url -> listing("902/902", "作者"));
        assertEquals(List.of("902"), matches(rule().getPagePatten(), last));
    }

    @Test
    void rejectsErrorPagesMissingPaginationAndOutOfRangePages() throws Exception {
        var adapter = new UukanshuSourceAdapter();
        for (String html : List.of("<title>错误页</title>", listing("2/1", "作者"),
            listing("1/9021junk", "作者"), listing("0/1", "作者")))
            assertNull(adapter.getHtml(LIST, url -> html));
        assertNull(adapter.getHtml(LIST, url -> null));
    }

    @ParameterizedTest
    @CsvSource({"全本,1", "連載,0", "未知,-1"})
    void metadataRepairsAnonymousAuthorAndMapsOnlyExplicitStatus(String status, int expected) throws Exception {
        var adapter = new UukanshuSourceAdapter();
        adapter.getHtml(LIST, url -> listing("1/1", "真实作者"));
        String html = adapter.getHtml(BOOK, url -> {
            assertEquals(BOOK, url); // 已读列表时不额外请求搜索或分类。
            return detail("佚名", status);
        });
        var parsed = new ArrayList<Book>();
        new CrawlParser(new CrawlHttpClient() {
            @Override public String get(String url, String charset) { return html; }
        }, null, CrawlTestStores.healthy()).parseBook(rule(), "42", parsed::add);
        Book book = parsed.getFirst();
        assertEquals("测试书", book.getBookName());
        assertEquals("真实作者", book.getAuthorName());
        assertEquals(1, book.getCatId());
        assertEquals("https://image.uukanshu.cc/0/42/42s.jpg", book.getPicUrl());
        assertEquals("正文简介第二段", Jsoup.parse(book.getBookDesc()).text().replace(" ", ""));
        assertNotNull(book.getLastIndexUpdateTime());
        assertEquals(expected < 0 ? null : (byte) expected, book.getBookStatus());
        assertEquals(List.of("100", "101"), matches(rule().getIndexIdPatten(), html));
        assertEquals(List.of("第一章", "第二章"), matches(rule().getIndexNamePatten(), html));
        assertFalse(html.contains("坏广告"));
        assertFalse(html.contains("nocover"));
    }

    @Test
    void directBookRequestsResolveAuthorFromItsCategoryWithoutSearching() throws Exception {
        List<String> requested = new ArrayList<>();
        String html = new UukanshuSourceAdapter().getHtml(BOOK, url -> {
            requested.add(url);
            return url.equals(BOOK) ? detail("佚名", "連載") : listing("1/1", "分类作者");
        });
        assertEquals(List.of(BOOK, LIST), requested);
        assertEquals(List.of("分类作者"), matches(rule().getAuthorNamePatten(), html));
    }

    @Test
    void authorSearchUsesExactBookIdAndNameAndFollowsOnlySameSiteNextPages() throws Exception {
        List<String> requested = new ArrayList<>();
        String search = "https://uukanshu.cc/search?searchkey=%E6%B5%8B%E8%AF%95&searchtype=all";
        String next = "https://uukanshu.cc/search/test_2.html";
        String html = new UukanshuSourceAdapter().getHtml(BOOK, url -> {
            requested.add(url);
            if (url.equals(BOOK)) return detail("佚名", "全本");
            if (url.equals(LIST)) return listing("1/1", "错误作者").replace("/book/42/", "/book/43/");
            if (url.equals(search)) return "<div id=\"pagelink\"><a href=\"" + next + "\">&gt;</a></div>";
            if (url.equals(next)) return listing("2/2", "搜索作者");
            throw new AssertionError(url);
        });
        assertEquals(List.of(BOOK, LIST, search, next), requested);
        assertEquals(List.of("搜索作者"), matches(rule().getAuthorNamePatten(), html));
    }

    @Test
    void neverGuessesAnonymousAuthorOrRequestsAnExternalSearchLink() throws Exception {
        List<String> requested = new ArrayList<>();
        String html = new UukanshuSourceAdapter().getHtml(BOOK, url -> {
            requested.add(url);
            if (url.equals(BOOK)) return detail("佚名", "連載");
            return "<div id=\"pagelink\"><a href=\"https://evil.invalid/search/next\">&gt;</a></div>";
        });
        assertEquals(List.of("作者待核验"), matches(rule().getAuthorNamePatten(), html));
        assertEquals(4, requested.size());
        assertTrue(requested.stream().allMatch(url -> url.startsWith("https://uukanshu.cc/")));
    }

    @Test
    void catalogRejectsWrongBookIdsEmptyCatalogAndDuplicateChapters() throws Exception {
        var adapter = new UukanshuSourceAdapter();
        for (String page : List.of(detail("真实作者", "全本").replace("content=\"42\"", "content=\"43\""),
            detail("真实作者", "全本").replace("/101.html", "/100.html"),
            detail("真实作者", "全本").replace("list-chapterAll", "missing")))
            assertThrows(CrawlMetadataException.class, () -> adapter.getHtml(BOOK, url -> page));
    }

    @Test
    void alternateTitleFragmentRecoversTheRealAuthorAfterSearchRedirectsToTheAnonymousDetail() throws Exception {
        String name = "亂武從拜入武館開始";
        String page = detail("佚名", "連載").replace("测试书", name);
        String first = "https://uukanshu.cc/search?searchkey=%E4%BA%82%E6%AD%A6&searchtype=all";
        String second = "https://uukanshu.cc/search?searchkey=%E5%BE%9E%E6%8B%9C&searchtype=all";
        List<String> requested = new ArrayList<>();
        String html = new UukanshuSourceAdapter().getHtml(BOOK, url -> {
            requested.add(url);
            if (url.equals(BOOK) || url.equals(first)) return page;
            if (url.equals(LIST)) return listing("1/1", "错误作者").replace("/book/42/", "/book/43/");
            if (url.equals(second)) return listing("1/1", "景全").replace("测试书", "亂武<span class=\"hot\">從拜</span>入武館開始");
            throw new AssertionError(url);
        });
        assertEquals(List.of(BOOK, LIST, first, second), requested);
        assertEquals(List.of("景全"), matches(rule().getAuthorNamePatten(), html));
    }

    @Test
    void twoCharacterTitleUsesRemainingLookupBudgetToVerifyItsAuthorOnCategoryPageThree() throws Exception {
        String page = detail("佚名", "連載").replace("测试书", "天淵");
        String search = "https://uukanshu.cc/search?searchkey=%E5%A4%A9%E6%B7%B5&searchtype=all";
        String second = "https://uukanshu.cc/class_1_2.html";
        String third = "https://uukanshu.cc/class_1_3.html";
        List<String> requested = new ArrayList<>();
        String html = new UukanshuSourceAdapter().getHtml(BOOK, url -> {
            requested.add(url);
            if (url.equals(BOOK) || url.equals(search)) return page;
            if (url.equals(LIST)) return listing("1/125", "其他作者").replace("/book/42/", "/book/43/")
                + "<div id=\"pagelink\"><a href=\"" + second + "\">&gt;</a></div>";
            if (url.equals(second)) return listing("2/125", "其他作者").replace("测试书", "同号的错误书名")
                + "<div id=\"pagelink\"><a href=\"" + third + "\">下一頁</a></div>";
            if (url.equals(third)) return listing("3/125", "沐瀟三生").replace("测试书", "天淵");
            throw new AssertionError(url);
        });
        assertEquals(List.of(BOOK, LIST, search, second, third), requested);
        assertEquals(List.of("沐瀟三生"), matches(rule().getAuthorNamePatten(), html));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.invalid/class_1_2.html", "http://uukanshu.cc/class_1_2.html",
        "/class_2_2.html", "/class_1_3.html", "/class_1_1.html", "/class_1_2.html?extra=1",
        "/class_1_2.html#extra", "https://user@uukanshu.cc/class_1_2.html"})
    void categoryFallbackRejectsExternalCrossCategorySkippedRepeatedAndNoncanonicalNextPages(String next) throws Exception {
        String page = detail("佚名", "連載").replace("测试书", "天淵");
        List<String> requested = new ArrayList<>();
        String html = new UukanshuSourceAdapter().getHtml(BOOK, url -> {
            requested.add(url);
            if (url.equals(BOOK) || url.contains("/search?")) return page;
            assertEquals(LIST, url);
            return listing("1/125", "其他作者").replace("/book/42/", "/book/43/")
                + "<div id=\"pagelink\"><a href=\"" + next + "\">&gt;</a></div>";
        });
        assertEquals(List.of("作者待核验"), matches(rule().getAuthorNamePatten(), html));
        assertEquals(3, requested.size());
    }

    @ParameterizedTest
    @CsvSource({"42,另一本书", "43,天淵"})
    void categoryFallbackStillRequiresBothTheExactSourceBookIdAndName(String id, String name) throws Exception {
        String page = detail("佚名", "連載").replace("测试书", "天淵");
        List<String> requested = new ArrayList<>();
        String html = new UukanshuSourceAdapter().getHtml(BOOK, url -> {
            requested.add(url);
            if (url.equals(BOOK) || url.contains("/search?")) return page;
            if (url.equals(LIST)) return listing("1/125", "其他作者").replace("/book/42/", "/book/43/")
                + "<div id=\"pagelink\"><a href=\"/class_1_2.html\">&gt;</a></div>";
            assertEquals("https://uukanshu.cc/class_1_2.html", url);
            return listing("2/125", "错误作者").replace("测试书", name).replace("/book/42/", "/book/" + id + "/");
        });
        assertEquals(List.of("作者待核验"), matches(rule().getAuthorNamePatten(), html));
        assertEquals(4, requested.size());
    }

    @Test
    void fallbackNeverAcceptsTheAuthorOfAnotherBookAndKeepsTheGlobalLookupBudget() throws Exception {
        String name = "甲乙丙丁戊己庚辛";
        List<String> requested = new ArrayList<>();
        String html = new UukanshuSourceAdapter().getHtml(BOOK, url -> {
            requested.add(url);
            if (url.equals(BOOK)) return detail("佚名", "連載").replace("测试书", name);
            return listing("1/125", "错误作者").replace("测试书", "另一本书")
                + (url.equals(LIST) ? "<div id=\"pagelink\"><a href=\"/class_1_2.html\">&gt;</a></div>" : "");
        });
        assertEquals(List.of("作者待核验"), matches(rule().getAuthorNamePatten(), html));
        assertEquals(5, requested.size(), "one detail, one category, at most three search pages");
        assertFalse(requested.contains("https://uukanshu.cc/class_1_2.html"), "search and category pages share a single budget");
        assertTrue(requested.stream().allMatch(url -> url.startsWith("https://uukanshu.cc/")));
    }

    @Test
    void stripsExecutableAdsAndKeepsVisibleFormattingAndAbsoluteIllustrations() throws Exception {
        String html = new UukanshuSourceAdapter().getHtml(CHAPTER, url -> chapter(
            "段落<b>加粗</b><br><img src=\"/picture.jpg\"><span onclick=\"bad()\">下一段</span>",
            "/book/42/101.html", "下一章"));
        assertEquals("段落加粗 下一段", Jsoup.parse(html).text());
        assertTrue(html.contains("<b>加粗</b>"));
        assertTrue(html.contains("https://uukanshu.cc/picture.jpg"));
        for (String noise : List.of("坏广告", "script", "iframe", "onclick", "next-page"))
            assertFalse(html.contains(noise), noise);
        assertNull(new UukanshuSourceAdapter().getHtml(CHAPTER, url -> "<h1>只有标题</h1>"));
    }

    @Test
    void stopAndChallengePropagateFromTheMetadataLookup() {
        var restriction = new CrawlAccessChallengeException(10);
        assertSame(restriction, assertThrows(CrawlAccessChallengeException.class,
            () -> new UukanshuSourceAdapter().getHtml(BOOK, url -> {
                if (url.equals(BOOK)) return detail("佚名", "連載");
                throw restriction;
            })));
        assertThrows(InterruptedException.class, () -> new UukanshuSourceAdapter().getHtml(BOOK, url -> {
            if (url.equals(BOOK)) return detail("佚名", "連載");
            throw new InterruptedException();
        }));
    }

    @Test
    void productionEntrypointAcceptsShortPagesAndJoinsOnlyTheSameChaptersPages() throws Exception {
        var transport = mock(ImpersonatingHttpClient.class);
        List<String> requested = new ArrayList<>();
        when(transport.get(anyString(), eq("UTF-8"))).thenAnswer(call -> {
            String url = call.getArgument(0);
            requested.add(url);
            if (url.equals(LIST)) return listing("1/1", "真实作者");
            if (url.equals(BOOK)) return detail("佚名", "全本");
            if (url.equals(CHAPTER)) return chapter("第一章第一页。", "/book/42/100_2.html", "下一頁");
            if (url.equals(BOOK + "100_2.html")) return chapter("第一章第二页。", "/book/42/101.html", "下一頁");
            if (url.equals(BOOK + "101.html")) return chapter("第二章正文。", "/book/42/", "目录");
            throw new AssertionError(url);
        });
        CrawlHttpClient client = new CrawlHttpClient();
        ReflectionTestUtils.setField(client, "pageClient", transport);
        String html = client.get(LIST, "UTF-8");
        assertTrue(html.length() < 1000);
        assertEquals(List.of("42"), matches(rule().getBookIdPatten(), html));
        var parser = new CrawlParser(client, null, CrawlTestStores.healthy());
        var books = new ArrayList<Book>();
        parser.parseBook(rule(), "42", books::add);
        Book book = books.getFirst();
        book.setId(1000L);
        var content = new ArrayList<BookContent>();
        assertTrue(parser.parseBookIndexAndContent("42", book, rule(), null, Map.of(),
            batch -> content.addAll(batch.getBookContentList()), null));
        assertEquals(2, content.size());
        assertEquals("第一章第一页。第一章第二页。", content.getFirst().getContent());
        assertEquals("第二章正文。", content.get(1).getContent());
        assertEquals(1, requested.stream().filter(url -> url.equals(BOOK + "101.html")).count());
        assertFalse(content.toString().contains("坏广告"));
        verify(transport, never()).getAjaxToken(anyString(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"false,true,4", "true,true,8", "false,false,8"})
    void onlyUukanshuBatchNewBooksUseDetailCategoryAndDictionaryDirection(boolean manual, boolean uu, int expected)
        throws Exception {
        RuleBean rule = rule();
        if (!uu) rule.setBookDetailUrl("https://other.invalid/book/{bookId}");
        CrawlParser parser = mock(CrawlParser.class);
        BookService books = mock(BookService.class);
        Book parsed = new Book();
        parsed.setBookName("测试书"); parsed.setAuthorName("真实作者"); parsed.setCatId(4);
        doAnswer(call -> { ((CrawlBookHandler) call.getArgument(2)).handle(parsed); return null; })
            .when(parser).parseBook(any(), anyString(), any());
        BookCategory category = new BookCategory();
        category.setId(expected); category.setName("字典分类"); category.setWorkDirection((byte) 1);
        when(books.queryBookCategoryByCatId(expected)).thenReturn(category);
        when(parser.parseBookIndexAndContent(anyString(), any(), any(), anyInt(), anyMap(), any(), any())).thenReturn(true);
        var service = new CrawlServiceImpl(parser, mock(CrawlSourceMapper.class), mock(CrawlSingleTaskMapper.class),
            books, mock(CrawlHttpClient.class));
        CrawlSingleTask task = manual ? new CrawlSingleTask() : null;
        assertTrue(service.parseBookAndSave(8, rule, 20, "42", task));
        assertEquals(expected, parsed.getCatId());
        assertEquals("字典分类", parsed.getCatName());
        assertEquals((byte) 1, parsed.getWorkDirection());
        verify(books).queryBookCategoryByCatId(expected);
    }

    @Test
    void globalDiscoveryKeepsExistingBooksInTheirOriginalCategory() throws Exception {
        RuleBean rule = rule();
        rule.setNewBookStatus(RuleBean.NewBookStatus.SERIALIZING);
        CrawlParser parser = mock(CrawlParser.class);
        BookService books = mock(BookService.class);
        Book parsed = new Book();
        parsed.setBookName("已有书"); parsed.setAuthorName("作者"); parsed.setCatId(4); parsed.setBookStatus((byte) 1);
        Book existing = new Book();
        existing.setId(123L); existing.setCatId(2);
        when(books.queryBookByBookNameAndAuthorName("已有书", "作者")).thenReturn(existing);
        doAnswer(call -> { ((CrawlBookHandler) call.getArgument(2)).handle(parsed); return null; })
            .when(parser).parseBook(any(), anyString(), any());
        var service = new CrawlServiceImpl(parser, mock(CrawlSourceMapper.class), mock(CrawlSingleTaskMapper.class),
            books, mock(CrawlHttpClient.class));
        assertTrue(service.parseBookAndSave(8, rule, 20, "42", null));
        assertEquals(2, existing.getCatId());
        verify(books, never()).queryBookCategoryByCatId(anyInt());
        verify(books).updateCrawlProperties(123L, 20, "42");
        verify(parser, never()).parseBookIndexAndContent(anyString(), any(), any(), anyInt(), anyMap(), any(), any());
    }
}
