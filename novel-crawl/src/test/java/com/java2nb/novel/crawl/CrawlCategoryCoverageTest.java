package com.java2nb.novel.crawl;

import tools.jackson.databind.ObjectMapper;
import com.java2nb.novel.core.crawl.CrawlBookHandler;
import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookCategory;
import com.java2nb.novel.entity.CrawlSingleTask;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.mapper.CrawlSingleTaskMapper;
import com.java2nb.novel.mapper.CrawlSourceMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import com.java2nb.novel.utils.CrawlHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 无外网/生产库调用，使用虚拟时钟检查多分类轮询以及实际入库路径的分类选择。 */
class CrawlCategoryCoverageTest {
    @AfterEach
    void clearTestThreadInterrupt() {
        Thread.interrupted();
    }

    @Test
    void legacyJsonAndNewListsResolveWithoutDroppingOtherLegacyCategories() {
        RuleBean rule = new ObjectMapper().readValue("""
            {"catIdRule":{"catId1":"old","catId2":"legacy","catId8":"other"},
             "catIdRules":{"catId1":[" 1 ","2","1",null,""],"catId8":[]}}
            """, RuleBean.class);
        assertEquals(List.of("1", "2"), rule.resolveCategoryRules(1));
        assertEquals(List.of("legacy"), rule.resolveCategoryRules(2));
        assertEquals(List.of(), rule.resolveCategoryRules(8));
        assertEquals(List.of(), rule.resolveCategoryRules(999));
        RuleBean old = new ObjectMapper().readValue("{\"catIdRule\":{\"catId8\":\"8\"}}", RuleBean.class);
        assertEquals(List.of("8"), old.resolveCategoryRules(8));
    }

