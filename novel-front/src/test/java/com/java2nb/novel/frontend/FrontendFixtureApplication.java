package com.java2nb.novel.reader;

import com.java2nb.novel.controller.*;
import com.java2nb.novel.controller.page.PageController;
import com.java2nb.novel.core.advice.CustomResponseBodyAdvice;
import com.java2nb.novel.core.config.*;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.reader.ReaderFixtureApplication;
import com.java2nb.novel.service.*;
import com.java2nb.novel.vo.*;
import io.github.xxyopen.model.page.PageBean;
import io.github.xxyopen.model.resp.RestResult;
import jakarta.servlet.http.*;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.*;
import org.springframework.boot.webmvc.autoconfigure.*;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.HttpEncodingAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Test-classpath-only, loopback preview. Actual page/reader controllers; original fixtures.
 * No production configuration, component scan, database, Redis, crawler, payment or AI beans. */
@SpringBootConfiguration
@Import({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class, HttpEncodingAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
    org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,
    org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class,
    ThymeleafAutoConfiguration.class, CustomResponseBodyAdvice.class, com.java2nb.novel.core.utils.SpringUtil.class,
    com.java2nb.novel.seo.SeoMetadataService.class, WebsiteProperties.class, PageController.class, ReaderController.class, ReaderChapterService.class, ReaderProperties.class,
    ReaderResourceConfig.class, FrontendFixtureApplication.Api.class})
