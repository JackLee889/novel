package com.java2nb.novel.crawl;

import com.java2nb.novel.controller.CrawlController;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.utils.CrawlAccessChallengeException;
import com.java2nb.novel.service.CrawlService;
import com.java2nb.novel.service.BookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrawlRuleCharsetTest {
    private static final String URL = "https://www.piaotia.com/bookinfo/16/16238.html";
    private static final String RULE = "<h1>([^<]+)</h1>";
    private CrawlController controller;
    private Map<String, String> cachedPages;
    private CrawlHttpClient http;

    @BeforeEach
    void setUp() {
        cachedPages = new HashMap<>();
        CacheService cache = mock(CacheService.class);
        when(cache.get(anyString())).thenAnswer(invocation -> cachedPages.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            cachedPages.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(cache).set(anyString(), anyString(), eq(600L));
        http = mock(CrawlHttpClient.class);
        controller = new CrawlController(mock(CrawlService.class), cache, mock(BookService.class), http);
    }

    @Test
    void sameUrlWithDifferentCharsetsDoesNotReuseGarbledPage() throws Exception {
        when(http.fetchPage(URL, "UTF-8"))
            .thenReturn("<h1>\uFFFD\uFFFD\uFFFD\uFFFD</h1>");
        when(http.fetchPage(URL, "GBK"))
            .thenReturn("<h1>凡尘飞仙</h1>");
        controller.testParse(RULE, URL, "0", "utf-8");
        Map<?, ?> gbk = (Map<?, ?>) controller.testParse(RULE, URL, "0", "gbk").getData();
        assertEquals("凡尘飞仙", gbk.get("匹配结果"));
        assertEquals(false, gbk.get("命中缓存"));
        assertEquals(2, cachedPages.size());
        verify(http).fetchPage(URL, "GBK");
    }

    @Test
    void equivalentCharsetNamesShareTheCorrectCachedPage() throws Exception {
        when(http.fetchPage(URL, "GBK"))
            .thenReturn("<h1>凡尘飞仙</h1>");
        controller.testParse(RULE, URL, "0", "gbk");
        Map<?, ?> repeat = (Map<?, ?>) controller.testParse(RULE, URL, "0", " GBK ").getData();
        assertEquals("凡尘飞仙", repeat.get("匹配结果"));
        assertEquals(true, repeat.get("命中缓存"));
        assertEquals("GBK", repeat.get("使用编码"));
        assertEquals(1, cachedPages.size());
        verify(http, times(1)).fetchPage(URL, "GBK");
    }

    @Test
    void challengeIsReportedAndNeverCachedAsBookHtml() throws Exception {
        when(http.fetchPage(URL, "GBK")).thenThrow(new CrawlAccessChallengeException(300));
        Map<?, ?> result = (Map<?, ?>) controller.testParse(RULE, URL, "1", "GBK").getData();
        assertEquals(false, result.get("成功"));
        assertEquals(300L, result.get("重试等待秒数"));
        assertTrue(result.get("说明").toString().contains("验证"));
        assertTrue(cachedPages.isEmpty());
    }
}
