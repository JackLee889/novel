package com.java2nb.novel.core.crawl;

import com.java2nb.novel.controller.CrawlController;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.utils.CrawlMetadataException;
import com.java2nb.novel.utils.CrawlRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.java2nb.novel.utils.CrawlMetadataException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrawlBookMetadataTest {
    @Test void strictVerificationStillDistinguishesAPlaceholderFromAnActualAuthor() {
        var error=new com.java2nb.novel.utils.CrawlMetadataException(AUTHOR_UNVERIFIED,
            "https://user:secret@fixture/book/1?token=private","1","测试书");
        assertFalse(error.getMessage().contains("secret"));assertFalse(error.getMessage().contains("private"));
        assertThrows(com.java2nb.novel.utils.CrawlMetadataException.class,()->CrawlBookMetadata.verifyAuthor("https://fixture/book/1","1","测试书","佚名","未知作者"));
    }
    private static final String URL = "https://source.invalid/book/42/";
    private RuleBean rule() {
        RuleBean rule = new RuleBean();
        rule.setBookNamePatten("<h1>([^<]+)</h1>");
        rule.setAuthorNamePatten("<span class=\"author\">([^<]+)</span>");
        return rule;
    }

    @ParameterizedTest
    @ValueSource(strings = {"佚名", "未知作者", "作者未知", "unknown", "null", " &nbsp; ", "佚 名"})
    void unverifiedNewBooksContinueWithAnExplicitPendingAuthor(String value) {
        var detail = CrawlBookMetadata.read(rule(), "42", URL,
            "<h1>测试书</h1><span class=\"author\">" + value + "</span>");
        assertEquals("测试书", detail.name());
        assertEquals(CrawlBookMetadata.PENDING_AUTHOR, detail.author());
    }

    @Test void metadataFallbackSurvivesAChangedAuthorTagWithoutExtraNetworkRequests() {
        var detail = CrawlBookMetadata.read(rule(), "42", URL,
            "<h1>书名&amp;副标题</h1><div class=\"changed-author\">作者区域改版</div>"
                + "<meta property=\"og:novel:author\" content=\"作者：真实作者\">");
        assertEquals("书名&副标题", detail.name());
        assertEquals("真实作者", detail.author());
    }

    @Test void structuredAuthorIsUsedOnlyWhenThePrimaryFieldIsNotUsable() {
        var detail = CrawlBookMetadata.read(rule(), "42", URL,
            "<h1>测试书</h1><span class=\"author\">佚名</span><span itemprop=\"author\">真实作者</span>");
        assertEquals("真实作者", detail.author());
    }

    @Test void existingAuthorAndHistoricalAnonymousIdentityRemainUnchanged() {
        for (String original : new String[]{"原作者", "佚名"}) {
            try (var context = CrawlRequestContext.bound("42", "测试书", original)) {
                assertEquals(original, CrawlBookMetadata.read(rule(), "42", URL,
                    "<h1>测试书</h1><span class=\"author\">源站改写的作者</span>").author());
                assertEquals(original, CrawlBookMetadata.read(rule(), "42", URL,
                    "<h1>测试书</h1>").author());
                assertEquals(CrawlBookMetadata.PENDING_AUTHOR, CrawlBookMetadata.read(rule(), "43", URL,
                    "<h1>测试书</h1><span class=\"author\">佚名</span>").author());
                assertEquals(CrawlBookMetadata.PENDING_AUTHOR, CrawlBookMetadata.read(rule(), "42", URL,
                    "<h1>同站另一本书</h1><span class=\"author\">佚名</span>").author());
            }
        }
    }

    @Test void missingPageTitleAndAuthorHaveDifferentDiagnostics() {
        assertEquals(DETAIL_UNAVAILABLE, assertThrows(CrawlMetadataException.class,
            () -> CrawlBookMetadata.read(rule(), "42", URL, null)).getReason());
        assertEquals(TITLE_MISSING, assertThrows(CrawlMetadataException.class,
            () -> CrawlBookMetadata.read(rule(), "42", URL, "<h1> </h1>")).getReason());
        assertEquals(CrawlBookMetadata.PENDING_AUTHOR, CrawlBookMetadata.read(rule(), "42", URL, "<h1>测试书</h1>").author());
    }

    @Test void aPendingBoundAuthorIsRetriedDuringAnOrdinaryDetailCheck() {
        try (var context = CrawlRequestContext.bound("42", "测试书", CrawlBookMetadata.PENDING_AUTHOR)) {
            assertNull(CrawlRequestContext.boundAuthor("42", "测试书"));
            assertEquals("真实作者", CrawlBookMetadata.read(rule(), "42", URL,
                "<h1>测试书</h1><span class=\"author\">真实作者</span>").author());
            assertEquals(CrawlBookMetadata.PENDING_AUTHOR, CrawlBookMetadata.read(rule(), "42", URL,
                "<h1>测试书</h1><span class=\"author\">佚名</span>").author());
        }
    }

    @Test void controlledMetadataErrorsReachTheMonitorWithoutExposingCredentials() {
        var monitor = new CrawlRuntimeMonitor();
        var failure = new CrawlMetadataException(AUTHOR_LOOKUP_FAILED,
            "https://user:password@source.invalid/book/42/?token=secret#cookie=value", "42", "测试书");
        monitor.failure(14, new RuntimeException("unsafe-wrapper", failure));
        String message = monitor.snapshot(14).errors().getFirst().message();
        assertTrue(message.contains("AUTHOR_LOOKUP_FAILED"));
        assertTrue(message.contains("测试书"));
        assertTrue(message.contains(URL));
        for (String secret : new String[]{"password", "token", "secret", "cookie", "unsafe-wrapper"})
            assertFalse(message.contains(secret));
        monitor.failure(14, new IllegalStateException("password=secret"));
        assertFalse(monitor.snapshot(14).errors().getFirst().message().contains("secret"));
    }

    @Test void ruleTestingReturnsTheSameSpecificMetadataFailureWithoutWritingItsCache() throws Exception {
        var http = mock(CrawlHttpClient.class);
        var cache = mock(CacheService.class);
        var controller = new CrawlController(null, cache, null, http);
        when(http.fetchPage(URL, "UTF-8")).thenThrow(new CrawlMetadataException(AUTHOR_LOOKUP_FAILED, URL, "42", "测试书"));
        var result = controller.testParse("(.*)", URL, "1", "UTF-8");
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertTrue(json.contains("AUTHOR_LOOKUP_FAILED"));
        assertTrue(json.contains("测试书"));
        verifyNoInteractions(cache);
    }
}
