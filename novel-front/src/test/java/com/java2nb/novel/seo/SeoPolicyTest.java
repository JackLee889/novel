package com.java2nb.novel.seo;

import com.java2nb.novel.entity.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeoPolicyTest {
    @Test void untrustedOriginsAreRejectedAndNoRequestHostIsUsed() {
        assertEquals("https://xs001.xyz",SeoValidation.publicOrigin("https://XS001.xyz/"));
        for(String bad:new String[]{"http://xs001.xyz","https://user:pass@xs001.xyz","https://xs001.xyz/path","https://xs001.xyz/?url=evil","https://localhost"})assertThrows(IllegalArgumentException.class,()->SeoValidation.publicOrigin(bad));
        assertThrows(IllegalArgumentException.class,()->SeoValidation.verification("<script>alert(1)</script>"));
    }
    @Test void overridesNeverPublishHiddenOrPaidContent() {
        var p=new SeoProperties();p.setEnabled(true);p.setSiteUrl("https://xs001.xyz");var r=mock(SeoRepository.class);var seo=new SeoService(p,r);
        when(r.settings()).thenReturn(new SeoSiteSettings(1,true,true,true,"","","","",""));when(r.bookOverride(1)).thenReturn(new SeoBookOverride(1,true,"","",""));
        Book b=new Book();b.setId(1L);b.setWordCount(20);b.setLastIndexId(10L);b.setStatus((byte)0);assertFalse(seo.isIndexable(b));
        b.setStatus((byte)1);assertTrue(seo.isIndexable(b));BookIndex c=new BookIndex();c.setBookId(1L);c.setWordCount(20);c.setIsVip((byte)1);assertFalse(seo.chapterIndexable(b,c));
        c.setIsVip((byte)0);assertTrue(seo.chapterIndexable(b,c));
        when(r.bookOverride(1)).thenReturn(new SeoBookOverride(1,false,"","",""));assertFalse(seo.chapterIndexable(b,c));
    }
    @Test void chapterIndexAndMapFlagsAreIndependentAndDisabledByDefault() {
        assertFalse(SeoSiteSettings.disabled().chaptersIndexEnabled());assertFalse(SeoSiteSettings.disabled().chapterSitemapEnabled());
        var p=new SeoProperties();var r=mock(SeoRepository.class);var seo=new SeoService(p,r);assertFalse(seo.siteIndexEnabled());verifyNoInteractions(r);
    }
}
