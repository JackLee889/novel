package com.java2nb.novel.utils;

import com.java2nb.novel.core.crawl.Bqg616SourceAdapter;
import com.java2nb.novel.core.crawl.HetushuSourceAdapter;
import com.java2nb.novel.core.crawl.UukanshuSourceAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Random;

/**
 * @author Administrator
 */
@Slf4j
@Component
public class CrawlHttpClient {

    @Autowired
    private ImpersonatingHttpClient pageClient;

    @Value("${crawl.interval.min}")
    private Integer intervalMin;

    @Value("${crawl.interval.max}")
    private Integer intervalMax;

    private final Random random = new Random();
    private final UukanshuSourceAdapter uukanshu = new UukanshuSourceAdapter();
    private final ThreadLocal<CrawlFetchFailure> lastFailure = new ThreadLocal<>();

    public CrawlFetchFailure lastFailure() { return lastFailure.get(); }

    /**
     * 单次请求的最大重试间隔（毫秒）
     */
    private static final long MAX_RETRY_BACKOFF_MS = 5000L;

    public String get(String url, String charset) throws InterruptedException {
        lastFailure.remove();
        if (pageClient != null) pageClient.clearLastFailure();
        String cached=CrawlRequestContext.cached(url,charset);
        if(cached!=null) return cached;
        // [审查11] 原来是 intervalMax > intervalMin，min == max（固定间隔）时反而不等待；
        // 这里改成闭区间判断，并校验取值合法。
        if (Objects.nonNull(intervalMin) && Objects.nonNull(intervalMax)
            && intervalMin >= 0 && intervalMax >= intervalMin) {
            Thread.sleep(random.nextInt(intervalMax - intervalMin + 1) + intervalMin);
        }

        // [审查11] 重试次数原来放在 ThreadLocal 里且只在彻底失败时清理，会跨页面累积：
        // 同线程连续几个 URL 各失败一次后，后续 URL 首次失败就被放弃。
        // 现在改为绑定单次请求的局部计数，方法返回即释放，不再有状态残留。
        int attempt = 0;
        while (true) {
            final String body;
            try {
                body = fetchPage(url, charset);
            } catch (CrawlAccessChallengeException e) {
                if(CrawlRequestContext.yieldOnChallenge()) throw e;
                // 留在当前书籍/章节等待源站冷却；不能抛给分类循环后跳过这本书。
                // Thread.sleep 可被关闭采集打断，规则测试 fetchPage 仍即时返回限制状态。
                log.warn("采集暂停，{} 秒后重试当前页面", e.getRetryAfterSeconds());
                waitForChallenge(e.getRetryAfterSeconds());
                continue;
            }
            // 动态书源在适配器中校验 JSON 和 ID；详情、分类转换后可能小于旧的 HTML 长度阈值。
            if (Objects.nonNull(body) && (Bqg616SourceAdapter.supports(url) || HetushuSourceAdapter.supports(url)
                || UukanshuSourceAdapter.supports(url)
                || body.length() >= Constants.INVALID_HTML_LENGTH)) {
                CrawlRequestContext.remember(url,charset,body);
                return body;
            }
            CrawlFetchFailure failure = pageClient == null ? null : pageClient.lastFailure();
            // A missing page needs a later recheck, not several immediate identical requests.
            if (failure != null && failure.missing()) {
                lastFailure.set(failure);
                return null;
            }
            if (attempt >= Constants.HTTP_FAIL_RETRY_COUNT) {
                lastFailure.set(failure == null
                    ? new CrawlFetchFailure(CrawlFetchFailure.Reason.INVALID_PAGE, 0, url, 0, false) : failure);
                log.warn("抓取失败，已重试 {} 次仍无有效内容：{}", attempt, url);
                return null;
            }
            attempt++;
            log.warn("抓取结果异常，第 {}/{} 次重试：{}", attempt, Constants.HTTP_FAIL_RETRY_COUNT, url);
            // [审查11] 失败退避，避免紧密重试打爆站点
            Thread.sleep(Math.min(1000L * attempt, MAX_RETRY_BACKOFF_MS));
        }
    }

    protected void waitForChallenge(long seconds) throws InterruptedException {
        Thread.sleep(Math.multiplyExact(seconds, 1000L));
    }

    /** 规则测试和正式采集使用相同的动态页面适配。 */
    public String fetchPage(String url, String charset) throws InterruptedException {
        if (UukanshuSourceAdapter.supports(url)) {
            return uukanshu.getHtml(url, endpoint -> pageClient.get(endpoint, "UTF-8"));
        }
        if (HetushuSourceAdapter.supports(url)) {
            return HetushuSourceAdapter.getHtml(url, new HetushuSourceAdapter.PageReader() {
                @Override public String getPage(String endpoint) throws InterruptedException {
                    return pageClient.get(endpoint, "UTF-8");
                }
                @Override public String getOrderToken(String endpoint, String referer) throws InterruptedException {
                    return pageClient.getAjaxToken(endpoint, referer);
                }
            });
        }
        return Bqg616SourceAdapter.supports(url)
            ? Bqg616SourceAdapter.get(url) : pageClient.get(url, charset);
    }

}
