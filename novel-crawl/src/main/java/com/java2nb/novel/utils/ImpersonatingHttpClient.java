package com.java2nb.novel.utils;

import com.github.zhkl0228.impersonator.ImpersonatorApi;
import com.github.zhkl0228.impersonator.ImpersonatorFactory;
import com.java2nb.novel.core.config.CrawlHttpProperties;
import com.java2nb.novel.core.config.HttpProxyProperties;
import com.java2nb.novel.core.utils.HttpUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClientFactory;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 复用连接池和 Chrome 指纹，按源站隔离会话及验证冷却。 */
@Slf4j
@Component
public class ImpersonatingHttpClient {
    private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CHALLENGE_TITLE = Pattern.compile(
        "^(?:验证码|captcha)(?:\\s*[-|_:：].*)?$|^(?:安全验证|人机验证|访问验证|just a moment|verify (?:that )?you are human|security (?:check|verification))|(?:请输入|请填写|请完成).{0,8}验证码",
        Pattern.CASE_INSENSITIVE);
    private static final int MAX_COOLDOWN_ORIGINS = 128;
    private final boolean enabled;
    private final OkHttpClient client;
    private final long cooldownMillis;
    private final Clock clock;
    private final Map<String, Integer> requestIntervals;
    private final Map<String, OriginRequestGate> requestGates = new ConcurrentHashMap<>();
    private final Map<String, Long> blockedUntil = new LinkedHashMap<>();
    private static final int MAX_MISSING_PAGES = 2048;
    private final Map<String, MissingPage> missingPages = new LinkedHashMap<>(32, .75f, true);
    private final ThreadLocal<CrawlFetchFailure> lastFailure = new ThreadLocal<>();
    private record MissingPage(int status, int failures, long until) { }

    public CrawlFetchFailure lastFailure() { return lastFailure.get(); }
    void clearLastFailure() { lastFailure.remove(); }

    @Autowired
    public ImpersonatingHttpClient(HttpProxyProperties proxy, CrawlHttpProperties properties) {
        this(properties.isImpersonatorEnabled(), properties.isImpersonatorEnabled()
            ? createClient(proxy, properties) : null, properties.getChallengeCooldownSeconds(), Clock.systemUTC(),
            properties.getRequestIntervalMsByHost());
    }

    ImpersonatingHttpClient(boolean enabled, OkHttpClient client, int cooldownSeconds, Clock clock) {
        this(enabled, client, cooldownSeconds, clock, Map.of());
    }

    ImpersonatingHttpClient(boolean enabled, OkHttpClient client, int cooldownSeconds, Clock clock,
        Map<String, Integer> requestIntervals) {
        if (cooldownSeconds < 1 || cooldownSeconds > 3600) {
            throw new IllegalArgumentException("crawl.http.challenge-cooldown-seconds 必须为 1 到 3600");
        }
        this.enabled = enabled;
        this.cooldownMillis = cooldownSeconds * 1000L;
        this.clock = clock;
        if (requestIntervals == null || requestIntervals.values().stream()
            .anyMatch(interval -> interval == null || interval < 0 || interval > 60000)) {
            throw new IllegalArgumentException("crawl.http.request-interval-ms-by-host 必须为 0 到 60000");
        }
        this.requestIntervals = Map.copyOf(requestIntervals);
        // 重定向的每一次 HTTP 请求也检查目标源站的冷却，避免从另一个域名绕过限流。
        this.client = client == null ? null : client.newBuilder().addNetworkInterceptor(chain -> {
            checkCooldown(OriginCookieJar.origin(chain.request().url()));
            return chain.proceed(chain.request());
        }).build();
    }

