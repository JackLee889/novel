package com.java2nb.novel.core.crawl;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.utils.CrawlHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrawlPriorityParserTest {
    CrawlHttpClient http;
    CrawlCollectionStore store;
    CrawlParser parser;
    RuleBean rule;
    Book book;
    Map<Integer,BookIndex> indexes;
    final AtomicReference<CrawlCollectionStore.Audit> checkpoint=new AtomicReference<>();
    int handlerCalls;
    @BeforeEach void setup() throws Exception {
        http=mock(CrawlHttpClient.class);store=mock(CrawlCollectionStore.class);
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
        parser=new CrawlParser(http,redis,store);
        rule=new RuleBean();rule.setCharset("UTF-8");rule.setBookIndexUrl("https://fixture/catalog");
        rule.setIndexIdPatten("href=\"/chapter/(\\d+)\"");rule.setIndexNamePatten("href=\"/chapter/\\d+\">([^<]+)</a>");
        rule.setBookContentUrl("https://fixture/chapter/{indexId}");rule.setContentStart("<article>");rule.setContentEnd("</article>");
        book=new Book();book.setId(1L);book.setBookStatus((byte)0);book.setWordCount(0);
        indexes=new HashMap<>();
        when(store.open(anyLong(),anyInt(),anyString(),anyString(),anyInt(),any())).thenAnswer(call->{
            var a=new CrawlCollectionStore.Audit(1,1,"book",call.getArgument(3),call.getArgument(4));
            var old=checkpoint.get();if(old!=null) {a.ready.or(old.ready);a.checked.or(old.checked);}return a;
        });
        when(store.readyChapters(anyLong(),anyList())).thenAnswer(call->{
            List<BookIndex> rows=call.getArgument(1);Set<Long> result=new HashSet<>();rows.forEach(i->result.add(i.getId()));return result;
        });
        doAnswer(call->{checkpoint.set(call.getArgument(0));return null;}).when(store).save(any());
        when(http.get(startsWith("https://fixture/chapter/"),anyString())).thenReturn("<article>这里是真实可读的测试章节正文，包含足够的文字用于校验。</article>");
    }
    String signature(int count) {
        StringBuilder text=new StringBuilder(new ObjectMapper().writeValueAsString(rule));
        for(int i=1;i<=count;i++) {String id=""+i,name="chapter"+i;text.append('\n').append(id.length()).append(':').append(id).append(':').append(name.length()).append(':').append(name);}
        return CrawlCollectionStore.hash(text.toString());
    }
    void catalog(int count) throws Exception {
        StringBuilder html=new StringBuilder();for(int i=1;i<=count;i++)html.append("<a href=\"/chapter/").append(i).append("\">chapter").append(i).append("</a>");
        when(http.get("https://fixture/catalog","UTF-8")).thenReturn(html.toString());
    }
    void old(int count,int expected) {
        var a=new CrawlCollectionStore.Audit(1,1,"book",signature(expected),expected);
        for(int i=0;i<count;i++) {var row=new BookIndex();row.setId(100L+i);row.setIndexNum(i);row.setIndexName("chapter"+(i+1));row.setSourceIndexId(""+(i+1));row.setWordCount(1);indexes.put(i,row);a.mark(i,true);}
        checkpoint.set(a);
    }
    List<String> run(CrawlRefreshStore.Session session) throws Exception {
        List<String> downloaded=new ArrayList<>();assertTrue(parser.beginBookCrawl(1L,3));
        try(var scope=parser.refreshScope(session)) {
            parser.parseBookIndexAndContent("book",book,rule,1,indexes,batch->{
                handlerCalls++;
                assertTrue(batch.getBookIndexMetaList()==null||batch.getBookIndexMetaList().isEmpty());
                assertTrue(batch.getReindexList()==null||batch.getReindexList().isEmpty());
                for(var index:batch.getBookIndexList()) {downloaded.add(index.getSourceIndexId());indexes.put(index.getIndexNum(),index);}
            },null);
        } finally {parser.endBookCrawl(1L);}
        return downloaded;
    }
    @Test void newTailIsFetchedInShortSlicesWithoutDownloadingHistoricalHoles() throws Exception {
        old(2,5);catalog(10);
        var first=new CrawlRefreshStore.Session(1,5,signature(5),null,0,0,2);
        assertEquals(List.of("6","7","8"),run(first));assertEquals(8,first.pendingFrom);
        verify(http,never()).get("https://fixture/chapter/3","UTF-8");
        verify(http,never()).get("https://fixture/chapter/4","UTF-8");
        var resumed=new CrawlRefreshStore.Session(1,10,signature(10),first.pendingFrom,0,0,0);
        assertEquals(List.of("9","10"),run(resumed));assertNull(resumed.pendingFrom);
        assertFalse(resumed.complete,"Old holes must not be counted as a complete book");
        assertEquals(7,checkpoint.get().ready.cardinality());
    }
    @Test void legacyBootstrapChecksCatalogWithoutVerifyingOrDownloadingTheOldBacklog() throws Exception {
        old(2,1000);checkpoint.set(null);catalog(1000);
        var s=new CrawlRefreshStore.Session(1,0,null,null,0,0,2);
        assertEquals(List.of(),run(s));assertFalse(s.complete);assertNotNull(s.checkedAt);
        verify(http,times(1)).get(anyString(),anyString());
        verify(store).readyChapters(1L,List.of());assertNull(checkpoint.get());
        assertEquals(0,handlerCalls);verify(store,never()).save(any());
    }
    @Test void legacyBookWithAMatchingPrefixGetsItsSmallNewTailOnTheFirstCheck() throws Exception {
        old(2,2);checkpoint.set(null);catalog(5);
        var s=new CrawlRefreshStore.Session(1,0,null,null,0,0,2);
        assertEquals(List.of("3","4","5"),run(s));assertNull(s.pendingFrom);
        verify(http,never()).get("https://fixture/chapter/1","UTF-8");
        verify(http,never()).get("https://fixture/chapter/2","UTF-8");
        assertFalse(s.complete,"Unknown old bodies still require background verification");
    }
    @Test void alreadyVerifiedUnchangedBookDoesNotFetchAnyBodyOrLoseItsReadyEvidence() throws Exception {
        old(5,5);catalog(5);var s=new CrawlRefreshStore.Session(1,5,signature(5),null,0,0,1);
        assertEquals(List.of(),run(s));assertTrue(s.complete);assertNull(s.savedAt);
        assertEquals(5,checkpoint.get().ready.cardinality());verify(http,times(1)).get(anyString(),anyString());
        verify(store,never()).readyChapters(anyLong(),anyList());
        assertEquals(0,handlerCalls);verify(store,never()).save(any());
    }
    @Test void sourceCompletionStillPublishesItsMetadataWithoutNewChapters() throws Exception {
        old(5,5);catalog(5);book.setBookStatus((byte)1);
        var s=new CrawlRefreshStore.Session(1,5,signature(5),null,0,0,1);
        run(s);assertTrue(s.sourceCompleted);assertTrue(s.complete);assertEquals(1,handlerCalls);verify(store).save(any());
    }
    @Test void reorderedCatalogDefersReconciliationToBackground() throws Exception {
        old(2,5);catalog(6);var s=new CrawlRefreshStore.Session(1,5,"mismatched-prefix",null,0,0,2);
        assertEquals(List.of(),run(s));assertNull(s.pendingFrom);verify(http,times(1)).get(anyString(),anyString());
    }

    @Test void unchangedCatalogDoesNotLoadHistoricalIndexesOrRewriteCompletionEvidence() throws Exception {
        old(5,5);catalog(5);var loaded=new java.util.concurrent.atomic.AtomicInteger();
        Map<Integer,BookIndex> deferred=CrawlParser.deferIndexes(()->{loaded.incrementAndGet();return indexes;});
        var session=new CrawlRefreshStore.Session(1,5,signature(5),null,0,0,1);
        assertTrue(parser.beginBookCrawl(1L,3));
        try(var scope=parser.refreshScope(session)) {
            assertTrue(parser.parseBookIndexAndContent("book",book,rule,1,deferred,batch->fail("No unchanged write"),null));
        } finally {parser.endBookCrawl(1L);}
        assertEquals(0,loaded.get());assertTrue(session.complete);assertFalse(parser.lastRunMadeProgress());
        verify(store,never()).save(any());
    }

    @Test void changedCatalogStillLoadsIndexesOnceAndPreservesExistingIds() throws Exception {
        old(2,2);catalog(4);var loaded=new java.util.concurrent.atomic.AtomicInteger();
        Map<Integer,BookIndex> deferred=CrawlParser.deferIndexes(()->{loaded.incrementAndGet();return indexes;});
        var session=new CrawlRefreshStore.Session(1,2,signature(2),null,0,0,1);
        List<Long> saved=new ArrayList<>();assertTrue(parser.beginBookCrawl(1L,3));
        try(var scope=parser.refreshScope(session)) {
            parser.parseBookIndexAndContent("book",book,rule,1,deferred,batch->saved.addAll(batch.getBookIndexList().stream().map(BookIndex::getId).toList()),null);
        } finally {parser.endBookCrawl(1L);}
        assertEquals(1,loaded.get());assertEquals(2,saved.size());assertEquals(100L,indexes.get(0).getId());assertEquals(101L,indexes.get(1).getId());
    }

    @Test void missingChapterDoesNotStopOtherChaptersOrPretendTheBookIsComplete() throws Exception {
        old(2,2);catalog(5);book.setBookName("测试书");
        when(http.get("https://fixture/chapter/3","UTF-8")).thenReturn(null);
        when(http.lastFailure()).thenReturn(new com.java2nb.novel.utils.CrawlFetchFailure(
            com.java2nb.novel.utils.CrawlFetchFailure.Reason.HTTP,404,"https://user:secret@fixture/chapter/3?token=private",900,false));
        var monitor=new CrawlRuntimeMonitor();parser.setRuntimeMonitor(monitor);
        var session=new CrawlRefreshStore.Session(1,2,signature(2),null,0,0,1);
        assertEquals(List.of("4","5"),run(session));assertFalse(session.complete);
        var error=monitor.snapshot(1).errors().getFirst().message();
        assertTrue(error.contains("[CHAPTER_HTTP_404]"));assertTrue(error.contains("书名=测试书"));assertTrue(error.contains("章号=3"));
        assertFalse(error.contains("secret"));assertFalse(error.contains("private"));
        assertEquals(1,checkpoint.get().failed);assertFalse(checkpoint.get().ready.get(2));
    }

    @Test void cachedMissingPagesDoNotConsumeTheBudgetForLaterReadableChapters() throws Exception {
        old(2,2);catalog(8);
        for(int i=3;i<=6;i++)when(http.get("https://fixture/chapter/"+i,"UTF-8")).thenReturn(null);
        when(http.lastFailure()).thenReturn(new com.java2nb.novel.utils.CrawlFetchFailure(
            com.java2nb.novel.utils.CrawlFetchFailure.Reason.HTTP,404,"https://fixture/missing",800,true));
        var session=new CrawlRefreshStore.Session(1,2,signature(2),null,0,0,1);
        assertEquals(List.of("7","8"),run(session));assertFalse(session.complete);assertEquals(4,checkpoint.get().failed);
    }
}
