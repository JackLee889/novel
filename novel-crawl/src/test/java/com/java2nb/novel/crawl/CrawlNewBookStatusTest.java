package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.ChapterBean;
import com.java2nb.novel.core.crawl.CrawlBookChapterHandler;
import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.core.crawl.RuleBean.NewBookStatus;
import com.java2nb.novel.core.listener.StarterListener;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookCategory;
import com.java2nb.novel.entity.CrawlSingleTask;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.mapper.CrawlSingleTaskMapper;
import com.java2nb.novel.mapper.CrawlSourceMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.CrawlService;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.utils.CrawlMetadataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实详情解析与采集入口，HTTP、数据库和章节下载使用本地测试替身。 */
class CrawlNewBookStatusTest {
    private static final int SOURCE_ID = 12;
    private static final String SOURCE_BOOK_ID = "fixture-book";

    @ParameterizedTest
    @ValueSource(strings = {"佚名", "未知作者", "&nbsp;"})
    void unavailableNewBookAuthorsDoNotBlockChapterCollection(String author) throws Exception {
        Fixture f = fixture(new RuleBean(), (byte) 0);
        when(f.http.get(anyString(), anyString())).thenReturn(
            "<h1>测试小说</h1><author>" + author + "</author><intro>简介</intro>");
        assertTrue(f.service.parseBookAndSave(1, f.rule, SOURCE_ID, SOURCE_BOOK_ID, null));
        verify(f.books).saveBookAndIndexAndContent(argThat(b -> "作者待核验".equals(b.getAuthorName())), anyList(), anyList());
        verify(f.parser).parseBookIndexAndContent(anyString(), any(), any(), anyInt(), anyMap(), any(), any());
    }

    @Test
    void queuedExistingBookKeepsItsIdentityWhenTheSourceAuthorIsMissing() throws Exception {
        Fixture f = fixture(new RuleBean(), (byte) 0);
        Book existing = existingBook();
        existing.setBookName("测试小说"); existing.setAuthorName("已确认作者");
        when(f.books.queryBookById(123L)).thenReturn(existing);
        when(f.http.get(anyString(), anyString())).thenReturn(
            "<h1>测试小说</h1><author>佚名</author><status>连载</status><intro>简介</intro>");
        doAnswer(call -> {
            Book book = call.getArgument(0);
            assertEquals(123L, book.getId());
            assertEquals("测试小说", book.getBookName()); assertEquals("已确认作者", book.getAuthorName());
            return null;
        }).when(f.books).updateBookAndIndexAndContent(any(), anyList(), anyList(), anyMap(), anyList(), anyList(), nullable(Integer.class));
        var job = new com.java2nb.novel.core.crawl.CrawlDiscoveryStore.Job(1L, SOURCE_ID, SOURCE_BOOK_ID, 1, 123L, "lease", 0);
        assertEquals("COMPLETE", f.service.parseDiscoveredBook(job, f.rule).outcome());
        verify(f.books).updateBookAndIndexAndContent(any(), anyList(), anyList(), anyMap(), anyList(), anyList(), nullable(Integer.class));
        verify(f.books, never()).saveBookAndIndexAndContent(any(), anyList(), anyList());
        verify(f.books, never()).updateCrawlProperties(anyLong(), anyInt(), anyString());
    }