    private static OkHttpClient createClient(HttpProxyProperties proxy, CrawlHttpProperties properties) {
        if (properties.getConnectTimeoutMs() < 1 || properties.getReadTimeoutMs() < 1
            || properties.getCallTimeoutMs() < 1) {
            throw new IllegalArgumentException("crawl.http 超时时间必须大于 0");
        }
        ImpersonatorApi profile = ImpersonatorFactory.macChrome();
        // 遵循项目 DNS/代理配置；避免库默认另外访问固定 DNS-over-HTTPS 服务。
        profile.setEchConfigProvider(null);
        OkHttpClient.Builder builder = OkHttpClientFactory.create(profile)
            .setConnectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
            .setReadTimeout(properties.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
            .setCallTimeout(properties.getCallTimeoutMs(), TimeUnit.MILLISECONDS)
            .newHttpClient().newBuilder()
            .cookieJar(new OriginCookieJar())
            .retryOnConnectionFailure(false)
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8").build()));
        if (Boolean.TRUE.equals(proxy.getEnabled())) {
            if (proxy.getIp() == null || proxy.getIp().isBlank() || proxy.getPort() == null
                || proxy.getPort() < 1 || proxy.getPort() > 65535) {
                throw new IllegalArgumentException("已启用的 http.proxy 必须配置有效地址和端口");
            }
            builder.proxy(new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(proxy.getIp(), proxy.getPort())));
            if (proxy.getUsername() != null && !proxy.getUsername().isBlank()
                && proxy.getPassword() != null && !proxy.getPassword().isBlank()) {
                builder.proxyAuthenticator((route, response) -> {
                    if (response.request().header("Proxy-Authorization") != null) {
                        return null;
                    }
                    return response.request().newBuilder().header("Proxy-Authorization",
                        Credentials.basic(proxy.getUsername(), proxy.getPassword())).build();
                });
            }
        } else {
            builder.proxy(Proxy.NO_PROXY);
        }
        return builder.build();
    }

    public String get(String url, String charset) throws InterruptedException {
        return get(url, charset, Map.of(), null);
    }

    /** 和图书的公开排序接口以 HTTP 204 + Token 响应头返回数据。 */
    public String getAjaxToken(String url, String referer) throws InterruptedException {
        return get(url, "UTF-8", Map.of("Referer", referer,
            "X-Requested-With", "XMLHttpRequest", "Content-Type", "application/x-www-form-urlencoded"), "Token");
    }

    private String get(String url, String charset, Map<String, String> headers, String noContentHeader)
        throws InterruptedException {
        lastFailure.remove();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        if (!enabled) {
            if (noContentHeader != null) {
                log.warn("和图书接口读取需要启用 crawl.http.impersonator-enabled");
                return null;
            }
            return HttpUtil.getByHttpClientWithChrome(url, charset);
        }
        final HttpUrl target;
        final Charset encoding;
        try {
            target = HttpUrl.get(url);
            encoding = Charset.forName(charset);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("页面请求地址或编码无效");
            return null;
        }
        String origin = OriginCookieJar.origin(target);
        checkCooldown(origin);
        if (deferMissing(target)) return null;
        int intervalMillis = requestIntervals.getOrDefault(target.host(), 0);
        OriginRequestGate gate = intervalMillis > 0
            ? requestGates.computeIfAbsent(origin, ignored -> new OriginRequestGate(intervalMillis)) : null;
        if (gate != null) {
            gate.enter();
        }
        try {
            // 排队期间其他分类可能触发冷却，取得源站锁后必须重新检查。
            checkCooldown(origin);
            return execute(target, encoding, origin, headers, noContentHeader);
        } finally {
            if (gate != null) {
                gate.leave();
            }
        }
    }

    private String execute(HttpUrl target, Charset encoding, String origin,
        Map<String, String> headers, String noContentHeader) throws InterruptedException {
        Request.Builder request = new Request.Builder().url(target).get();
        headers.forEach(request::header);
        try (Response response = client.newCall(request.build()).execute()) {
            String responseOrigin = OriginCookieJar.origin(response.request().url());
            if (response.code() == 429 || "challenge".equalsIgnoreCase(response.header("cf-mitigated"))) {
                log.warn("源站限制响应：{}{}，HTTP {}", response.request().url().host(),
                    response.request().url().encodedPath(), response.code());
                throw block(origin, responseOrigin, retryAfterMillis(response.header("Retry-After")));
            }
            if (response.code() == 204 && noContentHeader != null) {
                String value = response.header(noContentHeader);
                return value == null || value.isBlank() ? null : value;
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                lastFailure.set(new CrawlFetchFailure(CrawlFetchFailure.Reason.INVALID_PAGE, response.code(), target.toString(), 0, false));
                return null;
            }
            // 以书源规则编码解码，不能让错误的 Content-Type charset 覆盖 GBK 等规则。
            String body = new String(responseBody.bytes(), encoding);
            if (isChallengePage(body)) {
                throw block(origin, responseOrigin, cooldownMillis);
            }
            if (response.code() != 200) {
                if (response.code() == 404 || response.code() == 410) recordMissing(target, response.code());
                else lastFailure.set(new CrawlFetchFailure(CrawlFetchFailure.Reason.HTTP, response.code(), target.toString(), 0, false));
                log.warn("源站页面请求失败：{}，HTTP {}", target.host(), response.code());
                return null;
            }
            synchronized (missingPages) { missingPages.remove(target.toString()); }
            return body;
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            log.warn("源站页面请求超时：{}", target.host());
            lastFailure.set(new CrawlFetchFailure(CrawlFetchFailure.Reason.TIMEOUT, 0, target.toString(), 0, false));
            return null;
        } catch (IOException e) {
            log.warn("源站页面连接失败：{}，{}", target.host(), e.getClass().getSimpleName());
            lastFailure.set(new CrawlFetchFailure(CrawlFetchFailure.Reason.CONNECTION, 0, target.toString(), 0, false));
            return null;
        }
    }

