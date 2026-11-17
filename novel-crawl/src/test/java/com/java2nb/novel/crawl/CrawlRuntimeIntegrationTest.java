package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.*;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.mapper.CrawlSingleTaskMapper;
import com.java2nb.novel.mapper.CrawlSourceMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.vo.CrawlSourceVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrawlRuntimeIntegrationTest {
    @Test
    void pollingReadsSourceListAndTelemetryWithoutStartingTasksOrWritingData() {
        var monitor = new CrawlRuntimeMonitor(); monitor.saved(14, 7);
        var parser = mock(CrawlParser.class);
        when(parser.getCrawlSourceChapterCount(14)).thenReturn(100L);
        var mapper = mock(CrawlSourceMapper.class);
        var source = new CrawlSource(); source.setId(14); source.setSourceName("UU看书");
        when(mapper.selectMany(any(org.mybatis.dynamic.sql.select.render.SelectStatementProvider.class))).thenReturn(List.of(source));
        var books = mock(BookService.class); var tasks = mock(CrawlSingleTaskMapper.class); var http = mock(CrawlHttpClient.class);
        var service = new CrawlServiceImpl(parser, mapper, tasks, books, http);
        service.setRuntimeMonitor(monitor);
        var first = (CrawlSourceVO) service.listCrawlByPage(1, 10).getList().getFirst();
        assertEquals(7, first.getRuntime().savedLastMinute());
        assertEquals(100, first.getChapterCount());
        service.listCrawlByPage(1, 10);
        verify(mapper, never()).updateByPrimaryKeySelective(any());
        verifyNoInteractions(books, tasks, http);
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistenceCallbackMustSucceedBeforeAnyRealtimeOrRedisCountChanges() {
        CrawlRuntimeMonitor monitor = new CrawlRuntimeMonitor();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        var parser = new CrawlParser(mock(CrawlHttpClient.class), redis, mock(CrawlCollectionStore.class));
        parser.setRuntimeMonitor(monitor);
        Book book = new Book(); book.setId(1L);
        CrawlBookChapterHandler fail = batch -> { throw new IllegalStateException("fixture transaction rollback"); };
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(parser, "flushBatch", book,
            new ArrayList<>(List.of(new BookIndex())), new ArrayList<BookContent>(), new ArrayList<BookIndex>(),
            null, 1, fail, 14, null, true));
        assertEquals(0, monitor.snapshot(14).savedSinceStart());
        verifyNoInteractions(redis);

        CrawlBookChapterHandler success = batch -> assertEquals(0, monitor.snapshot(14).savedSinceStart());
        try (var activity = monitor.beginBook(14, "UPDATE", "23125")) {
            ReflectionTestUtils.invokeMethod(parser, "flushBatch", book,
                new ArrayList<>(List.of(new BookIndex(), new BookIndex())), new ArrayList<BookContent>(),
                new ArrayList<BookIndex>(), null, 2, success, 14, null, true);
            assertEquals(2, monitor.snapshot(14).savedSinceStart());
            assertEquals(2, monitor.snapshot(14).tasks().getFirst().saved());
        }
        verify(redis.opsForValue()).increment("crawlSource:chapterCount:14", 2);
        ReflectionTestUtils.invokeMethod(parser, "flushBatch", book, new ArrayList<BookIndex>(), new ArrayList<BookContent>(),
            new ArrayList<>(List.of(new BookIndex())), null, 2, (CrawlBookChapterHandler) batch -> {}, 14, null, true);
        assertEquals(2, monitor.snapshot(14).savedSinceStart(), "只更新目录身份不计为正文保存");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "INTERRUPTED", "BUSY"})
    void exceptionalBookRunsReleaseTheirActiveTaskAndDoNotInventSavedChapters(String kind) throws Exception {
        var monitor = new CrawlRuntimeMonitor();
        var parser = mock(CrawlParser.class);
        doAnswer(call -> {
            assertEquals(1, monitor.snapshot(14).activeDownloads());
            if (kind.equals("INTERRUPTED")) throw new InterruptedException();
            if (kind.equals("BUSY")) throw new CrawlBookBusyException(1L);
            throw new IllegalStateException("private upstream token");
        }).when(parser).parseBook(any(), anyString(), any());
        var service = new CrawlServiceImpl(parser, mock(CrawlSourceMapper.class), mock(CrawlSingleTaskMapper.class),
            mock(BookService.class), mock(CrawlHttpClient.class));
        service.setRuntimeMonitor(monitor);
        assertThrows(Exception.class, () -> service.parseBookAndSave(1, new RuleBean(), 14, "99", null));
        var snapshot = monitor.snapshot(14);
        assertTrue(snapshot.tasks().isEmpty());
        assertEquals(kind, snapshot.lastTask().outcome());
        assertEquals(0, snapshot.savedSinceStart());
        assertEquals(kind.equals("FAILED") ? 1 : 0, snapshot.errorsLastMinute());
    }

    @Test
    void newBookFilterStillSkipsWritingAndSerializesRuntimeWithExistingSourceFields() throws Exception {
        var monitor = new CrawlRuntimeMonitor();
        var parser = mock(CrawlParser.class);
        var books = mock(BookService.class);
        doAnswer(call -> {
            var book = new Book(); book.setBookName("连载图书"); book.setAuthorName("作者"); book.setBookStatus((byte) 0);
            ((CrawlBookHandler) call.getArgument(2)).handle(book);
            return null;
        }).when(parser).parseBook(any(), anyString(), any());
        var service = new CrawlServiceImpl(parser, mock(CrawlSourceMapper.class), mock(CrawlSingleTaskMapper.class), books,
            mock(CrawlHttpClient.class));
        service.setRuntimeMonitor(monitor);
        var rule = new RuleBean(); rule.setNewBookStatus(RuleBean.NewBookStatus.COMPLETED);
        assertTrue(service.parseBookAndSave(1, rule, 14, "99", null));
        assertEquals("FILTERED", monitor.snapshot(14).lastTask().outcome());
        assertEquals("连载图书", monitor.snapshot(14).lastTask().bookName());
        verify(books, never()).saveBookAndIndexAndContent(any(), anyList(), anyList());
        var source = new CrawlSourceVO(); source.setId(14); source.setSourceName("UU看书");
        source.setSourceStatus((byte) 1); source.setChapterCount(100L); source.setRuntime(monitor.snapshot(14));
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(Map.of("list", List.of(source))));
        assertEquals("UU看书", json.get("list").get(0).get("sourceName").asString());
        assertEquals(0, json.get("list").get(0).get("runtime").get("savedLastMinute").asInt());
        assertEquals("FILTERED", json.get("list").get(0).get("runtime").get("lastTask").get("outcome").asString());
    }
}
