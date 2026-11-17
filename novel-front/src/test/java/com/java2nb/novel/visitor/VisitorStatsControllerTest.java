package com.java2nb.novel.visitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VisitorStatsControllerTest {
    static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";
    VisitorStatsService stats; VisitorStatsProperties properties; MockMvc mvc;
    String visitor = UUID.randomUUID().toString(), page = UUID.randomUUID().toString();
    @BeforeEach void setup() {
        stats = mock(VisitorStatsService.class); properties = new VisitorStatsProperties();
        var snapshot = new VisitorStatsService.Snapshot("1", "9007199254740993", "12", "2026-10-09T06:00:00Z", 90, true, true, true, "available");
        when(stats.snapshot()).thenReturn(snapshot); when(stats.visit(visitor, page)).thenReturn(snapshot); when(stats.heartbeat(visitor)).thenReturn(snapshot);
        mvc = browserMvc("");
    }
    private MockMvc browserMvc(String siteUrl) {
        return MockMvcBuilders.standaloneSetup(new VisitorStatsController(stats, properties, siteUrl))
            .defaultRequest(get("/").header("User-Agent", BROWSER)).build();
    }
    @Test void getOnlyReadsAndPreservesLargeCounterPrecision() throws Exception {
        mvc.perform(get("/site-stats")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.visitors").value("9007199254740993"))
            .andExpect(header().string("Cache-Control", "private, no-store"));
        verify(stats).snapshot(); verifyNoMoreInteractions(stats);
    }
    @Test void validOriginAndSameOriginRefererAreAccepted() throws Exception {
        mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", "http://localhost")
            .param("visitorId", visitor).param("pageViewId", page)).andExpect(status().isOk());
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Referer", "http://localhost/book/123.html")
            .param("visitorId", visitor)).andExpect(status().isOk());
        verify(stats).visit(visitor, page); verify(stats).heartbeat(visitor);
    }
    @Test void crossSiteMissingAndMalformedOriginsCannotMutateStatistics() throws Exception {
        for (String origin : new String[]{"https://attacker.example", "null", "http://user@localhost", "http://localhost?query=1", "http://localhost#fragment", "http://localhost:81"})
            mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", origin)
                .param("visitorId", visitor).param("pageViewId", page)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("visitorId", visitor)).andExpect(status().isForbidden());
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", "http://localhost")
            .header("Sec-Fetch-Site", "cross-site").param("visitorId", visitor)).andExpect(status().isForbidden());
        verifyNoInteractions(stats);
    }
    @Test void explicitHttpsPublicOriginWorksBehindAnHttpReverseProxyAndForwardedHeadersDoNotAuthorizeWrites() throws Exception {
        properties.setSiteUrl("https://novels.example/");
        mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", "https://novels.example")
            .param("visitorId", visitor).param("pageViewId", page)).andExpect(status().isOk());
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", "https://attacker.example")
            .header("X-Forwarded-Host", "attacker.example").header("X-Forwarded-Proto", "https").param("visitorId", visitor))
            .andExpect(status().isForbidden());
        verify(stats).visit(visitor, page); verifyNoMoreInteractions(stats);
    }
    @Test void seoOriginFallbackAndCustomPortsMatchExactly() throws Exception {
        mvc = browserMvc("https://novels.example:8443");
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", "https://novels.example:8443")
            .param("visitorId", visitor)).andExpect(status().isOk());
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", "https://novels.example")
            .param("visitorId", visitor)).andExpect(status().isForbidden());
    }
    @Test void wwwAliasRequiresExplicitConfigurationAndStillMatchesSchemeHostAndPort() throws Exception {
        properties.setSiteUrl("https://novels.example");
        mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Origin", "https://www.novels.example").param("visitorId", visitor).param("pageViewId", page))
            .andExpect(status().isForbidden());
        properties.setAllowedOrigins(List.of("https://www.novels.example"));
        mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Origin", "https://www.novels.example").param("visitorId", visitor).param("pageViewId", page))
            .andExpect(status().isOk());
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Referer", "https://www.novels.example/book/123.html").param("visitorId", visitor))
            .andExpect(status().isOk());
        for (String origin : List.of("https://unknown.example", "http://www.novels.example", "https://www.novels.example:8443"))
            mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", origin).param("visitorId", visitor)).andExpect(status().isForbidden());
        verify(stats).visit(visitor, page); verify(stats).heartbeat(visitor); verifyNoMoreInteractions(stats);
    }
    @Test void configuredAliasDoesNotBypassCrossSiteOrOriginRequirements() throws Exception {
        properties.setSiteUrl("https://novels.example");
        properties.setAllowedOrigins(List.of("https://www.novels.example"));
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Origin", "https://www.novels.example").header("Sec-Fetch-Site", "cross-site")
            .param("visitorId", visitor)).andExpect(status().isForbidden());
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Host", "www.novels.example").header("X-Forwarded-Host", "www.novels.example")
            .param("visitorId", visitor)).andExpect(status().isForbidden());
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Origin", "null").header("Referer", "https://www.novels.example/book/123.html")
            .param("visitorId", visitor)).andExpect(status().isForbidden());
        verifyNoInteractions(stats);
    }
    @Test void malformedOrPathContainingAliasEntriesCannotAuthorizeAnOrigin() throws Exception {
        properties.setSiteUrl("https://novels.example");
        for (String alias : List.of("", "null", "not a URI", "https://www.novels.example/", "https://www.novels.example/book",
            "https://user@www.novels.example", "https://www.novels.example?query=1", "https://www.novels.example#fragment",
            "ftp://www.novels.example", "https://www.novels.example:", "https://www.novels.example:0", "https://www.novels.example:65536")) {
            properties.setAllowedOrigins(List.of(alias));
            mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", "https://www.novels.example").param("visitorId", visitor).param("pageViewId", page))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(stats);
    }
    @Test void invalidAndMissingIdsHaveAConsistentBadRequestEnvelope() throws Exception {
        when(stats.visit("bad", page)).thenThrow(new IllegalArgumentException());
        mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", "http://localhost")
            .param("visitorId", "bad").param("pageViewId", page)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Origin", "http://localhost")
            .param("visitorId", visitor)).andExpect(status().isBadRequest());
    }

    static Stream<String> automatedAgents() {
        return Stream.of(null, "", "   ", "Googlebot/2.1 (+http://www.google.com/bot.html)",
            BROWSER + " (compatible; Bingbot/2.0)", "Baiduspider/2.0", "Sogou web spider/4.0",
            "Bytespider", "YandexBot/3.0", "DuckDuckBot/1.0", "AhrefsBot/7.0", "SemrushBot/7~bl",
            "GPTBot/1.2", "ChatGPT-User/1.0", "OAI-SearchBot/1.0", "Claude-User/1.0",
            "Perplexity-User/1.0", "facebookexternalhit/1.1", "meta-externalagent/1.1",
            "HeadlessChrome/142.0.0.0", "PhantomJS/2.1.1", "Playwright/1.56", "Puppeteer",
            "curl/8.16.0", "Wget/1.21", "python-requests/2.32.5", "python-httpx/0.28.1",
            "Go-http-client/2.0", "Java/21", "okhttp/4.12.0", "node-fetch/3.3.2", "axios/1.12.0",
            "CustomCrawler/1.0", "CustomBot", "Some spider", "Mozilla/5.0 " + " ".repeat(1100) + "Googlebot/2.1",
            "Mozilla/5.0 " + "x".repeat(4096));
    }

    @ParameterizedTest @MethodSource("automatedAgents")
    void automatedVisitsAndHeartbeatsOnlyReadTheSnapshot(String userAgent) throws Exception {
        var rawMvc = MockMvcBuilders.standaloneSetup(new VisitorStatsController(stats, properties, "")).build();
        for (String route : List.of("/site-stats/visit", "/site-stats/heartbeat")) {
            var request = post(route).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", "http://localhost").param("visitorId", visitor).param("pageViewId", page);
            if (userAgent != null) request.header("User-Agent", userAgent);
            rawMvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("X-Visitor-Stats-Excluded", "automated-client"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.visitors").value("9007199254740993"));
        }
        verify(stats, times(2)).snapshot(); verifyNoMoreInteractions(stats);
    }

    @ParameterizedTest @ValueSource(strings = {
        BROWSER,
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/142.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 11; CUBOT) AppleWebKit/537.36 Chrome/142.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; CUBOT Build/TP1A.220624.014) AppleWebKit/537.36 Chrome/142.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Version/4.0 Chrome/142.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.64"
    })
    void ordinaryDesktopMobileAndEmbeddedBrowsersStillCount(String userAgent) throws Exception {
        mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Origin", "http://localhost").header("User-Agent", userAgent)
            .param("visitorId", visitor).param("pageViewId", page))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("X-Visitor-Stats-Excluded"));
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Origin", "http://localhost").header("User-Agent", userAgent).param("visitorId", visitor))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("X-Visitor-Stats-Excluded"));
        verify(stats).visit(visitor, page); verify(stats).heartbeat(visitor); verifyNoMoreInteractions(stats);
    }

    @Test void anExcludedClientStillCannotBypassTheOriginPolicy() throws Exception {
        mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Origin", "https://attacker.example").header("User-Agent", "Googlebot/2.1")
            .param("visitorId", visitor).param("pageViewId", page)).andExpect(status().isForbidden());
        verifyNoInteractions(stats);
    }
}
