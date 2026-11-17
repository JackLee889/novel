package com.java2nb.novel.seo;
import com.java2nb.novel.core.filter.NovelFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SeoFilterTest {
    @Test void mapsAndRobotsNeverCreateReaderCookiesOrConsultRedis()throws Exception {
        var filter=new NovelFilter();for(String url:new String[]{"/robots.txt","/sitemap.xml","/sitemap-core-1.xml","/sitemaps/core-test.xml.gz"}){
            var request=new MockHttpServletRequest("GET",url);var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);filter.doFilter(request,response,chain);verify(chain).doFilter(request,response);assertEquals(0,response.getCookies().length);
        }
    }
}
