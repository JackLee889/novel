package com.java2nb.novel.utils;

import com.java2nb.novel.core.config.CrawlHttpProperties;
import com.java2nb.novel.core.config.HttpProxyProperties;
import com.java2nb.novel.core.utils.HttpUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/** 验证 Spring 配置绑定、客户端注入及回退开关，不启动数据库或任务线程。 */
class CrawlHttpClientWiringTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CrawlHttpProperties.class, HttpProxyProperties.class})
    @Import({ImpersonatingHttpClient.class, CrawlHttpClient.class})
    static class Config {}

    private AnnotationConfigApplicationContext context(Map<String, Object> settings) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", settings));
        context.register(Config.class);
        context.refresh();
        return context;
    }

    @Test
    void defaultModeRoutesProductionFetchThroughImpersonator() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String page = "<title>正常小说</title>" + "正文".repeat(800);
        server.createContext("/", exchange -> {
            assertTrue(exchange.getRequestHeaders().getFirst("User-Agent").contains("Chrome/"));
            byte[] body = page.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (var context = context(Map.of("crawl.interval.min", 0, "crawl.interval.max", 0))) {
            assertTrue(context.getBean(CrawlHttpProperties.class).isImpersonatorEnabled());
            assertEquals(page, context.getBean(CrawlHttpClient.class)
                .get("http://127.0.0.1:" + server.getAddress().getPort() + "/", "UTF-8"));
        } finally { server.stop(0); }
    }

    @Test
    void configuredRollbackFlagRoutesBothFetchEntrypointsToLegacyClient() throws Exception {
        try (var context = context(Map.of("crawl.http.impersonator-enabled", "false",
                "crawl.interval.min", 0, "crawl.interval.max", 0));
             var legacy = mockStatic(HttpUtil.class)) {
            String url = "https://fixture.invalid/book";
            String page = "正文".repeat(800);
            legacy.when(() -> HttpUtil.getByHttpClientWithChrome(url, "GBK")).thenReturn(page);
            assertFalse(context.getBean(CrawlHttpProperties.class).isImpersonatorEnabled());
            CrawlHttpClient client = context.getBean(CrawlHttpClient.class);
            assertEquals(page, client.fetchPage(url, "GBK"));
            assertEquals(page, client.get(url, "GBK"));
        }
    }
}
