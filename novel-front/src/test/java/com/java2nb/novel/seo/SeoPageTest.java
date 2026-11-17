package com.java2nb.novel.seo;

import com.java2nb.novel.controller.*;
import com.java2nb.novel.controller.page.PageController;
import com.java2nb.novel.core.advice.CommonExceptionHandler;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.core.config.*;
import com.java2nb.novel.core.utils.*;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.service.*;
import com.java2nb.novel.vo.*;
import io.github.xxyopen.model.page.PageBean;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC + actual Thymeleaf templates; only business storage is mocked. No service is started. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes=SeoPageTest.Config.class)
@TestPropertySource(properties={"jwt.expiration=604800","jwt.secret=seo-page-fixture-key-not-for-production-20261008"})
class SeoPageTest {
    @Configuration @EnableWebMvc
    @Import({PageController.class,ReaderController.class,SeoMetadataService.class,CommonExceptionHandler.class,SpringUtil.class})
    static class Config {
        @Bean BookService books(){return mock(BookService.class);} @Bean ReaderChapterService chapters(){return mock(ReaderChapterService.class);}
        @Bean ReaderProperties reader(){return new ReaderProperties();} @Bean WebsiteProperties website(){var w=new WebsiteProperties();w.setName("测试书城");w.setKeyword("小说,阅读");return w;}
        @Bean SeoService seo(){return mock(SeoService.class);} @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean NewsService news(){return mock(NewsService.class);} @Bean AuthorService author(){return mock(AuthorService.class);} @Bean UserService user(){return mock(UserService.class);}
        @Bean JwtTokenUtil tokens(){return mock(JwtTokenUtil.class);} @Bean CacheService cache(){return mock(CacheService.class);}
        @Bean(destroyMethod="shutdown") ThreadPoolExecutor executor(){return new ThreadPoolExecutor(2,2,1,TimeUnit.SECONDS,new LinkedBlockingQueue<>());}
        @Bean ThymeleafViewResolver views(ApplicationContext context){var resolver=new SpringResourceTemplateResolver();resolver.setApplicationContext(context);resolver.setPrefix("classpath:/templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");resolver.setTemplateMode("HTML");resolver.setCacheable(false);var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);var views=new ThymeleafViewResolver();views.setTemplateEngine(engine);views.setCharacterEncoding("UTF-8");return views;}
    }
    @Autowired WebApplicationContext context; @Autowired BookService books; @Autowired ReaderChapterService chapters; @Autowired SeoService policy;
    @Autowired ServletContext servlet; @Autowired SeoMetadataService metadata; @Autowired ReaderProperties reader;
    MockMvc mvc;Book book;BookIndex chapter;
    @BeforeEach void setup(){
        reset(books,chapters,policy);reader.setEnabled(true);ThreadLocalUtil.setTemplateDir("");
        servlet.setAttribute("website",Map.of("name","测试书城","keyword","小说,阅读","description","阅读小说","logo","/images/logo.png","domain","fixture.invalid"));
        when(policy.publicSiteUrl()).thenReturn("https://xs001.xyz");when(policy.siteIndexEnabled()).thenReturn(true);
        when(policy.settings()).thenReturn(new SeoSiteSettings(1,true,true,true,"","","小说,阅读","",""));
        when(policy.bookOverride(anyLong())).thenAnswer(call->SeoBookOverride.inherited(call.getArgument(0)));
        when(policy.isIndexable(any())).thenReturn(true);when(policy.chapterIndexable(any(),any())).thenReturn(true);
        book=new Book();book.setId(9007199254740993L);book.setBookName("雨港手记");book.setAuthorName("原创作者");book.setCatId(1);book.setCatName("都市");book.setBookDesc("<p>雨港的原创故事。</p>");book.setStatus((byte)1);book.setWordCount(30000);book.setLastIndexId(9007199254741001L);book.setLastIndexName("第一章");book.setBookStatus((byte)0);
        chapter=new BookIndex();chapter.setId(9007199254741001L);chapter.setBookId(book.getId());chapter.setIndexName("第一章 灯塔");chapter.setIsVip((byte)0);chapter.setWordCount(200);
        when(books.queryBookDetail(book.getId())).thenReturn(book);when(books.queryIndexCount(book.getId())).thenReturn(401L);
        when(books.queryIndexList(eq(book.getId()),eq("index_num"),anyInt(),eq(200))).thenReturn(List.of(chapter));
        var category=new BookCategory();category.setId(1);category.setName("都市");category.setWorkDirection((byte)0);when(books.listBookCategory()).thenReturn(List.of(category));
        when(books.searchByPage(any(),anyInt(),eq(12))).thenAnswer(call->PageBean.of(((Integer)call.getArgument(1)).longValue(),12,30,List.of(book)));
        when(books.listRank(anyByte(),eq(30))).thenReturn(List.of(book));when(books.listBookSettingVO()).thenReturn(Map.of());when(books.listClickRank()).thenReturn(List.of(book));when(books.listUpdateRank()).thenReturn(List.of());
        doReturn(PageBean.of(1,8,1,List.of(book))).when(books).searchByPage(any(),eq(1),eq(8));
        when(books.listRecBookByCatId(any())).thenReturn(List.of());when(books.listCommentByPage(isNull(),anyLong(),eq(1),eq(5))).thenReturn(PageBean.of(1,5,0,List.of()));
        var view=new ReaderChapterVO(book.getId().toString(),chapter.getId().toString(),book.getBookName(),chapter.getIndexName(),0,0,null,null,"v1","allowed","anonymous",List.of(new ReaderChapterVO.Paragraph("p0","雨港清晨，这是公开的原创正文。")));
        when(chapters.read(book.getId(),chapter.getId(),null)).thenReturn(new ReaderChapterService.ChapterAccess(book,chapter,new BookContent(),view));
        mvc=MockMvcBuilders.webAppContextSetup(context).build();
    }
    @Test void homeAndRanksExposeBooksWithoutJavaScript() throws Exception {
        when(books.listBookSettingVO()).thenReturn(Map.of("0",List.of(),"2",List.of(),"3",List.of()));
        String home=mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertTrue(home.contains("雨港手记"));assertTrue(home.contains("href=\"/book/bookclass.html?c=1\""));
        assertTrue(home.contains("<title>测试书城 - 小说阅读与作品分类</title>"));assertTrue(home.contains("雨港的原创故事。"));assertFalse(home.contains("&lt;p&gt;"));assertFalse(home.contains("正在寻找好故事"));
        String rank=mvc.perform(get("/book/book_ranking.html").queryParam("type","2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertTrue(rank.contains("更新榜单"));assertTrue(rank.contains("href=\"/book/9007199254740993.html\""));assertFalse(rank.contains("正在加载…"));
        assertFalse(rank.contains("&lt;p&gt;"));
        mvc.perform(get("/index.html")).andExpect(status().isMovedPermanently()).andExpect(header().string("Location","/"));
    }
    @Test void catalogHasRealPaginationAndSelfCanonical() throws Exception {
        String html=mvc.perform(get("/book/bookclass.html").queryParam("c","1").queryParam("page","2")).andExpect(status().isOk()).andExpect(header().string("X-Robots-Tag","index,follow")).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("https://xs001.xyz/book/bookclass.html?c=1&amp;page=2"));assertTrue(html.contains("雨港手记"));assertTrue(html.contains("href=\"/book/bookclass.html?c=1&amp;page=3\""));assertTrue(html.contains("第2页"));
        mvc.perform(get("/book/bookclass.html").queryParam("k","灯塔")).andExpect(status().isOk()).andExpect(header().string("X-Robots-Tag","noindex,follow"));
    }
    @Test void aliasesNormalizeButConflictsAndInvalidPagesFail() throws Exception {
        mvc.perform(get("/book/bookclass.html").queryParam("catId","1").queryParam("page","1")).andExpect(status().isMovedPermanently()).andExpect(header().string("Location","/book/bookclass.html?c=1"));
        mvc.perform(get("/book/bookclass.html").queryParam("c","1").queryParam("catId","2")).andExpect(status().isBadRequest());
        mvc.perform(get("/book/bookclass.html").queryParam("page","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/book/bookclass.html").queryParam("page","8")).andExpect(status().isNotFound());
        mvc.perform(get("/book/book_ranking.html").queryParam("type","0")).andExpect(status().isMovedPermanently()).andExpect(header().string("Location","/book/book_ranking.html"));
        mvc.perform(get("/book/not-a-number.html")).andExpect(status().isBadRequest());
    }
    @Test void catalogOfChaptersIsBoundedAndKeepsEveryPageCanonical() throws Exception {
        String path="/book/indexList-"+book.getId()+".html";
        String html=mvc.perform(get(path).queryParam("page","2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("https://xs001.xyz"+path+"?page=2"));assertTrue(html.contains("href=\""+path+"?page=3\""));assertTrue(html.contains("401"));assertTrue(html.contains("第一章 灯塔"));
        verify(books).queryIndexList(book.getId(),"index_num",2,200);verify(books,never()).queryIndexList(anyLong(),isNull(),any(),isNull());
        mvc.perform(get(path).queryParam("page","4")).andExpect(status().isNotFound());
    }
    @Test void chapterBootstrapAndAjaxCarryIdenticalPublicMetadata() throws Exception {
        String path="/book/"+book.getId()+"/"+chapter.getId()+".html";
        var result=mvc.perform(get(path)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store")).andReturn();
        String html=result.getResponse().getContentAsString();assertTrue(html.contains("雨港清晨，这是公开的原创正文。"));assertTrue(html.contains("https://xs001.xyz"+path));assertTrue(html.contains("name=\"keywords\""));
        mvc.perform(get("/book/reader/chapter").param("bookId",book.getId().toString()).param("bookIndexId",chapter.getId().toString()))
            .andExpect(status().isOk()).andExpect(header().string("X-Robots-Tag","noindex,follow")).andExpect(jsonPath("$.data.bookIndexId").value(chapter.getId().toString())).andExpect(jsonPath("$.data.seo.canonical").value("https://xs001.xyz"+path)).andExpect(jsonPath("$.data.seo.robots").value("index,follow"));
    }
    @Test void vipMetadataNeverContainsPurchasedText() {
        chapter.setIsVip((byte)1);
        var view=new ReaderChapterVO("1","2","书","付费章",0,5,null,null,"v","allowed","9",List.of(new ReaderChapterVO.Paragraph("p0","不可出现在公开SEO的付费私有正文")));
        var result=metadata.chapter(book,chapter,view);assertEquals("noindex,follow",result.robots());assertFalse(result.description().contains("付费私有正文"));assertFalse(result.jsonLd().contains("付费私有正文"));
    }
    @Test void missingBookAndStorageFailurePreserveHttpStatusAndHtml() throws Exception {
        when(books.queryBookDetail(99L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/book/99.html")).andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("text/html"));
        when(chapters.read(book.getId(),chapter.getId(),null)).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        mvc.perform(get("/book/"+book.getId()+"/"+chapter.getId()+".html")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","60")).andExpect(header().string("X-Robots-Tag","noindex,follow"));
        mvc.perform(get("/book/reader/chapter").param("bookId",book.getId().toString()).param("bookIndexId",chapter.getId().toString())).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","60")).andExpect(header().string("X-Robots-Tag","noindex,follow"));
    }
    @Test void jsonLdUsesRealFieldsAndCannotCloseScriptTag() {
        book.setBookName("</script><script>alert(1)</script>书");var result=metadata.book(book);
        assertFalse(result.jsonLd().contains("<"));var tree=new ObjectMapper().readTree(result.jsonLd());assertEquals("https://schema.org",tree.get("@context").asText());assertFalse(result.jsonLd().contains("aggregateRating"));assertFalse(result.jsonLd().contains("isbn"));
        when(policy.publicSiteUrl()).thenReturn("");assertEquals("",metadata.book(book).canonical());assertEquals("noindex,follow",metadata.book(book).robots());
    }
    @Test void administrationPreviewRulesMatchPageMetadataWhileJsonLdDescribesVisibleBook() {
        var override=new SeoBookOverride(book.getId(),null,"管理员标题","管理员摘要","管理员关键词");
        when(policy.bookOverride(book.getId())).thenReturn(override);var result=metadata.book(book);
        assertEquals(SeoText.bookTitle(book,override,"测试书城"),result.title());
        assertEquals(SeoText.bookDescription(book,override),result.description());assertEquals(SeoText.bookKeywords(book,override),result.keywords());
        var structured=new ObjectMapper().readTree(result.jsonLd()).get("@graph").get(0);
        assertEquals("雨港的原创故事。",structured.get("description").asText());assertFalse(result.jsonLd().contains("AudioBook"));
        var inherited=SeoBookOverride.inherited(book.getId());when(policy.bookOverride(book.getId())).thenReturn(inherited);result=metadata.book(book);
        assertEquals(SeoText.bookTitle(book,inherited,"测试书城"),result.title());assertEquals("雨港手记,原创作者,都市,小说阅读",result.keywords());
    }
    @Test void legacyReaderStillRendersAuthorizedTextWithoutJavaScript() throws Exception {
        reader.setEnabled(false);
        String html=mvc.perform(get("/book/"+book.getId()+"/"+chapter.getId()+".html")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store")).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("雨港清晨，这是公开的原创正文。"));assertTrue(html.contains("id=\"classic-content\""));assertFalse(html.contains("&lt;p&gt;"));
        assertTrue(html.contains("https://xs001.xyz/book/"+book.getId()+"/"+chapter.getId()+".html"));
    }
}
