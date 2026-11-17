package com.java2nb.novel.seo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class SeoCoreSnapshotBuilderTest {
    @TempDir Path root;
    @Test void coreFilesAreGeneratedOffRequestWithConfiguredCountAndByteLimits()throws Exception {
        var r=mock(SeoRepository.class);var s=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());p.setSitemapUrlsPerPart(2);p.setSitemapMaxBytes(2048);
        when(s.siteIndexEnabled()).thenReturn(true);when(s.publicSiteUrl()).thenReturn("https://xs001.xyz");when(s.catalogPageSize()).thenReturn(200);var ticket=new SeoRepository.CoreTicket(4,2,1);when(r.coreTicket()).thenReturn(ticket);when(r.settings()).thenReturn(new SeoSiteSettings(4,true,true,true,"","","","",""));
        when(r.publishedStates(4,"https://xs001.xyz")).thenReturn(List.of(Map.of("builtFiles","chapters-7-r1-e4-p1-11111111-1111-1111-1111-111111111111.xml.gz")));
        Map<String,Object> book=new HashMap<>();book.put("bookId",7L);book.put("chapterCount",null);when(r.publicBooks()).thenReturn(List.of(book));when(r.completeCore(eq(ticket),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong())).thenReturn(true);
        assertTrue(new SeoCoreSnapshotBuilder(r,s,p).build());verify(r).completeCore(eq(ticket),eq("https://xs001.xyz"),anyString(),startsWith("index-"),contains("core-"),anyString(),anyLong(),longThat(value->value>=0));
        try(var files=Files.list(root)){var values=files.toList();Path index=values.stream().filter(x->x.getFileName().toString().startsWith("index-")).findFirst().orElseThrow();String manifest=Files.readString(index);assertTrue(manifest.contains("https://xs001.xyz/sitemap-core-"));assertTrue(manifest.contains("https://xs001.xyz/sitemap-chapters-"));assertFalse(manifest.contains("/sitemaps/"));for(Path file:values){if(!file.toString().endsWith(".gz"))continue;try(var in=new GZIPInputStream(Files.newInputStream(file))){byte[] body=in.readAllBytes();String xml=new String(body,java.nio.charset.StandardCharsets.UTF_8);assertTrue(body.length<=2048);assertTrue(xml.split("<url>",-1).length-1<=2);assertFalse(xml.contains("<lastmod>"));assertFalse(xml.contains("bookclass.html"));assertFalse(xml.contains("book_ranking.html"));}}}
    }
    @Test void activeCoreLeaseSkipsAllFileAndDatabaseEnumeration()throws Exception{
        var r=mock(SeoRepository.class);var s=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());when(s.siteIndexEnabled()).thenReturn(true);when(r.coreTicket()).thenReturn(null);
        assertFalse(new SeoCoreSnapshotBuilder(r,s,p).build());verify(r,never()).publicBooks();verify(r,never()).publicCategories();verify(r,never()).completeCore(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        try(var files=Files.list(root)){assertEquals(List.of(SeoSnapshotFiles.LOCK_FILE),files.map(x->x.getFileName().toString()).toList());}
    }
    @Test void failureBeforeWritingMapStillRecordsDurationAndRetainsTheClaimIdentity()throws Exception{
        var r=mock(SeoRepository.class);var s=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());when(s.siteIndexEnabled()).thenReturn(true);var ticket=new SeoRepository.CoreTicket(4,2,1);when(r.coreTicket()).thenReturn(ticket);when(r.settings()).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class,()->new SeoCoreSnapshotBuilder(r,s,p).build());verify(r).failedCore(eq(ticket),contains("IllegalStateException"),longThat(value->value>=0));
    }
    @Test void identicalXmlReusesIndexAndCoreFilesButChangedXmlGetsANewName()throws Exception{
        var r=mock(SeoRepository.class);var s=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());
        when(s.siteIndexEnabled()).thenReturn(true);when(s.publicSiteUrl()).thenReturn("https://xs001.xyz");when(s.catalogPageSize()).thenReturn(200);
        var ticket=new SeoRepository.CoreTicket(4,2,1);when(r.coreTicket()).thenReturn(ticket);when(r.settings()).thenReturn(new SeoSiteSettings(4,true,true,true,"","","","",""));
        when(r.completeCore(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong())).thenReturn(true);
        var builder=new SeoCoreSnapshotBuilder(r,s,p);assertTrue(builder.build());assertTrue(builder.build());
        try(var files=Files.list(root)){assertEquals(2,files.filter(x->!x.getFileName().toString().startsWith(".")).count());}
        when(r.publishedStates(4,"https://xs001.xyz")).thenReturn(List.of(Map.of("builtFiles","chapters-7-r1-e4-p1-11111111-1111-1111-1111-111111111111.xml.gz")));
        assertTrue(builder.build());try(var files=Files.list(root)){assertEquals(2,files.filter(x->x.getFileName().toString().startsWith("index-")).count());}
    }
    @Test void corruptImmutableIndexCannotBeReusedOrPublished()throws Exception{
        var r=mock(SeoRepository.class);var s=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(root.toString());
        when(s.siteIndexEnabled()).thenReturn(true);when(s.publicSiteUrl()).thenReturn("https://xs001.xyz");when(s.catalogPageSize()).thenReturn(200);
        when(r.coreTicket()).thenReturn(new SeoRepository.CoreTicket(4,2,1));when(r.settings()).thenReturn(new SeoSiteSettings(4,true,true,true,"","","","",""));
        var builder=new SeoCoreSnapshotBuilder(r,s,p);builder.build();Path index;try(var files=Files.list(root)){index=files.filter(x->x.getFileName().toString().startsWith("index-")).findFirst().orElseThrow();}
        Files.writeString(index,"corrupt");clearInvocations(r);assertThrows(java.io.IOException.class,builder::build);
        verify(r,never()).completeCore(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());assertEquals("corrupt",Files.readString(index));
    }
}
