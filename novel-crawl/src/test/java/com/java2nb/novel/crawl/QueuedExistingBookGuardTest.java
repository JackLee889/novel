package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.*;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.mapper.CrawlSingleTaskMapper;
import com.java2nb.novel.mapper.CrawlSourceMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import com.java2nb.novel.utils.CrawlHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueuedExistingBookGuardTest {
    BookService books;CrawlParser parser;CrawlDiscoveryStore store;CrawlServiceImpl service;Book existing;
    CrawlDiscoveryStore.Job job=new CrawlDiscoveryStore.Job(1L,12,"49611",1,123L,"lease",0);
    @BeforeEach void setup(){
        books=mock(BookService.class);parser=mock(CrawlParser.class);store=mock(CrawlDiscoveryStore.class);
        service=new CrawlServiceImpl(parser,mock(CrawlSourceMapper.class),mock(CrawlSingleTaskMapper.class),books,mock(CrawlHttpClient.class));
        service.setDiscoveryStore(store);existing=new Book();existing.setId(123L);existing.setCrawlSourceId(12);existing.setCrawlBookId("49611");
        existing.setCatId(4);existing.setCatName("local-category");existing.setWorkDirection((byte)0);existing.setPicUrl("/localPic/cover/keep.jpg");
        when(books.queryBookById(123L)).thenReturn(existing);
    }
    @Test void verifiedCompletedBookIsSkippedBeforeAnySourceRequest() throws Exception {
        when(store.isVerifiedCompleted(existing)).thenReturn(true);
        assertEquals("COMPLETE",service.parseDiscoveredBook(job,new RuleBean()).outcome());verifyNoInteractions(parser);
    }
    @Test void exhaustedStoreRetriesAreNotMultipliedByAnotherImmediateServiceRetryLoop() {
        var dispatcher=spy(service);var source=new com.java2nb.novel.entity.CrawlSource();
        source.setSourceStatus((byte)1);source.setCrawlRule("{}");
        doReturn(source).when(dispatcher).queryCrawlSource(12);
        when(store.enqueueExistingBook(same(existing),anyString()))
            .thenThrow(new org.springframework.dao.CannotAcquireLockException("fixture exhausted retries"));
        assertThrows(org.springframework.dao.CannotAcquireLockException.class,()->dispatcher.enqueueBookUpdate(123L));
        verify(store).enqueueExistingBook(same(existing),anyString());verifyNoInteractions(parser);
    }
    @ParameterizedTest @ValueSource(strings={"missing","stopped","source","identity"})
    void obsoleteOrStoppedJobsNeverFetchOrOverwriteAnotherBook(String reason) throws Exception {
        switch(reason){case "missing"->when(books.queryBookById(123L)).thenReturn(null);case "stopped"->existing.setCrawlIsStop((byte)1);
            case "source"->existing.setCrawlSourceId(13);case "identity"->existing.setCrawlBookId("different");}
        assertEquals("EXISTING",service.parseDiscoveredBook(job,new RuleBean()).outcome());verifyNoInteractions(parser);
    }
    @Test void queuedUpdateKeepsTheBoundBookEvenWhenSourceTitleChangesAndPreservesLocalCover() throws Exception {
        when(parser.beginBookCrawl(123L,25)).thenReturn(true);
        when(books.queryExistBookIndexMap(123L)).thenReturn(Map.of());
        when(parser.parseBookIndexAndContent(anyString(),any(),any(),eq(12),anyMap(),any(),isNull())).thenAnswer(call->{
            Book updated=call.getArgument(1);assertEquals(123L,updated.getId());assertEquals(4,updated.getCatId());assertNull(updated.getPicUrl());
            return false;
        });
        when(parser.lastRunMadeProgress()).thenReturn(true);
        doAnswer(call->{Book fromSource=new Book();fromSource.setBookName("renamed");fromSource.setAuthorName("source-author");
            fromSource.setPicUrl("https://source/cover.jpg");((CrawlBookHandler)call.getArgument(2)).handle(fromSource);return null;
        }).when(parser).parseBook(any(),eq("49611"),any());
        assertEquals("PARTIAL",service.parseDiscoveredBook(job,new RuleBean()).outcome());
        verify(books,never()).queryBookByBookNameAndAuthorName(anyString(),anyString());
        verify(books,never()).saveBookAndIndexAndContent(any(),anyList(),anyList());verify(parser).endBookCrawl(123L);
    }
}