    @ParameterizedTest
    @CsvSource(value = {
        "ALL, 0, true", "ALL, 1, true", "ALL, null, true", "ALL, 2, true",
        "COMPLETED, 0, false", "COMPLETED, 1, true", "COMPLETED, null, false", "COMPLETED, 2, false",
        "SERIALIZING, 0, true", "SERIALIZING, 1, false", "SERIALIZING, null, false", "SERIALIZING, 2, false"
    }, nullValues = "null")
    void batchNewBooksDownloadChaptersOnlyWhenStatusMatches(NewBookStatus selection, Byte status,
        boolean expectedDownload) throws Exception {
        RuleBean rule = new RuleBean();
        rule.setNewBookStatus(selection);
        Fixture fixture = fixture(rule, status);

        assertTrue(fixture.service.parseBookAndSave(1, rule, SOURCE_ID, SOURCE_BOOK_ID, null));

        verify(fixture.http).get("https://fixture/book/" + SOURCE_BOOK_ID, "UTF-8");
        verify(fixture.parser, times(expectedDownload ? 1 : 0)).parseBookIndexAndContent(
            eq(SOURCE_BOOK_ID), any(), same(rule), eq(SOURCE_ID), anyMap(), any(), isNull());
        verify(fixture.books, times(expectedDownload ? 1 : 0)).queryBookCategoryByCatId(1);
        verify(fixture.books, times(expectedDownload ? 1 : 0)).saveBookAndIndexAndContent(
            any(), anyList(), anyList());
        verify(fixture.books, never()).updateCrawlProperties(anyLong(), anyInt(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"newBookStatus\":null}"})
    void legacyAndNullRulesStillDownloadBooksWithUnknownStatus(String json) throws Exception {
        RuleBean rule = new ObjectMapper().readValue(json, RuleBean.class);
        Fixture fixture = fixture(rule, null);
        assertTrue(fixture.service.parseBookAndSave(1, rule, SOURCE_ID, SOURCE_BOOK_ID, null));
        verify(fixture.books).saveBookAndIndexAndContent(any(), anyList(), anyList());
    }

    @Test
    void selectedModesSurviveJsonRoundTripAndInvalidModesAreRejected() {
        ObjectMapper mapper = new ObjectMapper();
        for (NewBookStatus selection : NewBookStatus.values()) {
            RuleBean rule = new RuleBean();
            rule.setNewBookStatus(selection);
            RuleBean restored = mapper.readValue(mapper.writeValueAsString(rule), RuleBean.class);
            assertEquals(selection, restored.getNewBookStatus());
        }
        assertThrows(RuntimeException.class,
            () -> mapper.readValue("{\"newBookStatus\":\"INVALID\"}", RuleBean.class));
    }

    @Test
    void missingStatusPatternDoesNotTreatAnUnknownBookAsSerializing() throws Exception {
        RuleBean rule = new RuleBean();
        rule.setNewBookStatus(NewBookStatus.SERIALIZING);
        Fixture fixture = fixture(rule, (byte) 0);
        rule.setStatusPatten(null);
        assertTrue(fixture.service.parseBookAndSave(1, rule, SOURCE_ID, SOURCE_BOOK_ID, null));
        verify(fixture.books, never()).saveBookAndIndexAndContent(any(), anyList(), anyList());
        verify(fixture.parser, never()).parseBookIndexAndContent(
            anyString(), any(), any(), anyInt(), anyMap(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED, 0", "SERIALIZING, 1"})
    void existingBooksKeepTheirSourceUpdatedEvenWhenStatusDoesNotMatch(NewBookStatus selection,
        Byte status) throws Exception {
        RuleBean rule = new RuleBean();
        rule.setNewBookStatus(selection);
        Fixture fixture = fixture(rule, status);
        Book rebound=existingBook();rebound.setCrawlBookId("old-source-reference");
        when(fixture.books.queryBookByBookNameAndAuthorName("测试小说", "测试作者"))
            .thenReturn(rebound);

        assertTrue(fixture.service.parseBookAndSave(1, rule, SOURCE_ID, SOURCE_BOOK_ID, null));

        verify(fixture.books).updateCrawlProperties(123L, SOURCE_ID, SOURCE_BOOK_ID);
        verify(fixture.books, never()).saveBookAndIndexAndContent(any(), anyList(), anyList());
    }

    @Test void unchangedSourceBindingSkipsTheWriteWithoutDownloadingTheBookAgain() throws Exception {
        Fixture fixture=fixture(new RuleBean(),(byte)0);
        when(fixture.books.queryBookByBookNameAndAuthorName("测试小说","测试作者")).thenReturn(existingBook());
        assertTrue(fixture.service.parseBookAndSave(1,fixture.rule,SOURCE_ID,SOURCE_BOOK_ID,null));
        verify(fixture.books,never()).updateCrawlProperties(anyLong(),anyInt(),anyString());
        verify(fixture.books,never()).saveBookAndIndexAndContent(any(),anyList(),anyList());
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED, 0", "SERIALIZING, 1"})
    void manualSingleTasksStillDownloadNewBooksAndRepairExistingBooks(NewBookStatus selection,
        Byte status) throws Exception {
        for (boolean exists : List.of(false, true)) {
            RuleBean rule = new RuleBean();
            rule.setNewBookStatus(selection);
            Fixture fixture = fixture(rule, status);
            if (exists) {
                when(fixture.books.queryBookByBookNameAndAuthorName("测试小说", "测试作者"))
                    .thenReturn(existingBook());
            }
            CrawlSingleTask task = new CrawlSingleTask();
            task.setId(42L);
            assertTrue(fixture.service.parseBookAndSave(1, rule, SOURCE_ID, SOURCE_BOOK_ID, task));
            verify(fixture.parser).parseBookIndexAndContent(eq(SOURCE_BOOK_ID), any(), same(rule),
                eq(SOURCE_ID), anyMap(), any(), same(task));
            if (exists) {
                verify(fixture.books).updateBookAndIndexAndContent(any(), anyList(), anyList(),
                    anyMap(), anyList(), anyList(), nullable(Integer.class));
            } else {
                verify(fixture.books).saveBookAndIndexAndContent(any(), anyList(), anyList());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED, 0", "SERIALIZING, 1"})
    @Timeout(5)
    void automaticUpdatesStillFetchChaptersForExistingBooks(NewBookStatus selection, Byte status)
        throws Exception {
        RuleBean rule = new RuleBean();
        rule.setNewBookStatus(selection);
        Fixture fixture = fixture(rule, status);
        Book existing=existingBook();
        when(fixture.books.queryBookById(123L)).thenReturn(existing);
        doAnswer(call -> {
            Book book=call.getArgument(0);assertEquals(123L,book.getId());assertEquals(status,book.getBookStatus());return null;
        }).when(fixture.books).updateBookAndIndexAndContent(any(),anyList(),anyList(),anyMap(),anyList(),anyList(),nullable(Integer.class));
        var job=new com.java2nb.novel.core.crawl.CrawlDiscoveryStore.Job(1L,SOURCE_ID,SOURCE_BOOK_ID,1,123L,"lease",0);
        assertEquals("COMPLETE",fixture.service.parseDiscoveredBook(job,rule).outcome());
        verify(fixture.books).updateBookAndIndexAndContent(any(),anyList(),anyList(),anyMap(),anyList(),anyList(),nullable(Integer.class));
    }

    @Test void queuedNewBooksKeepAllThreeFilteringModesAndQueueRecoveryBypassesNewBookFiltering() throws Exception {
        for (RuleBean.NewBookStatus mode : RuleBean.NewBookStatus.values()) {
            for (Byte sourceStatus : new Byte[]{0,1,null}) {
                RuleBean rule=new RuleBean();rule.setNewBookStatus(mode);
                Fixture f=fixture(rule,sourceStatus);
                var job=new com.java2nb.novel.core.crawl.CrawlDiscoveryStore.Job(1L,SOURCE_ID,SOURCE_BOOK_ID,1,null,"fixture-lease",0);
                var result=f.service.parseDiscoveredBook(job,rule);
                assertEquals(rule.acceptsNewBookStatus(sourceStatus)?"COMPLETE":"FILTERED",result.outcome());
                assertTrue(result.success());
            }
        }
        RuleBean completedOnly=new RuleBean();completedOnly.setNewBookStatus(RuleBean.NewBookStatus.COMPLETED);
        Fixture f=fixture(completedOnly,(byte)0);
        when(f.books.queryBookByBookNameAndAuthorName(anyString(),anyString())).thenReturn(existingBook());
        when(f.books.queryBookById(123L)).thenReturn(existingBook());
        // Redis may have been rebuilt; the durable queue's book checkpoint still resumes this book.
        var job=new com.java2nb.novel.core.crawl.CrawlDiscoveryStore.Job(1L,SOURCE_ID,SOURCE_BOOK_ID,1,123L,"fixture-lease",0);
        assertEquals("COMPLETE",f.service.parseDiscoveredBook(job,completedOnly).outcome());
        verify(f.parser).parseBookIndexAndContent(eq(SOURCE_BOOK_ID),any(),same(completedOnly),eq(SOURCE_ID),anyMap(),any(),isNull());
    }

    @Test void missingAuthorDoesNotStopNewBookChapters() throws Exception {
        Fixture f = fixture(new RuleBean(), (byte)0);
        when(f.http.get(anyString(), anyString())).thenReturn("<h1>测试小说</h1><author>佚名</author><intro>简介</intro>");
        assertTrue(f.service.parseBookAndSave(1, f.rule, SOURCE_ID, SOURCE_BOOK_ID, null));
        verify(f.books).saveBookAndIndexAndContent(argThat(b -> "作者待核验".equals(b.getAuthorName())
            && SOURCE_BOOK_ID.equals(b.getCrawlBookId()) && Integer.valueOf(SOURCE_ID).equals(b.getCrawlSourceId())), anyList(), anyList());
    }

    @Test void pendingAuthorsNeverMergeSameNameBooksAcrossSources() throws Exception {
        Fixture f = fixture(new RuleBean(), (byte)0);
        when(f.http.get(anyString(), anyString())).thenReturn("<h1>测试小说</h1><author>佚名</author><intro>简介</intro>");
        Book other = existingBook(); other.setAuthorName("作者待核验"); other.setCrawlSourceId(99);
        when(f.books.queryBookByBookNameAndAuthorName("测试小说", "作者待核验")).thenReturn(other);
        assertEquals(CrawlMetadataException.Reason.BOOK_IDENTITY_CONFLICT, assertThrows(CrawlMetadataException.class,
            () -> f.service.parseBookAndSave(1, f.rule, SOURCE_ID, SOURCE_BOOK_ID, null)).getReason());
        verify(f.books, never()).saveBookAndIndexAndContent(any(), anyList(), anyList());
        verify(f.books, never()).updateCrawlProperties(anyLong(), anyInt(), anyString());
    }

    @Test void exactSourceBindingSurvivesAuthorEnrichmentAndContinuesTheSameBook() throws Exception {
        Fixture f = fixture(new RuleBean(), (byte)0);
        Book original = existingBook(); original.setBookName("测试小说"); original.setAuthorName("作者待核验");
        when(f.books.queryBookBySource("测试小说", SOURCE_ID, SOURCE_BOOK_ID)).thenReturn(original);
        when(f.books.fillPendingAuthor(123L, SOURCE_ID, SOURCE_BOOK_ID, "测试作者")).thenReturn("测试作者");
        var job = new com.java2nb.novel.core.crawl.CrawlDiscoveryStore.Job(1L, SOURCE_ID, SOURCE_BOOK_ID, 1, null, "lease", 0);
        assertEquals(123L, f.service.parseDiscoveredBook(job, f.rule).bookId());
        verify(f.books).fillPendingAuthor(123L, SOURCE_ID, SOURCE_BOOK_ID, "测试作者");
        verify(f.books).updateBookAndIndexAndContent(argThat(b -> b.getId().equals(123L)
            && "测试作者".equals(b.getAuthorName())), anyList(), anyList(), anyMap(), anyList(), anyList(), nullable(Integer.class));
        verify(f.books, never()).saveBookAndIndexAndContent(any(), anyList(), anyList());
        verify(f.books, never()).queryBookByBookNameAndAuthorName(anyString(), anyString());
    }

    @Test void sourceIdentityRecoveryStillHonorsTheExistingBookStopSwitch() throws Exception {
        Fixture f = fixture(new RuleBean(), (byte)0);
        Book stopped = existingBook(); stopped.setBookName("测试小说"); stopped.setAuthorName("作者待核验");
        stopped.setCrawlIsStop((byte)1);
        when(f.books.queryBookBySource("测试小说", SOURCE_ID, SOURCE_BOOK_ID)).thenReturn(stopped);
        var job = new com.java2nb.novel.core.crawl.CrawlDiscoveryStore.Job(1L, SOURCE_ID, SOURCE_BOOK_ID, 1, null, "lease", 0);
        assertEquals("EXISTING", f.service.parseDiscoveredBook(job, f.rule).outcome());
        verify(f.books, never()).fillPendingAuthor(anyLong(), anyInt(), anyString(), anyString());
        verify(f.books, never()).saveBookAndIndexAndContent(any(), anyList(), anyList());
        verify(f.books, never()).updateBookAndIndexAndContent(any(), anyList(), anyList(), anyMap(), anyList(), anyList(), nullable(Integer.class));
    }

    private static Fixture fixture(RuleBean rule, Byte status) throws Exception {
        rule.setBookDetailUrl("https://fixture/book/{bookId}");
        rule.setBookNamePatten("<h1>([^<]+)</h1>");
        rule.setAuthorNamePatten("<author>([^<]+)</author>");
        rule.setStatusPatten("<status>([^<]+)</status>");
        rule.setBookStatusRule(Map.of("连载", (byte) 0, "完结", (byte) 1, "暂停", (byte) 2));
        rule.setDescStart("<intro>");
        rule.setDescEnd("</intro>");
        String label = status == null ? "未知" : status == 0 ? "连载" : status == 1 ? "完结" : "暂停";
        CrawlHttpClient http = mock(CrawlHttpClient.class);
        when(http.get(anyString(), anyString())).thenReturn(
            "<h1>测试小说</h1><author>测试作者</author><status>" + label + "</status><intro>测试简介</intro>");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.SetOperations<String, String> pending =
            mock(org.springframework.data.redis.core.SetOperations.class);
        when(redis.opsForSet()).thenReturn(pending);
        CrawlParser parser = spy(new CrawlParser(http, redis, CrawlTestStores.healthy()));
        doAnswer(call -> {
            ChapterBean chapter = new ChapterBean();
            chapter.setBookIndexList(List.of());
            chapter.setBookContentList(List.of());
            chapter.setBookIndexMetaList(List.of());
            chapter.setReindexList(List.of());
            ((CrawlBookChapterHandler) call.getArgument(5)).handle(chapter);
            return true;
        }).when(parser).parseBookIndexAndContent(anyString(), any(), any(), anyInt(), anyMap(),
            any(), nullable(CrawlSingleTask.class));
        BookService books = mock(BookService.class);
        BookCategory category = new BookCategory();
        category.setId(1);
        category.setName("测试分类");
        category.setWorkDirection((byte) 0);
        when(books.queryBookCategoryByCatId(1)).thenReturn(category);
        when(books.queryExistBookIndexMap(123L)).thenReturn(Map.of());
        CrawlServiceImpl service = new CrawlServiceImpl(parser, mock(CrawlSourceMapper.class),
            mock(CrawlSingleTaskMapper.class), books, http);
        service.setDiscoveryStore(mock(com.java2nb.novel.core.crawl.CrawlDiscoveryStore.class));
        return new Fixture(rule, parser, books, http, service);
    }

    @Test void newBookDeduplicationPreservesTheExistingSourceBindingAndBody() throws Exception {
        for(var mode:RuleBean.NewBookStatus.values()) {
            var rule=new RuleBean();rule.setNewBookStatus(mode);var f=fixture(rule,(byte)0);
            Book existing=existingBook();existing.setCrawlSourceId(99);existing.setCrawlBookId("original-book");
            when(f.books.queryBookByBookNameAndAuthorName("测试小说","测试作者")).thenReturn(existing);
            var job=new com.java2nb.novel.core.crawl.CrawlDiscoveryStore.Job(999L,SOURCE_ID,SOURCE_BOOK_ID,1,null,"new-book",0);
            var result=f.service.parseDiscoveredBook(job,rule);
            assertEquals("EXISTING",result.outcome());assertEquals(123L,result.bookId());
            verify(f.books,never()).updateCrawlProperties(anyLong(),anyInt(),anyString());
            verify(f.parser,never()).parseBookIndexAndContent(anyString(),any(),any(),anyInt(),anyMap(),any(),any());
            verify(f.books,never()).saveBookAndIndexAndContent(any(),anyList(),anyList());
            verify(f.books,never()).updateBookAndIndexAndContent(any(),anyList(),anyList(),anyMap(),any(),any(),any());
        }
    }

    private static Book existingBook() {
        Book book = new Book();
        book.setId(123L);
        book.setCatId(1);
        book.setCrawlSourceId(SOURCE_ID);
        book.setCrawlBookId(SOURCE_BOOK_ID);
        book.setWordCount(100);
        return book;
    }

    private record Fixture(RuleBean rule, CrawlParser parser, BookService books, CrawlHttpClient http,
                           CrawlServiceImpl service) { }
}
