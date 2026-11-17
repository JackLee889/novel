package com.java2nb.novel.utils;

import com.java2nb.novel.core.config.CrawlHttpProperties;
import com.java2nb.novel.core.config.HttpProxyProperties;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/** 手工运行的单次只读探测：不启动 Spring、不连接数据库、不输出正文/Cookie/代理凭据。 */
public final class ImpersonatorReadOnlyProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--fixture".equals(args[0])) {
            fixture();
            return;
        }
        if (args.length != 1) {
            throw new IllegalArgumentException("传入一个公开页面 URL");
        }
        var properties = new CrawlHttpProperties();
        properties.setConnectTimeoutMs(5000);
        properties.setCallTimeoutMs(20000);
        var proxy = new HttpProxyProperties();
        String host = System.getenv("CRAWL_PROBE_PROXY_HOST");
        String port = System.getenv("CRAWL_PROBE_PROXY_PORT");
        if (host != null && port != null) {
            proxy.setEnabled(true);
            proxy.setIp(host);
            proxy.setPort(Integer.valueOf(port));
        }
        var json = new ObjectMapper();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", URI.create(args[0]).getHost());
        result.put("clientCodeSource", ImpersonatingHttpClient.class.getProtectionDomain().getCodeSource()
            .getLocation().toString());
        result.put("viaProxy", Boolean.TRUE.equals(proxy.getEnabled()));
        long started = System.nanoTime();
        var client = new ImpersonatingHttpClient(proxy, properties);
        try {
            String body = client.get(args[0], "UTF-8");
            result.put("pageReceived", body != null);
            if (body != null) {
                result.put("decodedUtf8Bytes", body.getBytes(StandardCharsets.UTF_8).length);
                if (body.stripLeading().startsWith("{")) {
                    var data = json.readTree(body);
                    result.put("httpVersion", data.path("http_version").asText());
                    result.put("userAgent", data.path("user_agent").asText());
                    result.put("ja3Hash", data.path("tls").path("ja3_hash").asText());
                    result.put("ja4", data.path("tls").path("ja4").asText());
                } else {
                    var title = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                        .matcher(body);
                    result.put("title", title.find() ? title.group(1).strip() : "");
                    result.put("bookLinks", Pattern.compile("/index/\\d+/").matcher(body).results().count());
                }
            }
        } catch (CrawlAccessChallengeException e) {
            result.put("pageReceived", false);
            result.put("challengeDetected", true);
            result.put("retryAfterSeconds", e.getRetryAfterSeconds());
        } finally {
            client.close();
            result.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000L);
        }
        System.out.println("PROBE_JSON=" + json.writeValueAsString(result));
    }

    private static void fixture() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var observedHeaders = new AtomicReference<com.sun.net.httpserver.Headers>();
        var challengeRequests = new AtomicInteger();
        byte[] page;
        try (var bytes = new ByteArrayOutputStream()) {
            try (var gzip = new GZIPOutputStream(bytes)) {
                gzip.write("<h1>正文第一段</h1>".getBytes(Charset.forName("GBK")));
            }
            page = bytes.toByteArray();
        }
        server.createContext("/session", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=fixture; Path=/");
            exchange.getResponseHeaders().add("Location", "/book");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/book", exchange -> {
            observedHeaders.set(exchange.getRequestHeaders());
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, page.length);
            try (var output = exchange.getResponseBody()) { output.write(page); }
        });
        server.createContext("/challenge", exchange -> {
            challengeRequests.incrementAndGet();
            exchange.getResponseHeaders().add("cf-mitigated", "challenge");
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        var client = new ImpersonatingHttpClient(new HttpProxyProperties(), new CrawlHttpProperties());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fixture", "standalone-no-spring-no-database");
        result.put("clientCodeSource", ImpersonatingHttpClient.class.getProtectionDomain().getCodeSource()
            .getLocation().toString());
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            result.put("gzipGbk", "<h1>正文第一段</h1>".equals(client.get(base + "/session", "GBK")));
            var headers = observedHeaders.get();
            result.put("chromeHeaders", headers.getFirst("User-Agent").contains("Chrome/")
                && headers.getFirst("Sec-Ch-Ua") != null);
            result.put("cookiePreserved", "session=fixture".equals(headers.getFirst("Cookie")));
            boolean challenge = false;
            try { client.get(base + "/challenge", "UTF-8"); }
            catch (CrawlAccessChallengeException e) { challenge = e.getRetryAfterSeconds() == 300; }
            boolean cooldown = false;
            try { client.get(base + "/challenge", "UTF-8"); }
            catch (CrawlAccessChallengeException e) { cooldown = challengeRequests.get() == 1; }
            result.put("challengeRejected", challenge);
            result.put("cooldownSendsNoRequest", cooldown);
            if (result.values().contains(Boolean.FALSE)) {
                throw new IllegalStateException("fixture failed");
            }
        } finally {
            client.close();
            server.stop(0);
        }
        System.out.println("PROBE_JSON=" + new ObjectMapper().writeValueAsString(result));
    }
}
