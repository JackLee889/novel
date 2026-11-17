package com.java2nb.novel.core.crawl;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.utils.CrawlHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrawlCatalogPairingTest {
    private RuleBean rule() {
        var rule = new RuleBean();
        rule.setCharset("UTF-8");
        rule.setBookIndexUrl("https://www.biquge365.net/newbook/{bookId}/");
        rule.setIndexIdPatten("<li><a href=\"/chapter/\\d+/(\\d+)\\.html\"");
        rule.setIndexNamePatten("<li><a href=\"/chapter/\\d+/\\d+\\.html\"[^>]*>([^<]+)</a>");
        return rule;
    }

    private Object parsed(RuleBean rule, String html) {
        return ReflectionTestUtils.invokeMethod(new CrawlParser(null, null, null), "parseCatalog", html, rule, "42");
    }

    private List<?> chapters(Object result) { return (List<?>) ReflectionTestUtils.getField(result, "chapters"); }

    private String bad(String title) {
        return "<li><a href=\"/chapter/42/101.html\" title=\"" + title + "\"><!--名称：-->截短标题</a></li>";
    }

    @Test void sourceCommentAndMalformedQuoteRecoveryPreserveEveryIdAndItsOwnFullTitle() {
        String html = "<li><a href=\"/chapter/42/100.html\" title=\"别的标题\">第一章</a></li>"
            + bad("<!--名称：-->第二章 完整标题")
            + "<li><a href=\"/chapter/42/102.html\" title=\"第三章“><!--名称：-->第三章\">第三章“><!</a></li>";
        Object result = parsed(rule(), html);
        assertEquals(List.of("100", "101", "102"), chapters(result).stream().map(c -> ReflectionTestUtils.getField(c,"id")).toList());
        assertEquals(List.of("第一章", "第二章 完整标题", "第三章"), chapters(result).stream().map(c -> ReflectionTestUtils.getField(c,"name")).toList());
        assertEquals(3, ReflectionTestUtils.getField(result,"idMatches"));
        assertEquals(1, ReflectionTestUtils.getField(result,"titleMatches"));
        assertEquals(2, ReflectionTestUtils.getField(result,"recoveredTitles"));
    }

    @ParameterizedTest
    @ValueSource(strings={"https://other.invalid/newbook/{bookId}/", "http://www.biquge365.net/newbook/{bookId}/",
        "https://user@www.biquge365.net/newbook/{bookId}/", "https://www.biquge365.net/newbook/{bookId}/?extra=1",
        "https://www.biquge365.net/newbook/43/"})
    void sourceSpecificRecoveryNeverWeakensOtherRulesOrUnverifiedRoutes(String url) {
        var rule = rule(); rule.setBookIndexUrl(url);
        assertTrue(chapters(parsed(rule,bad("<!--名称：-->正确标题"))).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings={"", "<!--名称：-->", "<!--名称：--><b></b>", "没有已知来源标记"})
    void missingEmptyOrUnrecognizedTitlesRejectTheWholeCatalog(String title) {
        assertTrue(chapters(parsed(rule(),bad(title))).isEmpty());
    }

    @Test void recoveredTitleMustBelongToTheRequestedBookAndExactMatchedChapter() {
        assertTrue(chapters(parsed(rule(),bad("<!--名称：-->错误书的标题").replace("/chapter/42/", "/chapter/43/"))).isEmpty());
    }

    @Test void sourceBookIdsWithLeadingZeroesKeepTheirExactIdentity() {
        Object result=ReflectionTestUtils.invokeMethod(new CrawlParser(null,null,null),"parseCatalog",
            bad("<!--名称：-->正确标题").replace("/chapter/42/", "/chapter/080212/"),rule(),"080212");
        assertEquals(1,chapters(result).size());
        assertEquals("101",ReflectionTestUtils.getField(chapters(result).getFirst(),"id"));
        assertEquals("正确标题",ReflectionTestUtils.getField(chapters(result).getFirst(),"name"));
    }

    @Test void excessiveTitlesAndOverlongFallbackRemainInvalid() {
        var rule=rule();rule.setIndexNamePatten(">(标题[^<]+)</a>");
        String html="<li><a href=\"/chapter/42/100.html\">标题一</a></li><a>标题二</a>";
        assertTrue(chapters(parsed(rule,html)).isEmpty());
        assertTrue(chapters(parsed(rule(),bad("<!--名称：-->"+"长".repeat(101)))).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings={"第84章 关键秘密一一<知道>","第113章 你被<真实感>骗了", "第136章 车辆拼成的<巨人机械体>",
        "第139章 被她的<心电感应>操纵","第141章 <意识我>只是一种心念"})
    void literalAngleBracketTitlesAreRecoveredFromTheirOwnVerifiedAnchor(String title) {
        String html="<li><a href=\"/chapter/42/100.html\">前一章</a></li>"
            +"<li><a href=\"/chapter/42/101.html\" title=\""+title+"\">"+title+"</a></li>"
            +"<li><a href=\"/chapter/42/102.html\">后一章</a></li>";
        Object result=parsed(rule(),html);
        assertEquals(List.of("100","101","102"),chapters(result).stream().map(c->ReflectionTestUtils.getField(c,"id")).toList());
        assertEquals(List.of("前一章",title,"后一章"),chapters(result).stream().map(c->ReflectionTestUtils.getField(c,"name")).toList());
        assertEquals(1,ReflectionTestUtils.getField(result,"recoveredTitles"));
    }

    @Test void specialTitleRecoveryCannotBorrowAnotherLabelOrWrongBookOrAcceptMarkup() {
        assertTrue(chapters(parsed(rule(),"<li><a href=\"/chapter/42/101.html\" title=\"第1章 <知道>\">第2章</a></li>")).isEmpty());
        assertTrue(chapters(parsed(rule(),"<li><a href=\"/chapter/43/101.html\" title=\"第1章 <知道>\">第1章 <知道></a></li>")).isEmpty());
        assertTrue(chapters(parsed(rule(),"<li><a href=\"/chapter/42/101.html\" title=\"<script>私有脚本</script>\"><script>私有脚本</script></a></li>")).isEmpty());
    }

    @Test void failureReportsTheBookAndMatchCountsWithoutUrlCredentialsOrTokens() throws Exception {
        var http=mock(CrawlHttpClient.class);var store=mock(CrawlCollectionStore.class);
        var monitor=mock(CrawlRuntimeMonitor.class);var parser=new CrawlParser(http,null,store);
        ReflectionTestUtils.setField(parser,"runtimeMonitor",monitor);
        var rule=rule();rule.setBookIndexUrl("https://user:secret@www.biquge365.net/newbook/{bookId}/?token=secret");
        when(http.get(anyString(),anyString())).thenReturn("<li><a href=\"/chapter/42/100.html\">第一章</a></li>"+bad("<!--名称：-->第二章"));
        var book=new Book();book.setId(1L);book.setBookName("测试书");
        assertFalse(parser.parseBookIndexAndContent("42",book,rule,12,java.util.Map.of(),chapter->{},null));
        var message=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(monitor).failure(eq(12),message.capture());
        assertTrue(message.getValue().contains("[CATALOG_PAIR_MISMATCH]"));
        assertTrue(message.getValue().contains("书号=42"));
        assertTrue(message.getValue().contains("ID匹配=2；标题匹配=1"));
        assertFalse(message.getValue().contains("secret"));assertFalse(message.getValue().contains("token="));
        verify(store).catalogUnavailable(1L);
    }

    @Test void pagedCatalogFailureDoesNotRequireASinglePageUrlAndReportsTheFirstPage() throws Exception {
        var http=mock(CrawlHttpClient.class);var store=mock(CrawlCollectionStore.class);
        var monitor=mock(CrawlRuntimeMonitor.class);var parser=new CrawlParser(http,null,store);
        ReflectionTestUtils.setField(parser,"runtimeMonitor",monitor);
        var rule=rule();rule.setBookIndexUrl(null);rule.setBookIndexPageUrl("https://fixture.invalid/book/{bookId}/{page}/?token=secret");
        var book=new Book();book.setId(1L);book.setBookName("测试书");
        assertFalse(parser.parseBookIndexAndContent("42",book,rule,12,java.util.Map.of(),chapter->{},null));
        var error=org.mockito.ArgumentCaptor.forClass(com.java2nb.novel.utils.CrawlMetadataException.class);
        verify(monitor).failure(eq(12),error.capture());
        assertEquals(com.java2nb.novel.utils.CrawlMetadataException.Reason.CATALOG_UNAVAILABLE,error.getValue().getReason());
        assertTrue(error.getValue().getMessage().contains("https://fixture.invalid/book/42/1/"));
        assertFalse(error.getValue().getMessage().contains("secret"));
    }
}
