package com.java2nb.novel.reader;

import com.java2nb.novel.controller.*;
import com.java2nb.novel.controller.page.PageController;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.config.*;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.service.*;
import org.springframework.boot.*;
import org.springframework.boot.webmvc.autoconfigure.*;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.HttpEncodingAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import io.github.xxyopen.model.resp.RestResult;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Test classpath ONLY: authentic PageController, Thymeleaf, reader API and TTS.
 * No component scanning, database, Redis, crawler, payment or external AI beans.
 */
@SpringBootConfiguration
@Import({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
    org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,
    org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class,
    ThymeleafAutoConfiguration.class, com.java2nb.novel.seo.SeoMetadataService.class, WebsiteProperties.class, PageController.class, ReaderController.class, ReaderChapterService.class,
    ReaderProperties.class, ReaderResourceConfig.class, ReaderFixtureApplication.FixtureBusiness.class})
public class ReaderFixtureApplication {
    public static final Long BOOK = 9007199254740993L;
    public static final Long FIRST = 9007199254741001L;
    public static final String PAID_TEXT = "付费夹具专属正文，不应出现在未授权响应中。";
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ReaderFixtureApplication.class);
        app.setDefaultProperties(Map.of("server.port","18983","server.address","127.0.0.1",
            "spring.config.name","reader-fixture","spring.thymeleaf.cache","false",
            "logging.level.root","WARN","reader.enabled","true","reader.tts.enabled","true",
            "jwt.secret","reader-fixture-only","jwt.expiration","3600"));
        app.run(args);
    }
    public static List<BookIndex> indices() {
        List<BookIndex> indices = new ArrayList<>();
        String[] names = {"第一章 雨港的清晨","第二章 灯塔来信","第三章 归舟","第四章 长夜手记","第五章 未拆的信"};
        for (int n = 0; n < 5; n++) {
            BookIndex i = new BookIndex(); i.setBookId(BOOK); i.setId(FIRST+n); i.setIndexNum(n);
            i.setIndexName(names[n]); i.setIsVip((byte)(n == 4 ? 1 : 0)); i.setBookPrice(5);
            i.setStorageType("fixture"); i.setWordCount(200); indices.add(i);
        }
        return indices;
    }
    @Bean BookService books() {
        BookService books = mock(BookService.class);
        Book book = new Book(); book.setId(BOOK); book.setStatus((byte)1);book.setBookName("雨港手记"); book.setAuthorName("原创验收夹具");
        when(books.queryBookDetail(BOOK)).thenReturn(book);
        for (var index : indices()) {
            when(books.queryBookIndex(index.getId())).thenReturn(index);
            int n = index.getIndexNum();
            when(books.queryPreBookIndexId(BOOK,n)).thenReturn(n == 0 ? 0L : FIRST+n-1);
            when(books.queryNextBookIndexId(BOOK,n)).thenReturn(n == 4 ? 0L : FIRST+n+1);
        }
        when(books.queryIndexList(eq(BOOK),eq("index_num"),anyInt(),eq(100))).thenAnswer(call -> {
            int page = call.getArgument(2); List<BookIndex> list = new ArrayList<>();
            for (int n=(page-1)*100;n<Math.min(page*100,125);n++) {
                if (n<5) list.add(indices().get(n));
                else { var i=new BookIndex();i.setId(FIRST+n);i.setBookId(BOOK);i.setIndexNum(n);i.setIndexName("目录分页夹具 "+(n+1));list.add(i); }
            }
            return list;
        });
        when(books.queryIndexCount(BOOK)).thenReturn(125L);
        return books;
    }
    @Bean com.java2nb.novel.seo.SeoService seoFixturePolicy(){
        var service=mock(com.java2nb.novel.seo.SeoService.class);
        when(service.publicSiteUrl()).thenReturn("https://fixture.invalid");
        when(service.settings()).thenReturn(com.java2nb.novel.seo.SeoSiteSettings.disabled());
        when(service.isPublic(any())).thenReturn(true);
        when(service.bookOverride(anyLong())).thenAnswer(call->com.java2nb.novel.seo.SeoBookOverride.inherited(call.getArgument(0)));
        return service;
    }
    @Bean UserService users() {
        UserService users = mock(UserService.class);
        when(users.queryIsBuyBookIndex(2L,FIRST+4)).thenReturn(true);
        return users;
    }
    @Bean(name="fixture") BookContentService fixtureContent() {
        BookContentService storage = mock(BookContentService.class);
        String[] contents = {
            "<p>清晨，雨港的街道还没有醒来，陈舟在窗边打开第一封信。</p><p>信里写着：Hello，朋友。下午三点，我们在灯塔见面。</p>",
            "<p>第二天，旧灯塔亮起一盏新灯。陈舟沿着石阶，听见海浪轻轻拍打岸边。</p><p>他把灯塔的照片寄给远方的人，信封上只写了一句：一路平安。</p>",
            "<p>第三天，归舟驶进港口。老朋友带来一篮梨子，笑着说，今年的收成很好。</p><p>陈舟收起三封信，重新打开窗户。故事到这里，才刚刚开始。</p>"
        };
        when(storage.queryBookContent(eq(BOOK),anyLong())).thenAnswer(call -> {
            long id = call.getArgument(1); int n = (int)(id-FIRST); BookContent c = new BookContent();
            if(n<3 && n>=0) c.setContent(contents[n]);
            else if(n==3) {
                StringBuilder longChapter = new StringBuilder();
                for(int p=0;p<60;p++) longChapter.append("<p>第").append(p+1).append("页手记。")
                    .append("夜色落在雨港，窗台上的纸船轻轻摇晃。陈舟记下风向和潮水，也记下邻居送来的茶。")
                    .append("远处的灯塔照着归来的小船，街角的书店仍然亮着灯。这一夜没有急事，只有平静的海声。</p>");
                c.setContent(longChapter.toString());
            } else if(n==4) c.setContent("<p>"+PAID_TEXT+"</p>");
            else return null;
            return c;
        });
        return storage;
    }
    @Bean JwtTokenUtil fixtureTokens() {
        JwtTokenUtil jwt = mock(JwtTokenUtil.class);
        when(jwt.getUserDetailsFromToken(anyString())).thenAnswer(call -> {
            String token = call.getArgument(0);
            if (!List.of("fixture-unpaid","fixture-purchased").contains(token)) return null;
            UserDetails user = new UserDetails(); user.setId(token.equals("fixture-purchased") ? 2L : 1L);
            user.setUsername(token); return user;
        });
        return jwt;
    }
    @Bean NewsService news() { return mock(NewsService.class); }
    @Bean AuthorService authors() { return mock(AuthorService.class); }
    @Bean(destroyMethod="shutdown") ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(1,1,1,TimeUnit.SECONDS,new LinkedBlockingQueue<>());
    }
    @Bean ServletContextInitializer website() {
        return context -> context.setAttribute("website",Map.of("name","小说精品屋","logo","/images/logo.png","logoDark","/images/logo.png"));
    }
    @RestController
    static class FixtureBusiness {
        @PostMapping({"/book/addVisitCount","/user/addReadHistory","/user/addToBookShelf"})
        public RestResult<Void> noop() { return RestResult.ok(); }
        @PostMapping("/user/buyBookIndex")
        public RestResult<Void> disabledPurchase() { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"验收环境禁止购买"); }
        @GetMapping("/reader-fixture")
        public String index() { return "Original fixtures only. Open /book/"+BOOK+"/"+FIRST+".html"; }
    }
}
