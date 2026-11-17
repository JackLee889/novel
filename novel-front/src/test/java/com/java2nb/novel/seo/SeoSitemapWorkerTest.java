package com.java2nb.novel.seo;

import com.java2nb.novel.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeoSitemapWorkerTest {
    @TempDir Path root;
    @Test void bootstrapRetriesFailedPageWithoutAdvancingAndPausesWithWorkerFlag(){
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setEnabled(true);when(seo.publicSiteUrl()).thenReturn("https://xs001.xyz");
        when(r.bootstrapMissingStates(0,50)).thenReturn(new SeoRepository.BootstrapBatch(50,50,3,false));
        when(r.bootstrapMissingStates(50,50)).thenThrow(new org.springframework.dao.CannotAcquireLockException("fixture")).thenReturn(new SeoRepository.BootstrapBatch(51,1,1,true));
        when(r.reconciliationIds(0,100)).thenReturn(List.of());when(r.claim(60)).thenReturn(Optional.empty());
        var worker=new SeoSitemapWorker(r,seo,p);worker.tick();worker.tick();
        verify(r,never()).reconciliationIds(anyLong(),anyInt());
        worker.tick();verify(r,times(2)).bootstrapMissingStates(50,50);verify(r).reconciliationIds(0,100);
        p.setSitemapWorkerEnabled(false);worker.tick();verify(r,times(3)).bootstrapMissingStates(anyLong(),anyInt());
    }
    @Test void keysetBatchesSplitGzipExcludeVipAndIncludeUnvalidatedDirectoryEntries()throws Exception {
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());p.setSitemapUrlsPerPart(2);
        var b=new Book();b.setId(7L);b.setStatus((byte)1);when(r.book(7)).thenReturn(b);when(seo.isIndexable(b)).thenReturn(true);when(seo.publicSiteUrl()).thenReturn("https://xs001.xyz");
        when(r.settings()).thenReturn(new SeoSiteSettings(9,true,true,true,"","","","",""));when(r.state(7)).thenReturn(Map.of("revision",3L));
        List<SeoRepository.ChapterRow> rows=new ArrayList<>();for(long id=1;id<=6;id++){var c=new BookIndex();c.setId(id);c.setBookId(7L);c.setWordCount(20);c.setStorageType("db");c.setIsVip(id==2?(byte)1:(byte)0);c.setCreateTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")));rows.add(new SeoRepository.ChapterRow(c));}
        when(r.chapterPage(7,0,100)).thenReturn(rows);when(r.chapterPage(7,6,100)).thenReturn(List.of());when(r.readyDatabaseIds(anyList())).thenReturn(Set.of(1L,2L,3L,5L,6L));
        var ticket=new SeoRepository.Ticket(7,3,9,Instant.parse("2024-01-01T00:00:00Z"),"job");new SeoSitemapWorker(r,seo,p).build(ticket);
        verify(r).complete(eq(ticket),contains("chapters-7-r3-e9-p2-"),eq("https://xs001.xyz"),eq(5L),eq(false),eq(6L),longThat(value->value>=0));
        verify(r).chapterPage(7,0,100);verify(r).chapterPage(7,6,100);verify(r,never()).readyDatabaseIds(anyList());
        String xml="";try(var paths=Files.list(root)){for(Path file:paths.filter(x->x.toString().endsWith(".gz")).toList())try(var in=new GZIPInputStream(Files.newInputStream(file))){xml+=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
        assertFalse(xml.contains("/7/2.html"));assertTrue(xml.contains("/7/4.html"));assertFalse(xml.contains("<lastmod>"));assertFalse(xml.contains("2024-01-01"));
    }
    @Test void globalEpochChangePreventsPublication() {
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());var b=new Book();b.setId(7L);b.setStatus((byte)1);
        when(r.book(7)).thenReturn(b);when(seo.isIndexable(b)).thenReturn(true);when(seo.publicSiteUrl()).thenReturn("https://xs001.xyz");
        when(r.settings()).thenReturn(new SeoSiteSettings(1,true,true,true,"","","","",""),new SeoSiteSettings(2,true,true,true,"","","","",""));
        var c=new BookIndex();c.setId(10L);c.setBookId(7L);c.setWordCount(20);c.setStorageType("db");when(r.chapterPage(7,0,100)).thenReturn(List.of(new SeoRepository.ChapterRow(c)));when(r.readyDatabaseIds(anyList())).thenReturn(Set.of(10L));
        new SeoSitemapWorker(r,seo,p).build(new SeoRepository.Ticket(7,1,1,Instant.now(),"job"));verify(r,never()).complete(any(),anyString(),anyString(),anyLong(),anyBoolean(),anyLong(),anyLong());
    }
    @Test void byteLimitCountsUncompressedXmlAndCleanupDoesNotPublishPartialFiles()throws Exception {
        try(var writer=new SitemapXmlWriter(root,"part.xml.gz",200)){assertFalse(writer.fits(SitemapXmlWriter.entry("https://xs001.xyz/"+"x".repeat(500),null)));}
        try(var files=Files.list(root)){assertEquals(0,files.count());}
    }
    @Test void txtDirectoryEntriesDoNotReadFilesOrRequireContentToExist()throws Exception{
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.resolve("maps").toString());p.setContentDirectory(root.resolve("absent-content").toString());
        var book=new Book();book.setId(7L);book.setStatus((byte)1);when(r.book(7)).thenReturn(book);when(seo.isIndexable(book)).thenReturn(true);when(seo.publicSiteUrl()).thenReturn("https://xs001.xyz");
        when(r.settings()).thenReturn(new SeoSiteSettings(9,true,true,true,"","","","",""));when(r.state(7)).thenReturn(Map.of("revision",3L));
        List<SeoRepository.ChapterRow> rows=new ArrayList<>();for(long id=1;id<=6;id++){var c=new BookIndex();c.setId(id);c.setBookId(7L);c.setWordCount(20);c.setIsVip((byte)0);c.setStorageType("txt");rows.add(new SeoRepository.ChapterRow(c));}
        when(r.chapterPage(7,0,100)).thenReturn(rows);when(r.chapterPage(7,6,100)).thenReturn(List.of());
        var ticket=new SeoRepository.Ticket(7,3,9,null,"job");
        try(var readiness=mockStatic(ChapterContentReadiness.class)){
            new SeoSitemapWorker(r,seo,p).build(ticket);readiness.verifyNoInteractions();
        }
        verify(r,never()).readyDatabaseIds(anyList());
        verify(r).complete(eq(ticket),anyString(),eq("https://xs001.xyz"),eq(6L),eq(false),eq(6L),anyLong());
        assertFalse(Files.exists(root.resolve("absent-content")));
    }
    @Test void nightlyReconciliationUsesBoundedBatchesAndDoesNotEnqueueUnchangedBooks(){
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setEnabled(true);when(seo.publicSiteUrl()).thenReturn("https://xs001.xyz");when(r.claim(60)).thenReturn(Optional.empty());
        when(r.bootstrapMissingStates(0,50)).thenReturn(new SeoRepository.BootstrapBatch(0,0,0,true));
        var first=java.util.stream.LongStream.rangeClosed(1,100).boxed().toList();when(r.reconciliationIds(0,100)).thenReturn(first);when(r.reconciliationIds(100,100)).thenReturn(List.of(101L));
        var worker=new SeoSitemapWorker(r,seo,p);worker.tick();verify(r).reconcileBook(100);verify(r,never()).reconcileBook(101);
        worker.tick();verify(r).reconcileBook(101);worker.tick();verify(r,times(1)).reconciliationIds(100,100);
        worker.reconcileNightly();worker.tick();verify(r,times(2)).reconciliationIds(0,100);
        verify(r,never()).enqueueAll();verify(r,never()).enqueueBook(anyLong());
    }
    @Test void generationFailureReportsElapsedDurationForTheClaimedTicket(){
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());var ticket=new SeoRepository.Ticket(7,3,9,null,"job");
        when(r.settings()).thenThrow(new IllegalStateException("storage unavailable"));new SeoSitemapWorker(r,seo,p).build(ticket);
        verify(r).failed(eq(ticket),contains("IllegalStateException"),longThat(value->value>=0));
    }

    @Test void changedRevisionDuringDirectoryReadSkipsBodyChecksAndPublication(){
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());
        var b=new Book();b.setId(7L);when(r.book(7)).thenReturn(b);when(seo.isIndexable(b)).thenReturn(true);
        when(r.settings()).thenReturn(new SeoSiteSettings(9,true,true,true,"","","","",""));
        when(r.state(7)).thenReturn(Map.of("revision",3L),Map.of("revision",4L));
        var c=new BookIndex();c.setId(10L);c.setWordCount(20);c.setStorageType("db");
        when(r.chapterPage(7,0,100)).thenReturn(List.of(new SeoRepository.ChapterRow(c)));
        new SeoSitemapWorker(r,seo,p).build(new SeoRepository.Ticket(7,3,9,null,"job"));
        verify(r,never()).readyDatabaseIds(anyList());
        verify(r,never()).complete(any(),anyString(),anyString(),anyLong(),anyBoolean(),anyLong(),anyLong());
    }

    @Test void staleTicketDoesNotEvenReadTheChapterDirectory(){
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());var b=new Book();b.setId(7L);
        when(r.book(7)).thenReturn(b);when(seo.isIndexable(b)).thenReturn(true);when(r.settings()).thenReturn(new SeoSiteSettings(9,true,true,true,"","","","",""));
        when(r.state(7)).thenReturn(Map.of("revision",4L));
        new SeoSitemapWorker(r,seo,p).build(new SeoRepository.Ticket(7,3,9,null,"job"));
        verify(r,never()).chapterPage(anyLong(),anyLong(),anyInt());verify(r,never()).readyDatabaseIds(anyList());
    }
    @Test void scheduledBuildYieldsBetweenPagesAndPublishesOnlyAfterTheFinalPage() {
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setEnabled(true);p.setSitemapDirectory(root.toString());
        var ticket=new SeoRepository.Ticket(7,3,9,null,"job");var b=new Book();b.setId(7L);b.setStatus((byte)1);
        when(r.book(7)).thenReturn(b);when(seo.isIndexable(b)).thenReturn(true);when(seo.publicSiteUrl()).thenReturn("https://xs001.xyz");
        when(r.settings()).thenReturn(new SeoSiteSettings(9,true,true,true,"","","","",""));when(r.state(7)).thenReturn(Map.of("revision",3L));
        when(r.claim(60)).thenReturn(Optional.of(ticket));when(r.bootstrapMissingStates(0,50)).thenReturn(new SeoRepository.BootstrapBatch(0,0,0,true));when(r.reconciliationIds(0,100)).thenReturn(List.of());
        var c=new BookIndex();c.setId(10L);c.setBookId(7L);c.setWordCount(20);c.setStorageType("db");
        when(r.chapterPage(7,0,100)).thenReturn(List.of(new SeoRepository.ChapterRow(c)));when(r.chapterPage(7,10,100)).thenReturn(List.of());when(r.readyDatabaseIds(anyList())).thenReturn(Set.of(10L));
        var worker=new SeoSitemapWorker(r,seo,p);org.springframework.test.util.ReflectionTestUtils.setField(worker,"budgeted",true);
        worker.tick();verify(r,never()).chapterPage(7,10,100);verify(r,never()).complete(any(),anyString(),anyString(),anyLong(),anyBoolean(),anyLong(),anyLong());
        p.setSitemapWorkerEnabled(false);worker.tick();verify(r,never()).chapterPage(7,10,100);
        p.setSitemapWorkerEnabled(true);worker.tick();verify(r).chapterPage(7,10,100);verify(r,times(1)).claim(60);
        verify(r).complete(eq(ticket),anyString(),eq("https://xs001.xyz"),eq(1L),eq(false),eq(1L),anyLong());
    }
    @Test void resumedBuildDiscardsUnpublishedCursorWhenRevisionChanges(){
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setEnabled(true);p.setSitemapDirectory(root.toString());
        var ticket=new SeoRepository.Ticket(7,3,9,null,"job");var b=new Book();b.setId(7L);b.setStatus((byte)1);
        when(r.book(7)).thenReturn(b);when(seo.isIndexable(b)).thenReturn(true);when(seo.publicSiteUrl()).thenReturn("https://xs001.xyz");
        when(r.settings()).thenReturn(new SeoSiteSettings(9,true,true,true,"","","","",""));when(r.state(7)).thenReturn(Map.of("revision",3L));when(r.claim(60)).thenReturn(Optional.of(ticket));
        when(r.bootstrapMissingStates(0,50)).thenReturn(new SeoRepository.BootstrapBatch(0,0,0,true));when(r.reconciliationIds(0,100)).thenReturn(List.of());
        var c=new BookIndex();c.setId(10L);c.setBookId(7L);c.setWordCount(20);when(r.chapterPage(7,0,100)).thenReturn(List.of(new SeoRepository.ChapterRow(c)));when(r.readyDatabaseIds(anyList())).thenReturn(Set.of(10L));
        var worker=new SeoSitemapWorker(r,seo,p);org.springframework.test.util.ReflectionTestUtils.setField(worker,"budgeted",true);worker.tick();
        when(r.state(7)).thenReturn(Map.of("revision",4L));worker.tick();
        verify(r,never()).chapterPage(7,10,100);verify(r,never()).complete(any(),anyString(),anyString(),anyLong(),anyBoolean(),anyLong(),anyLong());
    }

    @Test void metadataPolicyRetainsVipOwnershipAndPositiveWordCountWithoutBodyQueries()throws Exception {
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());
        var book=new Book();book.setId(7L);book.setStatus((byte)1);when(r.book(7)).thenReturn(book);when(seo.isIndexable(book)).thenReturn(true);when(seo.publicSiteUrl()).thenReturn("https://xs001.xyz");
        when(r.settings()).thenReturn(new SeoSiteSettings(9,true,true,true,"","","","",""));when(r.state(7)).thenReturn(Map.of("revision",3L));
        var rows=new ArrayList<SeoRepository.ChapterRow>();for(long id=1;id<=5;id++){var c=new BookIndex();c.setId(id);c.setBookId(id==4?8L:7L);c.setWordCount(id==5?0:10);c.setIsVip(id==2?(byte)1:(byte)0);rows.add(new SeoRepository.ChapterRow(c));}
        when(r.chapterPage(7,0,100)).thenReturn(rows);when(r.chapterPage(7,5,100)).thenReturn(List.of());
        when(r.readyDatabaseIds(anyList())).thenThrow(new AssertionError("SEO must not read bodies"));
        var ticket=new SeoRepository.Ticket(7,3,9,null,"job");new SeoSitemapWorker(r,seo,p).build(ticket);
        verify(r,never()).readyDatabaseIds(anyList());
        verify(r).complete(eq(ticket),anyString(),eq("https://xs001.xyz"),eq(2L),eq(false),eq(5L),anyLong());
    }
    @Test void coreMetadataUpdatesAreCoalescedButVisibilityRevocationIsImmediate()throws Exception{
        var r=mock(SeoRepository.class);var seo=mock(SeoService.class);var p=new SeoProperties();p.setEnabled(true);
        when(seo.publicSiteUrl()).thenReturn("https://xs001.xyz");when(seo.siteIndexEnabled()).thenReturn(true);
        when(r.bootstrapMissingStates(0,50)).thenReturn(new SeoRepository.BootstrapBatch(0,0,0,true));when(r.reconciliationIds(0,100)).thenReturn(List.of());when(r.claim(60)).thenReturn(Optional.empty());
        when(r.settings()).thenReturn(new SeoSiteSettings(9,true,true,true,"","","","",""));
        Map<String,Object> state=new HashMap<>(Map.of("indexFile","previous.xml","builtEpoch",9L,"builtOrigin","https://xs001.xyz","visibilityRevision",1L,"builtVisibilityRevision",1L,"revision",2L,"builtRevision",1L));when(r.coreState()).thenReturn(state);
        var builder=mock(SeoCoreSnapshotBuilder.class);var worker=new SeoSitemapWorker(r,seo,p);org.springframework.test.util.ReflectionTestUtils.setField(worker,"coreBuilder",builder);
        org.springframework.test.util.ReflectionTestUtils.setField(worker,"lastCoreBuild",System.currentTimeMillis()-20000);worker.tick();verify(builder,never()).build();
        state.put("visibilityRevision",2L);worker.tick();verify(builder).build();
    }
}
