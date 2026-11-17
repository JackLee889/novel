package com.java2nb.novel.utils;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CrawlHttpClientCooldownTest {
    @Test
    void cooldownRetriesSameChapterWithoutReturningToCategoryLoop() throws Exception {
        List<String> urls = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        String page = "<article>正文</article>" + " ".repeat(1500);
        CrawlHttpClient client = new CrawlHttpClient() {
            @Override public String fetchPage(String url, String charset) {
                urls.add(url);
                int count = calls.incrementAndGet();
                if (count <= 2) throw new CrawlAccessChallengeException(count == 1 ? 300 : 2);
                return page;
            }
            @Override protected void waitForChallenge(long seconds) {
                waits.add(seconds);
            }
        };
        assertEquals(page, client.get("https://fixture/chapter/2", "GBK"));
        assertEquals(List.of(300L, 2L), waits);
        assertEquals(List.of("https://fixture/chapter/2", "https://fixture/chapter/2",
            "https://fixture/chapter/2"), urls);
    }

    @Test
    void stoppingDuringCooldownDoesNotSendAnotherRequest() {
        AtomicInteger requests = new AtomicInteger();
        CrawlHttpClient client = new CrawlHttpClient() {
            @Override public String fetchPage(String url, String charset) {
                requests.incrementAndGet();
                throw new CrawlAccessChallengeException(300);
            }
            @Override protected void waitForChallenge(long seconds) throws InterruptedException {
                throw new InterruptedException("source stopped");
            }
        };
        assertThrows(InterruptedException.class, () -> client.get("https://fixture/chapter/2", "GBK"));
        assertEquals(1, requests.get());
    }

    @Test
    void ruleTestStillReportsRestrictionImmediately() {
        CrawlHttpClient client = new CrawlHttpClient() {
            @Override public String fetchPage(String url, String charset) {
                throw new CrawlAccessChallengeException(300);
            }
            @Override protected void waitForChallenge(long seconds) {
                fail("rule test must not wait for a crawl retry");
            }
        };
        assertThrows(CrawlAccessChallengeException.class, () -> client.fetchPage("https://fixture/", "GBK"));
    }
}
