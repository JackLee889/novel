package com.java2nb.novel.service;

import com.java2nb.novel.seo.SeoBookOverride;
import com.java2nb.novel.seo.SeoRepository;
import com.java2nb.novel.seo.SeoSiteSettings;
import com.java2nb.novel.seo.SeoHttpStatsRepository;
import com.java2nb.novel.entity.Book;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeoAdminServiceTest {
    SeoRepository repo;
    SeoPageInspector inspector;
    SeoAdminService service;
    @BeforeEach void setup() {
        repo = mock(SeoRepository.class); inspector = mock(SeoPageInspector.class);
        service = new SeoAdminService(repo, inspector, "https://xs001.xyz");
        when(repo.settings()).thenReturn(new SeoSiteSettings(7,false,false,false,"","","","",""));
        when(repo.bookPreview(42L,null)).thenReturn(Map.of("bookId","42","revision",3L,"excludedReason","unpublished"));
        when(repo.state(42L)).thenReturn(Map.of("status","pending"));
    }
    @Test void settingsPreserveExpectedEpochActorAndIndependentChapterFlags() {
        service.updateConfig(new SeoAdminService.ConfigChange("7",true,false,true," 标题 ","摘要","词","abc-12","token"),91L);
        ArgumentCaptor<SeoSiteSettings> saved=ArgumentCaptor.forClass(SeoSiteSettings.class);
        verify(repo).updateSettings(saved.capture(),eq(91L));
        assertEquals(7,saved.getValue().epoch()); assertTrue(saved.getValue().indexEnabled());
        assertFalse(saved.getValue().chaptersIndexEnabled()); assertTrue(saved.getValue().chapterSitemapEnabled());
        assertEquals("标题",saved.getValue().defaultTitle());
    }
    @Test void invalidOriginHtmlTokensAndMissingFlagsCannotReachWrites() {
        for(String origin: new String[]{"http://xs001.xyz","https://127.0.0.1","https://u:p@xs001.xyz","https://xs001.xyz/path","https://xs001.xyz?x=1"}) {
            SeoAdminService invalid=new SeoAdminService(repo,inspector,origin);
            assertThrows(IllegalArgumentException.class,()->invalid.updateConfig(change("title",""),9));
        }
        assertThrows(IllegalArgumentException.class,()->service.updateConfig(change("<script>",""),9));
        assertThrows(IllegalArgumentException.class,()->service.updateConfig(change("title","<meta>"),9));
        assertThrows(IllegalArgumentException.class,()->service.updateConfig(new SeoAdminService.ConfigChange("0",false,null,false,"","","","",""),9));
        verify(repo,never()).updateSettings(any(),anyLong());
    }
    private SeoAdminService.ConfigChange change(String title,String token) {
        return new SeoAdminService.ConfigChange("0",true,false,false,title,"","",token,"");
    }
    @Test void bookInheritPreservesNullAndRevisionAndExclusion() {
        Map<String,Object> result=service.updateBook(42,new SeoAdminService.BookChange("3","INHERIT","","",""),91);
        verify(repo).saveBookOverride(new SeoBookOverride(42,null,"","",""),3,91);
        assertEquals("unpublished",result.get("excludedReason"));
        assertEquals("https://xs001.xyz/book/42.html",result.get("canonical"));
    }
    @Test void rebuildOnlyEnqueuesAndReturnsJobId() {
        when(repo.enqueueAll()).thenReturn("job-all"); when(repo.enqueueBook(42)).thenReturn("job-book");
        assertEquals("job-all",service.enqueue(new SeoAdminService.Rebuild("all",null)));
        assertEquals("job-book",service.enqueue(new SeoAdminService.Rebuild("book","42")));
        verify(repo).enqueueAll(); verify(repo).enqueueBook(42);
        verifyNoInteractions(inspector);
    }
    @Test void invalidDeploymentOriginOverridesDatabaseIndexPermissionInPreview() {
        when(repo.bookPreview(42L,null)).thenReturn(Map.of("bookId","42","revision",3L,"indexable",true,"excludedReason",""));
        var invalid=new SeoAdminService(repo,inspector,"http://localhost:8083");var preview=invalid.preview(42,null);
        assertEquals(false,preview.get("indexable"));assertEquals("invalid_site_url",preview.get("excludedReason"));
        assertEquals("noindex,follow",preview.get("robots"));assertEquals("",preview.get("canonical"));
    }
    @Test void diagnosisUsesFixedTrustedPathsAndValidatesChapterOwnership() {
        when(repo.bookPreview(42L,9L)).thenReturn(chapterPolicy(true,false,true));
        when(inspector.inspect(any(),any())).thenReturn(Map.of("status",200));
        service.diagnose(new SeoAdminService.Diagnosis("chapter","42","9"));
        var expected=ArgumentCaptor.forClass(SeoPageInspector.ExpectedPage.class);
        verify(inspector).inspect(eq(URI.create("https://xs001.xyz/book/42/9.html")),expected.capture());
        assertTrue(expected.getValue().indexable());assertTrue(expected.getValue().contentExpected());
        assertEquals(200,expected.getValue().status());assertEquals("9",expected.getValue().chapterId());
        assertEquals("https://xs001.xyz/book/42/9.html",service.preview(42,9L).get("canonical"));
        assertThrows(IllegalArgumentException.class,()->service.diagnose(new SeoAdminService.Diagnosis("https://evil.example",null,null)));
        when(repo.bookPreview(42L,10L)).thenThrow(new IllegalArgumentException("Chapter does not exist in this book"));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.diagnose(new SeoAdminService.Diagnosis("chapter","42","10"))).getStatusCode().value());
        verify(inspector,times(1)).inspect(any(),any());
    }
    @Test void diagnosisMakesDatabaseContentAndSiteSwitchPartOfTheListExpectation() {
        when(repo.settings()).thenReturn(new SeoSiteSettings(7,true,false,false,"","","","",""));
        when(inspector.inspect(any(),any())).thenReturn(Map.of("status",200));
        when(repo.publicBookCount(null,null)).thenReturn(0L,2L);
        service.diagnose(new SeoAdminService.Diagnosis("category",null,null));
        service.diagnose(new SeoAdminService.Diagnosis("ranking",null,null));
        var capture=ArgumentCaptor.forClass(SeoPageInspector.ExpectedPage.class);
        verify(inspector,times(2)).inspect(any(),capture.capture());
        assertFalse(capture.getAllValues().get(0).indexable());assertFalse(capture.getAllValues().get(0).contentExpected());
        assertTrue(capture.getAllValues().get(1).indexable());assertTrue(capture.getAllValues().get(1).contentExpected());
    }
    @Test void paidHiddenAndUncheckedBodyHaveMetadataBasedExpectations() {
        when(inspector.inspect(any(),any())).thenReturn(Map.of("status",200));
        when(repo.bookPreview(42L,9L)).thenReturn(chapterPolicy(true,true,false),chapterPolicy(false,false,true),chapterPolicy(true,false,false));
        for(int i=0;i<3;i++)service.diagnose(new SeoAdminService.Diagnosis("chapter","42","9"));
        var capture=ArgumentCaptor.forClass(SeoPageInspector.ExpectedPage.class);
        verify(inspector,times(3)).inspect(any(),capture.capture());
        var paid=capture.getAllValues().get(0);assertFalse(paid.indexable());assertEquals(200,paid.status());assertTrue(paid.paidChapter());assertFalse(paid.contentExpected());
        var hidden=capture.getAllValues().get(1);assertFalse(hidden.indexable());assertEquals(404,hidden.status());
        var unchecked=capture.getAllValues().get(2);assertTrue(unchecked.indexable());assertEquals(200,unchecked.status());
    }
    @Test void disabledChapterIndexStillRequiresAnAvailableFreeSsrBody() {
        var policy=new LinkedHashMap<>(chapterPolicy(true,false,true));policy.put("indexable",false);policy.put("excludedReason","chapter_disabled");
        when(repo.bookPreview(42L,9L)).thenReturn(policy);when(inspector.inspect(any(),any())).thenReturn(Map.of("status",200));
        service.diagnose(new SeoAdminService.Diagnosis("chapter","42","9"));
        var capture=ArgumentCaptor.forClass(SeoPageInspector.ExpectedPage.class);verify(inspector).inspect(any(),capture.capture());
        assertFalse(capture.getValue().indexable());assertTrue(capture.getValue().contentExpected());assertEquals(200,capture.getValue().status());
    }
    @Test void catalogExpectationAndReturnedPolicyUseCatalogCanonical() {
        when(repo.bookPreview(42L,null)).thenReturn(Map.of("bookId","42","status",1,"indexable",true,"bookName","雪山","authorName","作者"));
        when(repo.chapterCount(42)).thenReturn(350L);when(inspector.inspect(any(),any())).thenReturn(Map.of("status",200));
        var result=service.diagnose(new SeoAdminService.Diagnosis("catalog","42",null));
        var capture=ArgumentCaptor.forClass(SeoPageInspector.ExpectedPage.class);
        verify(inspector).inspect(eq(URI.create("https://xs001.xyz/book/indexList-42.html")),capture.capture());
        assertTrue(capture.getValue().contentExpected());
        assertEquals("https://xs001.xyz/book/indexList-42.html",((Map<?,?>)result.get("policy")).get("canonical"));
    }
    @Test void bookExpectationUsesOriginalPlainDescriptionRatherThanMetadataOverride() {
        when(repo.bookPreview(42L,null)).thenReturn(Map.of("bookId","42","status",1,"indexable",true,"bookName","雪山","authorName","作者","resolvedDescription","SEO覆盖摘要"));
        Book book=new Book();book.setId(42L);book.setBookDesc("<p>真实 &amp; 简介</p>");when(repo.book(42)).thenReturn(book);
        when(inspector.inspect(any(),any())).thenReturn(Map.of("status",200));service.diagnose(new SeoAdminService.Diagnosis("book","42",null));
        var capture=ArgumentCaptor.forClass(SeoPageInspector.ExpectedPage.class);verify(inspector).inspect(any(),capture.capture());
        assertEquals("真实 & 简介",capture.getValue().bookDescription());
    }
    @Test void diagnosisCannotUseArbitraryOriginsPathsOrPrecisionLosingIds() {
        for(String type:List.of("https://evil.example/","//evil.example/","/book/42.html","book?url=https://evil.example"))
            assertThrows(IllegalArgumentException.class,()->service.diagnose(new SeoAdminService.Diagnosis(type,"42",null)));
        assertThrows(IllegalArgumentException.class,()->service.diagnose(new SeoAdminService.Diagnosis("book","42/../../",null)));
        assertThrows(IllegalArgumentException.class,()->service.diagnose(new SeoAdminService.Diagnosis("chapter","42","9223372036854775808")));
        verifyNoInteractions(inspector);
    }
    @Test void sitemapOverviewIncludesRealAnonymousHttpAggregates() {
        SeoHttpStatsRepository stats=mock(SeoHttpStatsRepository.class);when(repo.diagnostics()).thenReturn(Map.of("pendingBooks",3));
        when(stats.recentOverview()).thenReturn(Map.of("windowHours",168,"timezone","UTC"));
        var result=new SeoAdminService(repo,inspector,stats,"https://xs001.xyz").sitemaps();
        assertEquals(Map.of("windowHours",168,"timezone","UTC"),result.get("httpStats"));verify(stats).recentOverview();
    }
    private Map<String,Object> chapterPolicy(boolean published,boolean paid,boolean ready) {
        var policy=new LinkedHashMap<String,Object>();policy.put("bookId","42");policy.put("chapterId","9");policy.put("status",published?1:0);
        policy.put("chapterVip",paid?1:0);policy.put("contentChecked",false);policy.put("indexable",published&&!paid);
        policy.put("bookName","雪山");policy.put("authorName","作者");policy.put("indexName","第一章");return policy;
    }
    @Test void snowflakeAndUnicodeValidationNeverRoundOrCutCodepoints() {
        assertEquals(9007199254740993L,SeoAdminService.id("9007199254740993"));
        assertThrows(IllegalArgumentException.class,()->SeoAdminService.id("9223372036854775808"));
        assertEquals("𠮷",SeoAdminService.text("𠮷",1));
        assertThrows(IllegalArgumentException.class,()->SeoAdminService.text("𠮷𠮷",1));
    }
}