    @Test
    void multipleSourceCategoriesKeepIndependentPageCountsAndResetIndependently() throws Exception {
        CrawlHttpClient http = mock(CrawlHttpClient.class);
        VirtualTimeService service = new VirtualTimeService(http);
        RuleBean rule = rule();
        rule.setCatIdRules(Map.of("catId1", List.of("a", "b")));
        List<String> requests = new ArrayList<>();
        when(http.get(anyString(), anyString())).thenAnswer(call -> {
            String url = call.getArgument(0);
            requests.add(url);
            if (requests.size() == 7) throw new InterruptedException("End fixture");
            return url.contains("/a/") ? "pages=2 book=a" : "pages=3 book=b";
        });
        service.parseBookList(1, rule, 4);
        assertEquals(List.of("https://fixture/a/1", "https://fixture/b/1", "https://fixture/a/2",
            "https://fixture/b/2", "https://fixture/b/3", "https://fixture/a/1",
            "https://fixture/b/1"), requests);
        assertEquals(List.of(Duration.ofMinutes(1)), service.waits);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void failingCategoryBacksOffAndRetriesSamePageWhileOtherCategoryContinues() throws Exception {
        CrawlHttpClient http = mock(CrawlHttpClient.class);
        VirtualTimeService service = new VirtualTimeService(http);
        RuleBean rule = rule();
        rule.setCatIdRules(Map.of("catId5", List.of("a", "b")));
        List<String> requests = new ArrayList<>();
        when(http.get(anyString(), anyString())).thenAnswer(call -> {
            String url = call.getArgument(0);
            requests.add(url);
            if (requests.size() == 5) throw new InterruptedException("End fixture");
            if (requests.size() == 1) return null;
            return url.contains("/b/") ? "pages=2" : "pages=1";
        });
        service.parseBookList(5, rule, 12);
        assertEquals(List.of("https://fixture/a/1", "https://fixture/b/1", "https://fixture/b/2",
            "https://fixture/a/1", "https://fixture/b/1"), requests);
        assertEquals(List.of(Duration.ofMinutes(1)), service.waits);
    }

    @Test
    void legacyFullUrlRulesAndSingleValuePaginationStillWork() throws Exception {
        CrawlHttpClient http = mock(CrawlHttpClient.class);
        VirtualTimeService service = new VirtualTimeService(http);
        RuleBean rule = rule();
        rule.setBookListUrl("");
        rule.setCatIdRule(Map.of("catId8", "https://fixture/other/{page}"));
        List<String> requests = new ArrayList<>();
        when(http.get(anyString(), anyString())).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            if (requests.size() == 3) throw new InterruptedException("End fixture");
            return "pages=2";
        });
        service.parseBookList(8, rule, 12);
        assertEquals(List.of("https://fixture/other/1", "https://fixture/other/2",
            "https://fixture/other/1"), requests);
    }

    @Test
    void interruptionDuringCooldownStopsWithoutRequestingAnotherCycle() throws Exception {
        CrawlHttpClient http = mock(CrawlHttpClient.class);
        VirtualTimeService service = new VirtualTimeService(http);
        service.interruptWait = true;
        RuleBean rule = rule();
        rule.setCatIdRules(Map.of("catId8", List.of("8")));
        when(http.get(anyString(), anyString())).thenReturn("pages=1");
        service.parseBookList(8, rule, 12);
        verify(http, times(1)).get("https://fixture/8/1", "utf-8");
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void explicitEmptyMappingDoesNotStartLegacyCategory() {
        CrawlHttpClient http = mock(CrawlHttpClient.class);
        VirtualTimeService service = new VirtualTimeService(http);
        RuleBean rule = rule();
        rule.setCatIdRule(Map.of("catId1", "1"));
        rule.setCatIdRules(Map.of("catId1", List.of()));
        service.parseBookList(1, rule, 4);
        verifyNoInteractions(http);
    }

    @Test
    @Timeout(5)
    void sourceStartsOnlyConfiguredDictionaryCategoriesIncludingEightAndStopsEveryWorker() throws Exception {
        BookService books = mock(BookService.class);
        CrawlSourceMapper sources = mock(CrawlSourceMapper.class);
        CrawlSource source = new CrawlSource();
        source.setCrawlRule("""
            {"catIdRules":{"catId1":["1","2"],"catId8":["13"],"catId777":["missing"]}}
            """);
        when(sources.selectMany(any(org.mybatis.dynamic.sql.select.render.SelectStatementProvider.class)))
            .thenReturn(List.of(source));
        when(sources.updateByPrimaryKeySelective(any(CrawlSource.class))).thenReturn(1);
        when(books.listBookCategories()).thenReturn(List.of(category(1, "玄幻奇幻", (byte) 0),
            category(8, "其他小说", (byte) 0), category(99, "未配置", (byte) 0)));
        Set<Integer> started = ConcurrentHashMap.newKeySet();
        AtomicInteger workerCount = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch stopped = new CountDownLatch(2);
        CrawlServiceImpl service = new CrawlServiceImpl(mock(CrawlParser.class), sources,
            mock(CrawlSingleTaskMapper.class), books, mock(CrawlHttpClient.class)) {
            @Override
            public void parseBookList(int categoryId, RuleBean rule, Integer sourceId) {
                started.add(categoryId);
                workerCount.incrementAndGet();
                ready.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    stopped.countDown();
                }
            }
        };
        try {
            service.openOrCloseCrawl(4, (byte) 1);
            service.openOrCloseCrawl(4, (byte) 1);
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            assertEquals(Set.of(1, 8), started);
            assertEquals(2, workerCount.get());
            verify(books, times(1)).listBookCategories();
        } finally {
            service.openOrCloseCrawl(4, (byte) 0);
        }
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
    }

    @Test
    void newBookUsesCategoryDictionaryDirectionRatherThanSevenSpecialCase() throws Exception {
        for (BookCategory category : List.of(category(8, "其他小说", (byte) 0),
                category(7, "测试字典方向", (byte) 0), category(21, "女频分类", (byte) 1))) {
            CrawlParser parser = mock(CrawlParser.class);
            BookService books = mock(BookService.class);
            Book parsed = new Book();
            parsed.setBookName("新书");
            parsed.setAuthorName("作者");
            doAnswer(call -> {
                ((CrawlBookHandler) call.getArgument(2)).handle(parsed);
                return null;
            }).when(parser).parseBook(any(), anyString(), any());
            when(books.queryBookCategoryByCatId(category.getId())).thenReturn(category);
            when(parser.parseBookIndexAndContent(anyString(), any(), any(), anyInt(), anyMap(), any(), isNull()))
                .thenReturn(true);
            CrawlServiceImpl service = new CrawlServiceImpl(parser, mock(CrawlSourceMapper.class),
                mock(CrawlSingleTaskMapper.class), books, mock(CrawlHttpClient.class));
            assertTrue(service.parseBookAndSave(category.getId(), rule(), 12, "book", null));
            assertEquals(category.getId(), parsed.getCatId());
            assertEquals(category.getName(), parsed.getCatName());
            assertEquals(category.getWorkDirection(), parsed.getWorkDirection());
            verify(parser).parseBookIndexAndContent(eq("book"), same(parsed), any(), eq(12),
                anyMap(), any(), isNull());
            verify(books, never()).queryCatNameByCatId(anyInt());
        }
    }

    @Test
    void missingDictionaryCategoryCannotWriteNewBookOrChapters() throws Exception {
        CrawlParser parser = mock(CrawlParser.class);
        BookService books = mock(BookService.class);
        Book parsed = new Book();
        parsed.setBookName("新书");
        parsed.setAuthorName("作者");
        doAnswer(call -> {
            ((CrawlBookHandler) call.getArgument(2)).handle(parsed);
            return null;
        }).when(parser).parseBook(any(), anyString(), any());
        CrawlServiceImpl service = new CrawlServiceImpl(parser, mock(CrawlSourceMapper.class),
            mock(CrawlSingleTaskMapper.class), books, mock(CrawlHttpClient.class));
        assertThrows(IllegalArgumentException.class, () -> service.parseBookAndSave(888, rule(), 12, "book", null));
        verify(parser, never()).parseBookIndexAndContent(anyString(), any(), any(), anyInt(), anyMap(), any(), any());
        verify(books, never()).saveBookAndIndexAndContent(any(), anyList(), anyList());
    }

    @Test
    void unfinishedNewBookResumesAfterRestartAndKeepsExistingCategory() throws Exception {
        for (boolean finished : List.of(false, true)) {
            CrawlParser parser = mock(CrawlParser.class);
            BookService books = mock(BookService.class);
            Book parsed = new Book();
            parsed.setBookName("未采完的新书"); parsed.setAuthorName("作者"); parsed.setBookStatus((byte) 0);
            Book existing = new Book();
            existing.setId(123L); existing.setCatId(2); existing.setCatName("武侠仙侠");
            existing.setWorkDirection((byte) 0);
            when(books.queryBookByBookNameAndAuthorName(parsed.getBookName(), parsed.getAuthorName())).thenReturn(existing);
            when(books.queryExistBookIndexMap(123L)).thenReturn(Map.of());
            when(parser.isPendingNewBook(1, 123L)).thenReturn(true);
            when(parser.beginBookCrawl(123L)).thenReturn(true);
            doAnswer(call -> {
                ((CrawlBookHandler) call.getArgument(2)).handle(parsed); return null;
            }).when(parser).parseBook(any(), anyString(), any());
            when(parser.parseBookIndexAndContent(anyString(), same(parsed), any(), eq(1), anyMap(), any(), isNull()))
                .thenReturn(finished);
            CrawlServiceImpl service = new CrawlServiceImpl(parser, mock(CrawlSourceMapper.class),
                mock(CrawlSingleTaskMapper.class), books, mock(CrawlHttpClient.class));
            RuleBean completedOnly = rule();
            completedOnly.setNewBookStatus(RuleBean.NewBookStatus.COMPLETED);
            assertEquals(finished, service.parseBookAndSave(8, completedOnly, 1, "book", null));
            verify(parser).endBookCrawl(123L);
            assertEquals(123L, parsed.getId()); assertEquals(2, parsed.getCatId());
            assertEquals("武侠仙侠", parsed.getCatName());
            verify(parser).parseBookIndexAndContent(eq("book"), same(parsed), same(completedOnly), eq(1), anyMap(), any(), isNull());
            verify(books, never()).updateCrawlProperties(anyLong(), anyInt(), anyString());
            if (finished) verify(parser).completePendingNewBook(1, 123L);
            else verify(parser, never()).completePendingNewBook(anyInt(), anyLong());
        }
    }

    @Test
    void categoryDiscoveryDoesNotReclassifyOrFetchChaptersOfExistingBook() throws Exception {
        CrawlParser parser = mock(CrawlParser.class);
        BookService books = mock(BookService.class);
        Book parsed = new Book();
        parsed.setBookName("已有书");
        parsed.setAuthorName("作者");
        Book existing = new Book();
        existing.setId(123L);
        existing.setCatId(2);
        existing.setCatName("武侠仙侠");
        existing.setWorkDirection((byte) 0);
        when(books.queryBookByBookNameAndAuthorName("已有书", "作者")).thenReturn(existing);
        doAnswer(call -> {
            ((CrawlBookHandler) call.getArgument(2)).handle(parsed);
            return null;
        }).when(parser).parseBook(any(), anyString(), any());
        CrawlServiceImpl service = new CrawlServiceImpl(parser, mock(CrawlSourceMapper.class),
            mock(CrawlSingleTaskMapper.class), books, mock(CrawlHttpClient.class));
        assertTrue(service.parseBookAndSave(8, rule(), 12, "book", null));
        assertEquals(2, existing.getCatId());
        assertEquals("武侠仙侠", existing.getCatName());
        verify(books, never()).queryBookCategoryByCatId(anyInt());
        verify(parser, never()).parseBookIndexAndContent(anyString(), any(), any(), anyInt(), anyMap(), any(), any());
        verify(books).updateCrawlProperties(123L, 12, "book");
    }

    private static RuleBean rule() {
        RuleBean rule = new RuleBean();
        rule.setCharset("utf-8");
        rule.setBookListUrl("https://fixture/{catId}/{page}");
        rule.setBookIdPatten("book=([a-z]+)");
        rule.setTotalPagePatten("pages=(\\d+)");
        return rule;
    }

    private static BookCategory category(int id, String name, byte direction) {
        BookCategory category = new BookCategory();
        category.setId(id);
        category.setName(name);
        category.setWorkDirection(direction);
        return category;
    }

    private static class VirtualTimeService extends CrawlServiceImpl {
        private long now;
        private final List<Duration> waits = new ArrayList<>();
        private boolean interruptWait;

        private VirtualTimeService(CrawlHttpClient http) {
            super(mock(CrawlParser.class), mock(CrawlSourceMapper.class), mock(CrawlSingleTaskMapper.class),
                mock(BookService.class), http);
            var discovery = mock(com.java2nb.novel.core.crawl.CrawlDiscoveryStore.class);
            when(discovery.cursor(anyInt(),anyInt(),anyString())).thenAnswer(c ->
                new com.java2nb.novel.core.crawl.CrawlDiscoveryStore.Cursor(c.getArgument(0),c.getArgument(1),c.getArgument(2),1,1,0,java.time.Instant.EPOCH));
            when(discovery.commitPage(any(),anyInt(),anyList(),anyString(),any())).thenReturn(true);
            setDiscoveryStore(discovery);
        }

        @Override
        protected long categoryTimeNanos() {
            return now;
        }

        @Override
        protected void waitForCategory(Duration delay) throws InterruptedException {
            waits.add(delay);
            if (interruptWait) throw new InterruptedException("End cooldown fixture");
            now += delay.toNanos();
        }

        @Override
        public boolean parseBookAndSave(int catId, RuleBean rule, Integer sourceId, String bookId,
                CrawlSingleTask task) {
            return true;
        }
    }
}