public class FrontendFixtureApplication implements WebMvcConfigurer {
    static final long BOOK = ReaderFixtureApplication.BOOK;
    static final long FIRST = ReaderFixtureApplication.FIRST;
    static final List<Book> BOOKS = makeBooks();
    static final List<Map<String,Object>> WRITES = new CopyOnWriteArrayList<>();
    static final Set<String> SHELF = ConcurrentHashMap.newKeySet();
    static { SHELF.add(String.valueOf(BOOK)); SHELF.add(String.valueOf(BOOK+1)); }
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FrontendFixtureApplication.class);
        app.setDefaultProperties(Map.of("server.port","18984","server.address","127.0.0.1",
            "spring.config.name","frontend-fixture","spring.thymeleaf.cache","false",
            "logging.level.root","WARN","reader.enabled","true","reader.tts.enabled","true",
            "jwt.secret","frontend-fixture-only","jwt.expiration","3600"));
        app.run(args);
    }
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/frontend-fixture/assets/**").addResourceLocations("file:tests/frontend/assets/");
    }
    static List<Book> makeBooks() {
        String[] names={"雨港手记","山海拾光","长街灯未眠","风起北境","星河来信","人间慢行","月落长安","深海回声","旧城新雪","云上有间书店","最后一座灯塔","故园春早","青山如故","时间的旅人","白夜归航","千山之外"};
        String[] desc={"一封从旧灯塔寄来的信，让陈舟重新认识了这座熟悉的雨港。那些关于海风、归途与重逢的故事，在清晨慢慢展开。","沿着山海之间的旧路，拾起散落的风景，也寻找久别的人。","在灯火未眠的长街，一间小小的书店连接着彼此的生活。","漫长的冬天将尽，年轻的旅人从北境出发，踏上未知的路。"};
        List<Book> result=new ArrayList<>();
        for(int n=0;n<names.length;n++) {
            Book b=new Book();b.setId(BOOK+n);b.setBookName(names[n]);b.setAuthorName(new String[]{"南岸","青禾","路遥知","林深"}[n%4]);
            b.setCatId(n%4+1);b.setCatName(new String[]{"都市","仙侠","现代言情","玄幻"}[n%4]);b.setWorkDirection((byte)(n%4==2?1:0));
            b.setBookDesc(desc[n%4]);b.setPicUrl("/frontend-fixture/assets/cover-"+(n%8)+".svg");
            b.setBookStatus((byte)(n%3==1?1:0));b.setStatus((byte)1);b.setIsVip((byte)0);b.setScore(9.2F);
            b.setWordCount(86000+n*21000);b.setVisitCount(18000L+n*7200);b.setCommentCount(12+n);
            b.setLastIndexId(FIRST+n*200+3);b.setLastIndexName("第四章 长夜手记");b.setLastIndexUpdateTime(new Date());b.setCreateTime(new Date());result.add(b);
        }
        return result;
    }
    static <T> PageBean<T> page(List<T> all,int curr,int limit) {
        int start=Math.min(Math.max(0,(curr-1)*limit),all.size());
        return PageBean.of(curr,limit,all.size(),all.subList(start,Math.min(start+limit,all.size())));
    }
    static List<BookIndex> indicesFor(long bookId) {
        if (bookId == BOOK) return ReaderFixtureApplication.indices();
        long first = FIRST + (bookId - BOOK) * 200;
        List<BookIndex> result = new ArrayList<>();
        for (var original : ReaderFixtureApplication.indices()) {
            BookIndex index = new BookIndex();
            BeanUtils.copyProperties(original, index);
            index.setBookId(bookId); index.setId(first + index.getIndexNum()); result.add(index);
        }
        return result;
    }
    static BookCommentVO comment() {
        BookCommentVO c=new BookCommentVO();c.setId(41L);c.setBookId(BOOK);c.setCreateUserName("临窗读者");
        c.setCommentContent("喜欢雨港的清晨。平静的文字里，每一封信都带着不同的温度。");c.setCreateTime(new Date());c.setReplyCount(2);c.setLikesCount(4L);c.setUnLikesCount(0L);return c;
    }
    @Bean BookService books() {
        BookService b=new ReaderFixtureApplication().books();
        for(var book:BOOKS)when(b.queryBookDetail(book.getId())).thenReturn(book);
        for(int n=0;n<BOOKS.size();n++) {
            long bid=BOOK+n,first=FIRST+n*200;
            when(b.queryFirstBookIndexId(bid)).thenReturn(first);
            if(n==0)continue;
            List<BookIndex> indices=new ArrayList<>();
            for(var original:ReaderFixtureApplication.indices()) {
                BookIndex index=new BookIndex();BeanUtils.copyProperties(original,index);index.setBookId(bid);index.setId(first+index.getIndexNum());indices.add(index);
                when(b.queryBookIndex(index.getId())).thenReturn(index);
                int number=index.getIndexNum();when(b.queryPreBookIndexId(bid,number)).thenReturn(number==0?0L:first+number-1);
                when(b.queryNextBookIndexId(bid,number)).thenReturn(number==4?0L:first+number+1);
            }
            when(b.queryIndexList(eq(bid),isNull(),eq(1),isNull())).thenReturn(indices);
            when(b.queryIndexList(eq(bid),eq("index_num"),anyInt(),eq(100))).thenAnswer(call->(int)call.getArgument(2)==1?indices:List.of());
            when(b.queryIndexCount(bid)).thenReturn(5L);
        }
        Map<String,List<BookSettingVO>> settings=new HashMap<>();
        for(int key=0;key<5;key++) {
            List<BookSettingVO> list=new ArrayList<>();
            for(var book:BOOKS.subList(key==0?0:key,Math.min(BOOKS.size(),key+8))) {
                var s=new BookSettingVO();BeanUtils.copyProperties(book,s);s.setBookId(book.getId());list.add(s);
            }
            settings.put(String.valueOf(key),list);
        }
        when(b.listBookSettingVO()).thenReturn(settings);
        var categoryList=new ArrayList<BookCategory>();String[] categoryNames={"都市","仙侠","现代言情","玄幻","科幻","历史","古代言情","悬疑"};
        for(int n=0;n<categoryNames.length;n++){var category=new BookCategory();category.setId(n+1);category.setName(categoryNames[n]);category.setWorkDirection((byte)(n==2||n==6?1:0));categoryList.add(category);}
        when(b.listBookCategory()).thenReturn(categoryList);
        when(b.listClickRank()).thenReturn(BOOKS);
        when(b.listRank(anyByte(),anyInt())).thenReturn(BOOKS);
        var updates=new ArrayList<BookVO>();for(var book:BOOKS){var update=new BookVO();BeanUtils.copyProperties(book,update);updates.add(update);}when(b.listUpdateRank()).thenReturn(updates);
        when(b.searchByPage(any(),anyInt(),anyInt())).thenAnswer(call->{BookSpVO query=call.getArgument(0);List<Book> list=BOOKS.stream().filter(book->query.getKeyword()==null||book.getBookName().contains(query.getKeyword())||book.getAuthorName().contains(query.getKeyword())).filter(book->query.getCatId()==null||Objects.equals(book.getCatId(),query.getCatId())).filter(book->query.getWorkDirection()==null||Objects.equals(book.getWorkDirection(),query.getWorkDirection())).filter(book->query.getBookStatus()==null||Objects.equals(book.getBookStatus(),query.getBookStatus())).toList();return page(list,call.getArgument(1),call.getArgument(2));});
        for(var book:BOOKS)when(b.queryIndexList(eq(book.getId()),eq("index_num"),anyInt(),eq(200))).thenAnswer(call->(int)call.getArgument(2)==1?indicesFor(book.getId()):List.of());
        when(b.listRecBookByCatId(any())).thenReturn(BOOKS.subList(1,7));
        when(b.listCommentByPage(isNull(),anyLong(),anyInt(),anyInt())).thenReturn(page(List.of(comment()),1,5));
        when(b.getBookComment(anyLong())).thenReturn(comment());
        when(b.queryIndexList(eq(BOOK),isNull(),eq(1),isNull())).thenReturn(ReaderFixtureApplication.indices());
        return b;
    }
    @Bean com.java2nb.novel.seo.SeoService seoFixturePolicy(){
        var service=mock(com.java2nb.novel.seo.SeoService.class);
        when(service.publicSiteUrl()).thenReturn("https://fixture.invalid");
        when(service.settings()).thenReturn(com.java2nb.novel.seo.SeoSiteSettings.disabled());
        when(service.isPublic(any())).thenReturn(true);
        when(service.bookOverride(anyLong())).thenAnswer(call->com.java2nb.novel.seo.SeoBookOverride.inherited(call.getArgument(0)));
        return service;
    }
    @Bean UserService users(){ return new ReaderFixtureApplication().users(); }
    @Bean com.java2nb.novel.core.cache.CacheService cache(){return mock(com.java2nb.novel.core.cache.CacheService.class);}
    @Bean org.springframework.web.filter.OncePerRequestFilter templatePrefix(){
        return new org.springframework.web.filter.OncePerRequestFilter(){
            @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,jakarta.servlet.FilterChain chain) throws java.io.IOException,jakarta.servlet.ServletException {
                String ua=Objects.toString(r.getHeader("User-Agent"),"");
                com.java2nb.novel.core.utils.ThreadLocalUtil.setTemplateDir(ua.matches(".*(Android|iPhone|Mobile).*" )?"mobile/":"");
                try{chain.doFilter(r,s);}finally{com.java2nb.novel.core.utils.ThreadLocalUtil.setTemplateDir("");}
            }
        };
    }
    @Bean(name="fixture") BookContentService content(){var storage=new ReaderFixtureApplication().fixtureContent();for(int n=1;n<BOOKS.size();n++){final int number=n;when(storage.queryBookContent(eq(BOOK+n),anyLong())).thenAnswer(call->{var c=new BookContent();c.setContent("<p>"+BOOKS.get(number).getBookName()+"，是一段用于本地预览的原创故事。</p><p>清晨的风从窗边吹过。旅人翻开手中的书，沿着文字开始新的旅程。</p>");return c;});}return storage;}
    @Bean JwtTokenUtil tokens(){return new ReaderFixtureApplication().fixtureTokens();}
    @Bean AuthorService authors(){var a=mock(AuthorService.class);when(a.isAuthor(anyLong())).thenReturn(true);when(a.register(anyLong(),any())).thenReturn("请核对作者邀请码（本地演示）。");return a;}
    @Bean NewsService news(){var n=mock(NewsService.class);News item=new News();item.setId(31L);item.setTitle("阅读与创作，从一页开始");item.setCatName("站内公告");item.setCreateTime(new Date());item.setContent("<p>这是独立本地预览的原创公告，用于检查公告正文排版。</p><p>所有演示书籍、账户和操作记录均为测试数据。</p>");when(n.listIndexNews()).thenReturn(List.of(item));when(n.queryNewsInfo(anyLong())).thenReturn(item);return n;}
    @Bean(destroyMethod="shutdown") ThreadPoolExecutor executor(){return new ThreadPoolExecutor(2,2,1,TimeUnit.SECONDS,new LinkedBlockingQueue<>());}
    @Bean ServletContextInitializer website(){return c->c.setAttribute("website",Map.of("name","小说精品屋","logo","/images/logo.png","logoDark","/images/logo_white.png","domain","127.0.0.1","keyword","小说,原创文学","description","发现好故事，享受阅读。"));}
    @RestController static class Api {
        static boolean logged(HttpServletRequest r){return r.getCookies()!=null&&Arrays.stream(r.getCookies()).anyMatch(c->c.getName().equals("Authorization")&&c.getValue().startsWith("fixture-"));}
        static RestResult<?> ok(Object data){return RestResult.ok(data);}
        static RestResult<?> unauth(){return RestResult.fail(com.java2nb.novel.core.enums.ResponseStatus.NO_LOGIN);}
        @RequestMapping({"/user/login","/user/register","/user/refreshToken"}) RestResult<?> login(HttpServletRequest r){return logged(r)||!r.getRequestURI().endsWith("refreshToken")?ok(Map.of("token","fixture-purchased","nickName","临窗读者","username","13800000000")):unauth();}
        @GetMapping("/user/userInfo") RestResult<?> info(HttpServletRequest r){return logged(r)?ok(Map.of("nickName","临窗读者","username","13800000000","accountBalance",3200,"userSex",0,"userPhoto","/images/man.png")):unauth();}
        @GetMapping("/book/listBookCategory") RestResult<?> cats(){List<Map<String,Object>> list=new ArrayList<>();String[] names={"都市","仙侠","现代言情","玄幻","科幻","历史","古代言情","悬疑"};for(int n=0;n<8;n++)list.add(Map.of("id",n+1,"name",names[n],"workDirection",n==2||n==6?1:0));return ok(list);}
        @GetMapping("/book/searchByPage") RestResult<?> search(@RequestParam Map<String,String> p){List<Book> list=BOOKS.stream().filter(b->!p.containsKey("keyword")||b.getBookName().contains(p.get("keyword"))||b.getAuthorName().contains(p.get("keyword"))).filter(b->!p.containsKey("catId")||String.valueOf(b.getCatId()).equals(p.get("catId"))).filter(b->!p.containsKey("workDirection")||String.valueOf(b.getWorkDirection()).equals(p.get("workDirection"))).filter(b->!p.containsKey("bookStatus")||String.valueOf(b.getBookStatus()).equals(p.get("bookStatus"))).toList();return ok(page(list,Integer.parseInt(p.getOrDefault("curr","1")),Integer.parseInt(p.getOrDefault("limit","12"))));}
        @GetMapping({"/book/listClickRank","/book/listNewRank","/book/listUpdateRank","/book/listRank"}) RestResult<?> ranks(){return ok(BOOKS);}
        @GetMapping("/book/queryBookDetail/{id}") RestResult<?> detail(@PathVariable long id){return ok(BOOKS.stream().filter(b->b.getId()==id).findFirst().orElse(BOOKS.get(0)));}
        @GetMapping("/book/queryIndexList") RestResult<?> indices(@RequestParam(defaultValue="9007199254740993") long bookId,@RequestParam(defaultValue="1") int curr,@RequestParam(defaultValue="20") int limit){return ok(page(indicesFor(bookId),curr,limit));}
        @GetMapping("/book/queryNewIndexList") RestResult<?> newIndices(@RequestParam(defaultValue="9007199254740993") long bookId){return ok(indicesFor(bookId));}
        @GetMapping("/book/listCommentByPage") RestResult<?> comments(){return ok(page(List.of(comment()),1,20));}
        @GetMapping("/book/listCommentReplyByPage") RestResult<?> replies(){return ok(page(List.of(Map.of("id","42","createUserName","海边的风","replyContent","读完这一章，想去看看那座灯塔。","createTime","刚刚","likesCount",2,"unLikesCount",0)),1,20));}
        @GetMapping({"/user/listBookShelfByPage","/user/listReadHistoryByPage"}) RestResult<?> shelf(HttpServletRequest r,@RequestParam(defaultValue="1") int curr,@RequestParam(defaultValue="12") int limit){if(!logged(r))return unauth();List<Map<String,Object>> list=new ArrayList<>();for(var b:BOOKS){if(!SHELF.contains(b.getId().toString()))continue;Map<String,Object> m=new LinkedHashMap<>();m.put("bookId",b.getId().toString());m.put("bookName",b.getBookName());m.put("picUrl",b.getPicUrl());m.put("authorName",b.getAuthorName());m.put("catId",b.getCatId());m.put("catName",b.getCatName());m.put("lastIndexId",b.getLastIndexId());m.put("lastIndexName",b.getLastIndexName());m.put("lastIndexUpdateTime","10/06 18:30");m.put("preContentId",FIRST+(b.getId()-BOOK)*200);list.add(m);}return ok(page(list,curr,limit));}
        @GetMapping("/user/queryIsInShelf") RestResult<?> inShelf(@RequestParam String bookId,HttpServletRequest r){return logged(r)?ok(SHELF.contains(bookId)):unauth();}
        @GetMapping("/user/listCommentByPage") RestResult<?> myComments(HttpServletRequest r){return logged(r)?comments():unauth();}
        @GetMapping("/user/listUserFeedBackByPage") RestResult<?> feedback(HttpServletRequest r){return logged(r)?ok(page(List.of(Map.of("content","希望增加更多完结作品推荐。","createTime","2026-10-06","replyContent","感谢你的建议，我们会继续完善推荐。")),1,10)):unauth();}
        @GetMapping("/author/listBookByPage") RestResult<?> authorBooks(){return ok(page(BOOKS.subList(0,2),1,12));}
        @GetMapping("/author/checkPenName") RestResult<?> name(){return ok(false);}
        @GetMapping("/author/queryIndexContent/{id}") RestResult<?> chapter(){return ok("清晨，雨港的街道还没有醒来。\n陈舟在窗边打开第一封信。");}
        @GetMapping("/author/queryAiGenPic") RestResult<?> cover(){return ok("");}
        @GetMapping("/author/listIncomeMonthByPage") RestResult<?> months(){return ok(page(List.of(Map.of("incomeMonth","2026-09","preTaxIncome",18000,"afterTaxIncome",16500,"payStatus",0,"confirmStatus",0)),1,10));}
        @GetMapping("/author/listIncomeDailyByPage") RestResult<?> days(){return ok(page(List.of(Map.of("incomeDate","2026-10-05","incomeAccount",1200,"incomeCount",12,"incomeNumber",8)),1,10));}
        @GetMapping("/news/listByPage") RestResult<?> news(){return ok(page(List.of(Map.of("id","31","title","阅读与创作，从一页开始","catName","站内公告","createTime","2026-10-06")),1,10));}
        @GetMapping("/friendLink/listIndexLink") RestResult<?> links(){return ok(List.of());}
        @PostMapping("/file/picUpload") RestResult<?> upload(){return ok("/frontend-fixture/assets/cover-0.svg");}
        @GetMapping(value="/file/getVerify",produces="image/svg+xml") void captcha(HttpServletResponse response) throws java.io.IOException {response.setContentType("image/svg+xml");response.getWriter().write("<svg xmlns='http://www.w3.org/2000/svg' width='120' height='44'><rect width='120' height='44' fill='#e7ede6'/><text x='16' y='30' font-size='25' fill='#244c39'>1234</text></svg>");}
        @RequestMapping(value={"/book/addVisitCount","/user/addReadHistory","/user/addToBookShelf","/user/removeFromBookShelf/{id}","/user/updateUserInfo","/user/updatePassword","/user/addFeedBack","/book/addBookComment","/book/addCommentReply","/book/toggleCommentLike","/book/toggleCommentUnLike","/book/toggleReplyLike","/book/toggleReplyUnLike","/author/addBook","/author/addBookContent","/author/updateBookContent","/author/updateBookPic","/author/updateBookStatus","/author/updateIndexName","/author/deleteIndex/{id}"},method={RequestMethod.POST,RequestMethod.DELETE}) RestResult<?> write(HttpServletRequest r,@RequestParam Map<String,String> p){if(!logged(r)&&!r.getRequestURI().equals("/book/addVisitCount"))return unauth();WRITES.add(Map.of("path",r.getRequestURI(),"method",r.getMethod(),"params",new LinkedHashMap<>(p)));if(r.getRequestURI().equals("/user/addToBookShelf"))SHELF.add(p.get("bookId"));if(r.getMethod().equals("DELETE")&&r.getRequestURI().contains("removeFromBookShelf"))SHELF.remove(r.getRequestURI().substring(r.getRequestURI().lastIndexOf('/')+1));return ok(r.getRequestURI().contains("toggle")?5:null);}
        @PostMapping("/author/ai/{action}") RestResult<?> ai(){return ok("这是本地模拟的辅助写作结果，可预览后决定是否插入正文。");}
        @PostMapping("/pay/aliPay") RestResult<?> payment(){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"本地预览禁止创建支付订单");}
        @PostMapping("/user/buyBookIndex") RestResult<?> purchase(){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"本地预览禁止购买");}
        @GetMapping("/frontend-fixture/writes") RestResult<?> writes(){return ok(WRITES);}
    }
}