    private boolean deferMissing(HttpUrl target) {
        synchronized (missingPages) {
            MissingPage page = missingPages.get(target.toString());
            if (page == null || page.until <= clock.millis()) return false;
            lastFailure.set(new CrawlFetchFailure(CrawlFetchFailure.Reason.HTTP, page.status, target.toString(),
                Math.max(1, (page.until - clock.millis() + 999) / 1000), true));
            return true;
        }
    }

    private void recordMissing(HttpUrl target, int status) {
        synchronized (missingPages) {
            MissingPage previous = missingPages.get(target.toString());
            int failures = previous == null ? 1 : Math.min(previous.failures + 1, 6);
            long seconds = status == 410 ? 21600 : Math.min(21600, 900L << (failures - 1));
            missingPages.put(target.toString(), new MissingPage(status, failures, clock.millis() + seconds * 1000));
            while (missingPages.size() > MAX_MISSING_PAGES) missingPages.remove(missingPages.keySet().iterator().next());
            lastFailure.set(new CrawlFetchFailure(CrawlFetchFailure.Reason.HTTP, status, target.toString(), seconds, false));
        }
    }

    private synchronized void checkCooldown(String origin) {
        long now = clock.millis();
        blockedUntil.values().removeIf(until -> until <= now);
        Long until = blockedUntil.get(origin);
        if (until != null) {
            throw new CrawlAccessChallengeException(Math.max(1L, (until - now + 999L) / 1000L));
        }
    }

    private synchronized CrawlAccessChallengeException block(String origin, String responseOrigin, long delay) {
        long waitMillis = Math.max(cooldownMillis, delay);
        long until = clock.millis() + waitMillis;
        blockedUntil.merge(origin, until, Math::max);
        blockedUntil.merge(responseOrigin, until, Math::max);
        while (blockedUntil.size() > MAX_COOLDOWN_ORIGINS) {
            blockedUntil.remove(blockedUntil.keySet().iterator().next());
        }
        log.warn("源站触发验证或限流：{}，冷却 {} 秒", origin, (waitMillis + 999L) / 1000L);
        return new CrawlAccessChallengeException((waitMillis + 999L) / 1000L);
    }

    private long retryAfterMillis(String value) {
        if (value == null) {
            return cooldownMillis;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return Math.min(3600L, Math.max(0L, seconds)) * 1000L;
        } catch (NumberFormatException e) {
            try {
                long until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return Math.min(3_600_000L, Math.max(0L, until - clock.millis()));
            } catch (RuntimeException ignored) {
                return cooldownMillis;
            }
        }
    }

    static boolean isChallengePage(String body) {
        // 只检查页面标题和专用验证标记，正文提到“验证码”不应误判。
        String beginning = body.substring(0, Math.min(body.length(), 65536));
        Matcher title = TITLE.matcher(beginning);
        if (title.find() && CHALLENGE_TITLE.matcher(title.group(1).strip()).find()) {
            return true;
        }
        String lower = beginning.toLowerCase(Locale.ROOT);
        return lower.contains("/cdn-cgi/challenge-platform/")
            && (lower.contains("id=\"challenge-form\"") || lower.contains("id='challenge-form'"));
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
