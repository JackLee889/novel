package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.CrawlBookBusyException;
import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.utils.CrawlHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(5)
class CrawlBookOwnershipTest {
    @Test
    void oneOwnerCoversTheSnapshotAndParserWhileOtherSourcesCannotEnterOrReleaseIt() throws Exception {
        var http = mock(CrawlHttpClient.class);
        var parser = new CrawlParser(http, null, CrawlTestStores.healthy());
        var rule = new RuleBean(); rule.setBookIndexUrl("https://fixture/{bookId}"); rule.setCharset("UTF-8");
        var book = new Book(); book.setId(10L);
        when(http.get(anyString(), anyString())).thenReturn(null);
        assertTrue(parser.beginBookCrawl(10L));
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertThrows(CrawlBookBusyException.class, () -> parser.parseBookIndexAndContent(
                    "other-source", book, rule, 2, Map.of(), batch -> fail("No writes while busy"), null));
                parser.endBookCrawl(10L);
            }).get(2, TimeUnit.SECONDS);
            assertTrue(parser.isBookCrawlActive(10L), "Only the owner may release the book");
            verifyNoInteractions(http);
            assertFalse(parser.parseBookIndexAndContent("same-owner", book, rule, 1, Map.of(), batch -> {}, null));
            assertTrue(parser.isBookCrawlActive(10L), "Nested parsing must retain its caller's ownership");
        } finally { parser.endBookCrawl(10L); }
        assertFalse(parser.isBookCrawlActive(10L));
    }

    @Test
    void anInterruptedDownloadReleasesTheBookForALaterRetry() throws Exception {
        var http = mock(CrawlHttpClient.class);
        var parser = new CrawlParser(http, null, CrawlTestStores.healthy());
        var rule = new RuleBean(); rule.setBookIndexUrl("https://fixture/{bookId}"); rule.setCharset("UTF-8");
        var book = new Book(); book.setId(20L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        when(http.get(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown(); Thread.sleep(60000); return null;
        });
        Thread worker = new Thread(() -> {
            try {
                parser.parseBookIndexAndContent("interrupted", book, rule, 1, Map.of(), batch -> {}, null);
                fail("Expected interruption");
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } finally { released.countDown(); }
        });
        try {
            worker.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(parser.isBookCrawlActive(20L));
            worker.interrupt();
            assertTrue(released.await(2, TimeUnit.SECONDS));
            assertFalse(parser.isBookCrawlActive(20L));
            assertTrue(parser.beginBookCrawl(20L));
            parser.endBookCrawl(20L);
        } finally { worker.interrupt(); worker.join(1000); }
    }
}
