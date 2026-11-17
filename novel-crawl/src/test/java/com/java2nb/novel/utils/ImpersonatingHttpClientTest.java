package com.java2nb.novel.utils;

import com.java2nb.novel.core.config.CrawlHttpProperties;
import com.java2nb.novel.core.config.HttpProxyProperties;
import com.java2nb.novel.core.utils.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 通过本地 HTTP 源站验证真实请求、编码、压缩、Cookie、代理和验证冷却，无数据库依赖。 */
class ImpersonatingHttpClientTest {
    private HttpServer server;
    private ImpersonatingHttpClient client;
    private String base;
    private java.util.concurrent.ExecutorService serverExecutor;

    @Test
    void readsPublicAjaxOrderFromNoContentHeaderWithoutChangingOrdinaryPageRequests() throws Exception {
        String referer = base + "/book/4/2230.html";
        server.createContext("/order", exchange -> {
            if (exchange.getRequestHeaders().getFirst("X-Requested-With") != null) {
                assertEquals("XMLHttpRequest", exchange.getRequestHeaders().getFirst("X-Requested-With"));
                assertEquals(referer, exchange.getRequestHeaders().getFirst("Referer"));
            }
            exchange.getResponseHeaders().set("Token", "MEE=" );
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        assertEquals("MEE=", client.getAjaxToken(base + "/order", referer));
        assertNull(client.get(base + "/order", "UTF-8"));
    }

    @Test
    void noContentWithoutTokenAndOrderRateLimitDoNotProduceData() throws Exception {
        server.createContext("/no-token", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/order-limited", exchange -> {
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        assertNull(client.getAjaxToken(base + "/no-token", base + "/book"));
        assertThrows(CrawlAccessChallengeException.class,
            () -> client.getAjaxToken(base + "/order-limited", base + "/book"));
    }

    @Test
    void categoryThreadsShareOneOriginIntervalAndDoNotOverlap() throws Exception {
        List<Long> starts = new CopyOnWriteArrayList<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maximumInFlight = new AtomicInteger();
        server.createContext("/paced", exchange -> {
            starts.add(System.nanoTime());
            maximumInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(40);
                send(exchange, 200, "正常页面".getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { inFlight.decrementAndGet(); }
        });
        client.close();
        var properties = new CrawlHttpProperties();
        properties.setRequestIntervalMsByHost(Map.of("127.0.0.1", 120));
        client = new ImpersonatingHttpClient(new HttpProxyProperties(), properties);
        var workers = Executors.newFixedThreadPool(7);
        var ready = new CountDownLatch(1);
        try {
            var calls = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 7; i++) calls.add(workers.submit(() -> {
                ready.await();
                return client.get(base + "/paced", "UTF-8");
            }));
            ready.countDown();
            for (var call : calls) assertEquals("正常页面", call.get(5, TimeUnit.SECONDS));
            assertEquals(7, starts.size());
            assertEquals(1, maximumInFlight.get());
            for (int i = 1; i < starts.size(); i++) {
                assertTrue(starts.get(i) - starts.get(i - 1) >= TimeUnit.MILLISECONDS.toNanos(90),
                    "category requests were not paced across threads");
            }
        } finally { workers.shutdownNow(); }
    }

    @Test
    void queuedCategoryChecksCooldownAfterTakingOriginPermit() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var firstArrived = new CountDownLatch(1);
        var reply = new CountDownLatch(1);
        server.createContext("/queued", exchange -> {
            requests.incrementAndGet(); firstArrived.countDown();
            try { reply.await(2, TimeUnit.SECONDS); send(exchange, 429, new byte[]{'x'}); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        client.close();
        var properties = new CrawlHttpProperties();
        properties.setRequestIntervalMsByHost(Map.of("127.0.0.1", 20));
        client = new ImpersonatingHttpClient(new HttpProxyProperties(), properties);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> assertThrows(CrawlAccessChallengeException.class,
                () -> client.get(base + "/queued", "UTF-8")));
            assertTrue(firstArrived.await(2, TimeUnit.SECONDS));
            var second = workers.submit(() -> assertThrows(CrawlAccessChallengeException.class,
                () -> client.get(base + "/queued", "UTF-8")));
            reply.countDown();
            first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS);
            assertEquals(1, requests.get());
        } finally { reply.countDown(); workers.shutdownNow(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new ImpersonatingHttpClient(new HttpProxyProperties(), new CrawlHttpProperties());
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop(0);
        serverExecutor.shutdownNow();
        Thread.interrupted();
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws Exception {
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void html(String path, String body) {
        server.createContext(path, exchange -> {
            try {
                send(exchange, 200, body.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void sendsConsistentChromeHeadersAndChineseLanguage() throws Exception {
        List<com.sun.net.httpserver.Headers> requests = new CopyOnWriteArrayList<>();
        server.createContext("/headers", exchange -> {
            requests.add(exchange.getRequestHeaders());
            try { send(exchange, 200, "正常页面".getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals("正常页面", client.get(base + "/headers", "UTF-8"));
        var headers = requests.getFirst();
        String ua = headers.getFirst("User-Agent");
        assertTrue(ua.contains("Chrome/"));
        assertFalse(ua.contains("Chrome/87."));
        String major = ua.split("Chrome/")[1].split("\\.")[0];
        assertTrue(headers.getFirst("Sec-Ch-Ua").contains("v=\"" + major + "\""));
        assertEquals("navigate", headers.getFirst("Sec-Fetch-Mode"));
        assertEquals("document", headers.getFirst("Sec-Fetch-Dest"));
        assertTrue(headers.getFirst("Accept-Language").startsWith("zh-CN"));
    }

    @Test
    void followsRedirectAndPreservesItsCookieAndRuleCharset() throws Exception {
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=fixture; Path=/; HttpOnly");
            exchange.getResponseHeaders().add("Location", "/book");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/book", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            try { send(exchange, 200, ("session=fixture".equals(cookie) ? "凡尘飞仙" : "会话丢失")
                .getBytes(Charset.forName("GBK"))); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals("凡尘飞仙", client.get(base + "/redirect", "GBK"));
        assertEquals("凡尘飞仙", client.get(base + "/book", "GBK"));
    }

    @Test
    void decompressesGzipBeforeDecodingGbk() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write("正文第一段".getBytes(Charset.forName("GBK")));
        }
        server.createContext("/gzip", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            try { send(exchange, 200, output.toByteArray()); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals("正文第一段", client.get(base + "/gzip", "GBK"));
    }

    @Test
    void cookieDoesNotLeakToAnotherOriginAndDeletionAndPathAreHonored() throws Exception {
        List<String> cookies = new CopyOnWriteArrayList<>();
        server.createContext("/set", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=fixture; Path=/only");
            try { send(exchange, 200, new byte[]{'x'}); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        server.createContext("/only", exchange -> {
            cookies.add(String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
            exchange.getResponseHeaders().add("Set-Cookie", "session=deleted; Max-Age=0; Path=/only");
            try { send(exchange, 200, new byte[]{'x'}); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        server.createContext("/other", exchange -> {
            cookies.add(String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
            try { send(exchange, 200, new byte[]{'x'}); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        client.get(base + "/set", "UTF-8");
        client.get(base + "/other", "UTF-8");
        client.get(base.replace("127.0.0.1", "localhost") + "/only", "UTF-8");
        client.get(base + "/only", "UTF-8");
        client.get(base + "/only", "UTF-8");
        assertEquals(List.of("null", "null", "session=fixture", "null"), cookies);
    }

    @Test
    void captchaStopsImmediateRetriesAndAllPathsOnTheOrigin() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/captcha", exchange -> {
            requests.incrementAndGet();
            try { send(exchange, 200, "<title>安全验证 - 香书小说</title><form>验证码</form>"
                .getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        CrawlHttpClient crawler = new CrawlHttpClient();
        ReflectionTestUtils.setField(crawler, "pageClient", client);
        var first = assertThrows(CrawlAccessChallengeException.class,
            () -> crawler.fetchPage(base + "/captcha", "UTF-8"));
        assertEquals(300L, first.getRetryAfterSeconds());
        assertThrows(CrawlAccessChallengeException.class, () -> client.get(base + "/captcha", "UTF-8"));
        assertThrows(CrawlAccessChallengeException.class, () -> client.get(base + "/another-chapter", "UTF-8"));
        assertEquals(1, requests.get());
        assertFalse(ImpersonatingHttpClient.isChallengePage("<title>正常小说</title><p>他说请输入验证码。</p>"));
        assertFalse(ImpersonatingHttpClient.isChallengePage("<title>验证码修仙记 - 香书小说</title>"));
    }

    @Test
    void rateLimitRespectsRetryAfterAndCooldownExpires() throws Exception {
        var properties = new CrawlHttpProperties();
        properties.setChallengeCooldownSeconds(1);
        client.close();
        client = new ImpersonatingHttpClient(new HttpProxyProperties(), properties);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/rate", exchange -> {
            int count = requests.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "600");
            try { send(exchange, count == 1 ? 429 : 200, "正常页面".getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        var error = assertThrows(CrawlAccessChallengeException.class, () -> client.get(base + "/rate", "UTF-8"));
        assertEquals(600L, error.getRetryAfterSeconds());
        ReflectionTestUtils.setField(client, "clock", Clock.fixed(Instant.now().plusSeconds(601), ZoneOffset.UTC));
        assertEquals("正常页面", client.get(base + "/rate", "UTF-8"));
        assertEquals(2, requests.get());
    }

    @Test
    void challengeCooldownDoesNotBlockOtherOrigins() throws Exception {
        html("/captcha", "<title>Just a moment...</title>");
        html("/normal", "正常页面");
        assertThrows(CrawlAccessChallengeException.class, () -> client.get(base + "/captcha", "UTF-8"));
        assertEquals("正常页面", client.get(base.replace("127.0.0.1", "localhost") + "/normal", "UTF-8"));
    }

    @Test
    void redirectsCannotRequestAnOriginAlreadyInCooldown() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/captcha", exchange -> {
            requests.incrementAndGet();
            try { send(exchange, 200, "<title>安全验证</title>".getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/captcha");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        assertThrows(CrawlAccessChallengeException.class, () -> client.get(base + "/captcha", "UTF-8"));
        assertThrows(CrawlAccessChallengeException.class,
            () -> client.get(base.replace("127.0.0.1", "localhost") + "/redirect", "UTF-8"));
        assertEquals(1, requests.get());
    }

    @Test
    void cloudflareHeaderIsRejectedEvenWhenBodyHasNoRecognizableTitle() throws Exception {
        server.createContext("/challenge", exchange -> {
            exchange.getResponseHeaders().add("cf-mitigated", "challenge");
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        assertThrows(CrawlAccessChallengeException.class, () -> client.get(base + "/challenge", "UTF-8"));
    }

    @Test
    void concurrentRequestsShareCookiesWithoutCorruption() throws Exception {
        server.createContext("/session", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=shared; Path=/");
            try { send(exchange, 200, "会话已建立".getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        server.createContext("/normal", exchange -> {
            String value = "session=shared".equals(exchange.getRequestHeaders().getFirst("Cookie"))
                ? "正常页面" : "会话丢失";
            try { send(exchange, 200, value.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals("会话已建立", client.get(base + "/session", "UTF-8"));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var jobs = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> executor.submit(() -> client.get(base + "/normal", "UTF-8"))).toList();
            for (var job : jobs) {
                assertEquals("正常页面", job.get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void configuredProxySupportsAuthentication() throws Exception {
        List<String> proxyAuth = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
            proxyAuth.add(String.valueOf(authorization));
            try {
                if (authorization == null) {
                    exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=fixture");
                    send(exchange, 407, new byte[]{'x'});
                } else {
                    send(exchange, 200, "代理页面".getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        var proxy = new HttpProxyProperties();
        proxy.setEnabled(true);
        proxy.setIp("127.0.0.1");
        proxy.setPort(server.getAddress().getPort());
        proxy.setUsername("fixture");
        proxy.setPassword("fixture-only");
        var proxied = new ImpersonatingHttpClient(proxy, new CrawlHttpProperties());
        try { assertEquals("代理页面", proxied.get("http://unresolvable.invalid/book", "UTF-8")); }
        finally { proxied.close(); }
        assertEquals(2, proxyAuth.size());
        assertEquals("null", proxyAuth.getFirst());
        assertTrue(proxyAuth.get(1).startsWith("Basic "));
    }

    @Test
    void disabledModeUsesLegacyClientAndInterruptedCallSendsNothing() throws Exception {
        var properties = new CrawlHttpProperties();
        properties.setImpersonatorEnabled(false);
        var legacy = new ImpersonatingHttpClient(new HttpProxyProperties(), properties);
        try (var mocked = mockStatic(HttpUtil.class)) {
            mocked.when(() -> HttpUtil.getByHttpClientWithChrome(base, "GBK")).thenReturn("旧客户端");
            assertEquals("旧客户端", legacy.get(base, "GBK"));
        } finally { legacy.close(); }
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> client.get(base + "/normal", "UTF-8"));
    }

    @Test
    void tlsClientHelloContainsBrowserGreaseAndHttp2Alpn() throws Exception {
        try (var listener = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(5000);
            var hello = executor.submit(() -> {
                try (var socket = listener.accept(); var input = new DataInputStream(socket.getInputStream())) {
                    socket.setSoTimeout(5000);
                    assertEquals(22, input.readUnsignedByte());
                    input.readUnsignedShort();
                    byte[] message = input.readNBytes(input.readUnsignedShort());
                    listener.close();
                    return message;
                }
            });
            assertNull(client.get("https://localhost:" + listener.getLocalPort() + "/", "UTF-8"));
            ByteBuffer buffer = ByteBuffer.wrap(hello.get(10, TimeUnit.SECONDS));
            assertEquals(1, buffer.get());
            buffer.position(4 + 2 + 32);
            int sessionLength = Byte.toUnsignedInt(buffer.get());
            buffer.position(buffer.position() + sessionLength);
            int cipherBytes = Short.toUnsignedInt(buffer.getShort());
            List<Integer> ciphers = new ArrayList<>();
            for (int i = 0; i < cipherBytes; i += 2) {
                ciphers.add(Short.toUnsignedInt(buffer.getShort()));
            }
            assertTrue(ciphers.stream().anyMatch(value -> (value & 0x0f0f) == 0x0a0a));
            assertTrue(ciphers.containsAll(List.of(0x1301, 0x1302, 0x1303)));
            int compressionBytes = Byte.toUnsignedInt(buffer.get());
            buffer.position(buffer.position() + compressionBytes);
            int extensionsEnd = buffer.position() + 2 + Short.toUnsignedInt(buffer.getShort());
            String alpn = "";
            while (buffer.position() < extensionsEnd) {
                int type = Short.toUnsignedInt(buffer.getShort());
                int length = Short.toUnsignedInt(buffer.getShort());
                byte[] data = new byte[length];
                buffer.get(data);
                if (type == 16) {
                    alpn = new String(data, StandardCharsets.ISO_8859_1);
                }
            }
            assertTrue(alpn.contains("h2"));
            assertTrue(alpn.contains("http/1.1"));
        }
    }
}
