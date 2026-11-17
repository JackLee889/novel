package com.java2nb.novel.seo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeoSitemapControllerTest {
    @TempDir Path directory;
    @Test void robotsAndXmlSupportHeadAndConditionalGetsWithoutStartingJobs()throws Exception {
        var r=mock(SeoRepository.class);var s=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(directory.toString());when(s.siteIndexEnabled()).thenReturn(true);when(s.publicSiteUrl()).thenReturn("https://xs001.xyz");when(s.settings()).thenReturn(new SeoSiteSettings(1,true,false,false,"","","","",""));when(s.catalogPageSize()).thenReturn(200);
        var c=new SeoSitemapController(s,r,p);var get=new MockHttpServletRequest("GET","/robots.txt");get.addHeader("Host","evil.invalid");var response=new MockHttpServletResponse();c.robots(get,response);
        assertTrue(response.getContentAsString().contains("https://xs001.xyz/sitemap.xml"));assertFalse(response.getContentAsString().contains("evil.invalid"));
        var head=new MockHttpServletRequest("HEAD","/robots.txt");var h=new MockHttpServletResponse();c.robots(head,h);assertEquals(0,h.getContentAsByteArray().length);assertEquals(response.getHeader("Content-Length"),h.getHeader("Content-Length"));
        var conditional=new MockHttpServletRequest("GET","/robots.txt");conditional.addHeader("If-None-Match",response.getHeader("ETag"));var unchanged=new MockHttpServletResponse();c.robots(conditional,unchanged);assertEquals(304,unchanged.getStatus());
        Files.writeString(directory.resolve("index-test.xml"),"<sitemapindex><sitemap><loc>https://xs001.xyz/core.xml.gz</loc></sitemap></sitemapindex>");
        when(r.coreState()).thenReturn(Map.of("builtEpoch",1L,"builtOrigin","https://xs001.xyz","visibilityRevision",1L,"builtVisibilityRevision",1L,"indexFile","index-test.xml","coreFiles","core-test.xml.gz"));
        var xml=new MockHttpServletResponse();c.index(new MockHttpServletRequest("GET","/sitemap.xml"),xml);assertTrue(xml.getContentAsString().contains("core.xml.gz"));assertNotNull(xml.getHeader("Last-Modified"));
        var since=new MockHttpServletRequest("GET","/sitemap.xml");since.addHeader("If-Modified-Since",xml.getHeader("Last-Modified"));var fresh=new MockHttpServletResponse();c.index(since,fresh);assertEquals(304,fresh.getStatus());
        var headXml=new MockHttpServletResponse();c.index(new MockHttpServletRequest("HEAD","/sitemap.xml"),headXml);assertEquals(0,headXml.getContentAsByteArray().length);assertEquals(xml.getHeader("Content-Length"),headXml.getHeader("Content-Length"));
        verify(r,never()).enqueueAll();verify(r,never()).enqueueBook(anyLong());verify(r,never()).claim();verify(r,never()).publicBooks();verify(r,never()).publicCategories();
    }
    @Test void initialSnapshotReturns503AndVisibilityRevisionImmediatelyRevokesOldSnapshot()throws Exception {
        var r=mock(SeoRepository.class);var s=mock(SeoService.class);when(s.siteIndexEnabled()).thenReturn(true);when(s.publicSiteUrl()).thenReturn("https://xs001.xyz");when(s.settings()).thenReturn(new SeoSiteSettings(1,true,true,true,"","","","",""));
        var c=new SeoSitemapController(s,r,new SeoProperties());when(r.coreState()).thenReturn(Map.of("builtEpoch",0L));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->c.index(new MockHttpServletRequest(),new MockHttpServletResponse())).getStatusCode().value());
        when(r.coreState()).thenReturn(Map.of("builtEpoch",1L,"builtOrigin","https://xs001.xyz","visibilityRevision",2L,"builtVisibilityRevision",1L,"indexFile","index-test.xml"));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->c.index(new MockHttpServletRequest(),new MockHttpServletResponse())).getStatusCode().value());
    }
    @Test void disabledSitesAndUnpublishedOrTraversalPartsAreNotServed()throws Exception {
        var r=mock(SeoRepository.class);var s=mock(SeoService.class);var c=new SeoSitemapController(s,r,new SeoProperties());var out=new MockHttpServletResponse();c.robots(new MockHttpServletRequest("GET","/robots.txt"),out);assertTrue(out.getContentAsString().contains("Disallow: /"));
        assertThrows(ResponseStatusException.class,()->c.index(new MockHttpServletRequest(),new MockHttpServletResponse()));
        when(s.siteIndexEnabled()).thenReturn(true);when(s.settings()).thenReturn(new SeoSiteSettings(1,true,true,true,"","","","",""));
        assertThrows(ResponseStatusException.class,()->c.part("../secret",new MockHttpServletRequest(),new MockHttpServletResponse()));
        assertThrows(ResponseStatusException.class,()->c.part("chapters-7-r1-e1-p1.xml.gz",new MockHttpServletRequest(),new MockHttpServletResponse()));
    }
    @Test void immutablePartsAreServedFromRootScopeWithHeadAndValidators()throws Exception {
        var r=mock(SeoRepository.class);var s=mock(SeoService.class);var p=new SeoProperties();p.setSitemapDirectory(directory.toString());when(s.siteIndexEnabled()).thenReturn(true);when(s.publicSiteUrl()).thenReturn("https://xs001.xyz");when(s.settings()).thenReturn(new SeoSiteSettings(1,true,true,true,"","","","",""));
        String file="chapters-7-r1-e1-p1-11111111-1111-1111-1111-111111111111.xml.gz";Files.write(directory.resolve(file),new byte[]{1,2,3});when(r.filePublished(file,1,"https://xs001.xyz")).thenReturn(true);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new SeoSitemapController(s,r,p)).build();String path="/sitemap-"+file;
        var get=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andReturn().getResponse();assertEquals(200,get.getStatus());assertArrayEquals(new byte[]{1,2,3},get.getContentAsByteArray());
        var head=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(path)).andReturn().getResponse();assertEquals(200,head.getStatus());assertEquals(0,head.getContentAsByteArray().length);assertEquals("3",head.getHeader("Content-Length"));
        assertEquals(304,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("If-None-Match",get.getHeader("ETag"))).andReturn().getResponse().getStatus());
    }
}
