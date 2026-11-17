package com.java2nb.novel.controller.page;

import com.java2nb.novel.controller.BaseController;
import com.java2nb.novel.controller.ReaderController;
import com.java2nb.novel.core.config.ReaderProperties;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.utils.ThreadLocalUtil;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.service.*;
import com.java2nb.novel.vo.BookCommentVO;
import com.java2nb.novel.vo.BookSettingVO;
import io.github.xxyopen.model.page.PageBean;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.LinkedHashMap;
import com.java2nb.novel.seo.CatalogQuery;
import com.java2nb.novel.seo.SeoMetadataService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author 11797
 */
@Slf4j
@RequiredArgsConstructor
@Controller
public class PageController extends BaseController {

    private final BookService bookService;
    private final ReaderChapterService readerChapterService;
    private final ReaderProperties readerProperties;
    private final ObjectMapper objectMapper;
    private final SeoMetadataService seoMetadata;

    private final NewsService newsService;

    private final AuthorService authorService;

    private final UserService userService;

    private final ThreadPoolExecutor threadPoolExecutor;

    private final Map<String, BookContentService> bookContentServiceMap;

    @ModelAttribute("globalReaderEnabled")
    public boolean globalReaderEnabled() {
        return readerProperties.isEnabled() && readerProperties.getTts().isEnabled();
    }

    @ModelAttribute("seoVerification")
    public Map<String,String> seoVerification(){return seoMetadata.verifications();}

    @ModelAttribute("seo")
    public SeoMetadataService.Meta defaultSeo(HttpServletRequest request, HttpServletResponse response) {
        var fallback=seoMetadata.page(request.getRequestURI(),"","",false,List.of());
        var meta=new SeoMetadataService.Meta("","",fallback.keywords(),fallback.canonical(),fallback.robots(),"");
        seoMetadata.apply(response,meta);
        if(request.getRequestURI().startsWith("/user/")||request.getRequestURI().startsWith("/pay/")||request.getRequestURI().startsWith("/author/"))ReaderController.noStore(response);
        return meta;
    }

    private void seo(Model model,HttpServletResponse response,SeoMetadataService.Meta meta){model.addAttribute("seo",meta);seoMetadata.apply(response,meta);}
    public record PageLink(String label,String url,boolean current,String rel) {}
    private void pagination(Model model,int page,long total,int size,java.util.function.IntFunction<String> url){
        int pages=(int)Math.max(1,(total+size-1)/size);if(page>pages)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"分页不存在");
        var links=new ArrayList<PageLink>();
        if(page>1){links.add(new PageLink("首页",url.apply(1),false,""));links.add(new PageLink("上一页",url.apply(page-1),false,"prev"));}
        for(int p=Math.max(1,page-2);p<=Math.min(pages,page+2);p++)links.add(new PageLink(String.valueOf(p),url.apply(p),p==page,""));
        if(page<pages){links.add(new PageLink("下一页",url.apply(page+1),false,"next"));links.add(new PageLink("末页",url.apply(pages),false,""));}
        model.addAttribute("paginationLinks",links);model.addAttribute("currentPage",page);model.addAttribute("totalPages",pages);
    }

    @RequestMapping("{url}.html")
    public String module(@PathVariable("url") String url) {
        return url;
    }

    @RequestMapping("{module}/{url}.html")
    public String module2(@PathVariable("module") String module, @PathVariable("url") String url,
        HttpServletRequest request) {

        if (request.getRequestURI().startsWith("/author")) {
            //访问作者专区
            UserDetails user = getUserDetails(request);
            if (user == null) {
                //未登录
                return "redirect:/user/login.html?originUrl=" + request.getRequestURI();
            }

            boolean isAuthor = authorService.isAuthor(user.getId());
            if (!isAuthor) {
                return "redirect:/author/register.html";
            }
        }

        return module + "/" + url;
    }

    @RequestMapping("{module}/{classify}/{url}.html")
    public String module3(@PathVariable("module") String module, @PathVariable("classify") String classify,
        @PathVariable("url") String url) {
        return module + "/" + classify + "/" + url;
    }

    /**
     * 首页
     */
    @SneakyThrows
    @GetMapping(path = "/")
    public String index(Model model,HttpServletResponse response) {
        //加载小说首页小说基本信息线程
        CompletableFuture<Map<String, List<BookSettingVO>>> bookCompletableFuture = CompletableFuture.supplyAsync(
            bookService::listBookSettingVO, threadPoolExecutor);
        //加载首页新闻线程
        CompletableFuture<List<News>> newsCompletableFuture = CompletableFuture.supplyAsync(newsService::listIndexNews,
            threadPoolExecutor);
        var bookMap=new HashMap<>(bookCompletableFuture.get());
        if(bookMap.get("0")==null||bookMap.get("0").isEmpty()){
            var query=new com.java2nb.novel.vo.BookSpVO();query.setSort("word_count");
            var discovery=bookService.searchByPage(query,1,8).getList();
            var picks=new ArrayList<BookSettingVO>();
            for(var item:discovery){if(item instanceof Book book){var pick=new BookSettingVO();org.springframework.beans.BeanUtils.copyProperties(book,pick);pick.setBookId(book.getId());picks.add(pick);}}
            if(!picks.isEmpty()){
                bookMap.put("0",picks.subList(0,Math.min(4,picks.size())));
                if(bookMap.get("3")==null||bookMap.get("3").isEmpty())bookMap.put("3",picks);
                if(bookMap.get("2")==null||bookMap.get("2").isEmpty())bookMap.put("2",picks);
            }
        }
        model.addAttribute("bookMap", bookMap);
        model.addAttribute("newsList", newsCompletableFuture.get());
        model.addAttribute("homeCategories",bookService.listBookCategory());
        model.addAttribute("clickRank",bookService.listClickRank());
        model.addAttribute("updateRank",bookService.listUpdateRank());
        seo(model,response,seoMetadata.home());
        return ThreadLocalUtil.getTemplateDir() + "index";
    }

    @GetMapping({"/index","/index.html"})
    public void indexAlias(HttpServletResponse response){response.setStatus(301);response.setHeader("Location","/");}

    /**
     * 登录页
     */
    @RequestMapping("user/login.html")
    public String login() {
        return ThreadLocalUtil.getTemplateDir() + "user/login";
    }

    /**
     * 注册页
     */
    @RequestMapping("user/register.html")
    public String register() {
        return ThreadLocalUtil.getTemplateDir() + "user/register";
    }

    /**
     * 用户中心页
     */
    @RequestMapping("user/userinfo.html")
    public String userinfo() {
        return ThreadLocalUtil.getTemplateDir() + "user/userinfo";
    }

    /**
     * 我的书架页
     */
    @RequestMapping("user/favorites.html")
    public String favorites() {
        return ThreadLocalUtil.getTemplateDir() + "user/favorites";
    }

    /**
     * 阅读历史页
     */
    @RequestMapping("user/read_history.html")
    public String readHistory() {
        return ThreadLocalUtil.getTemplateDir() + "user/read_history";
    }

    /**
     * 充值页
     */
    @RequestMapping("pay/index.html")
    public String pay() {
        return ThreadLocalUtil.getTemplateDir() + "pay/index.html";
    }


    /**
     * 作品页
     */
    @RequestMapping("book/bookclass.html")
    public String bookClass(HttpServletRequest request,HttpServletResponse response,Model model) {
        CatalogQuery query=CatalogQuery.parse(request);
        String url=query.url(query.page());
        if(!url.equals(request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString()))){response.setStatus(301);response.setHeader("Location",url);return null;}
        var categories=bookService.listBookCategory();
        String label=query.values().containsKey("workDirection")?("0".equals(query.values().get("workDirection"))?"男频小说":"女频小说"):
            "1".equals(query.values().get("bookStatus"))?"完结小说":"全部作品";
        if(query.search().getCatId()!=null){var category=categories.stream().filter(c->Objects.equals(c.getId(),query.search().getCatId())).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"分类不存在"));label=category.getName()+"小说";}
        var result=bookService.searchByPage(query.search(),query.page(),12);
        pagination(model,query.page(),result.getTotal(),12,query::url);
        model.addAttribute("catalogQuery",query);model.addAttribute("catalogCategories",categories);model.addAttribute("catalogPage",result);model.addAttribute("catalogLabel",label);
        seo(model,response,seoMetadata.page(url,label+" - 第"+query.page()+"页",label+"，按题材、作者和连载状态发现喜欢的作品。",query.indexableShape()&&result.getTotal()>0,List.of(new SeoMetadataService.Crumb("首页","/"),new SeoMetadataService.Crumb(label,url))));
        return "book/bookclass";
    }

    /**
     * 排行页
     */
    @RequestMapping("book/book_ranking.html")
    public String bookRank(@RequestParam(defaultValue="0") String type,HttpServletRequest request,HttpServletResponse response,Model model) {
        int rank=CatalogQuery.integer(type,0,3);String path="/book/book_ranking.html"+(rank==0?"":"?type="+rank);
        if(!path.equals(request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString()))){response.setStatus(301);response.setHeader("Location",path);return null;}
        String name=List.of("点击榜单","新书榜单","更新榜单","评论榜单").get(rank);
        var books=bookService.listRank((byte)rank,30);model.addAttribute("rankBooks",books);model.addAttribute("rankType",rank);model.addAttribute("rankName",name);
        seo(model,response,seoMetadata.page(path,name,name+"，发现读者喜爱的小说和最新故事。",!books.isEmpty(),List.of(new SeoMetadataService.Crumb("首页","/"),new SeoMetadataService.Crumb(name,path))));
        return ThreadLocalUtil.getTemplateDir() + "book/book_ranking";
    }


    /**
     * 详情页
     */
    @SneakyThrows
    @RequestMapping("/book/{bookId}.html")
    public String bookDetail(@PathVariable("bookId") Long bookId, Model model,HttpServletResponse response) {
        //加载小说基本信息线程
        CompletableFuture<Book> bookCompletableFuture = CompletableFuture.supplyAsync(() -> {
            //查询书籍
            Book book = bookService.queryBookDetail(bookId);
            log.debug("加载小说基本信息线程结束");
            return book;
        }, threadPoolExecutor);
        //加载小说评论列表线程
        CompletableFuture<PageBean<BookCommentVO>> bookCommentPageBeanCompletableFuture = CompletableFuture.supplyAsync(
            () -> {
                PageBean<BookCommentVO> bookCommentVOPageBean = bookService.listCommentByPage(null, bookId, 1, 5);
                log.debug("加载小说评论列表线程结束");
                return bookCommentVOPageBean;
            }, threadPoolExecutor);
        //加载小说首章信息线程，该线程在加载小说基本信息线程执行完毕后才执行
        CompletableFuture<Long> firstBookIndexIdCompletableFuture = bookCompletableFuture.thenApplyAsync((book) -> {
            if (book.getLastIndexId() != null) {
                //查询首章目录ID
                Long firstBookIndexId = bookService.queryFirstBookIndexId(bookId);
                log.debug("加载小说基本信息线程结束");
                return firstBookIndexId;
            }
            return null;
        }, threadPoolExecutor);
        //加载随机推荐小说线程，该线程在加载小说基本信息线程执行完毕后才执行
        CompletableFuture<List<Book>> recBookCompletableFuture = bookCompletableFuture.thenApplyAsync((book) -> {
            List<Book> books = bookService.listRecBookByCatId(book.getCatId());
            log.debug("加载随机推荐小说线程结束");
            return books;
        }, threadPoolExecutor);

        model.addAttribute("book", bookCompletableFuture.get());
        model.addAttribute("firstBookIndexId", firstBookIndexIdCompletableFuture.get());
        model.addAttribute("recBooks", recBookCompletableFuture.get());
        model.addAttribute("bookCommentPageBean", bookCommentPageBeanCompletableFuture.get());
        Book book=bookCompletableFuture.get();model.addAttribute("bookDescription",SeoMetadataService.plain(book.getBookDesc()));
        seo(model,response,seoMetadata.book(book));

        return ThreadLocalUtil.getTemplateDir() + "book/book_detail";
    }

    /**
     * 目录页
     */
    @SneakyThrows
    @RequestMapping("/book/indexList-{bookId}.html")
    public String indexList(@PathVariable("bookId") Long bookId,@RequestParam(defaultValue="1") String page,HttpServletRequest request,HttpServletResponse response, Model model) {
        int current=CatalogQuery.integer(page,1,100000);
        String path="/book/indexList-"+bookId+".html"+(current==1?"":"?page="+current);
        if(!path.equals(request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString()))){response.setStatus(301);response.setHeader("Location",path);return null;}
        Book book = bookService.queryBookDetail(bookId);
        model.addAttribute("book", book);
        long count=bookService.queryIndexCount(bookId);
        pagination(model,current,count,200,p->"/book/indexList-"+bookId+".html"+(p>1?"?page="+p:""));
        List<BookIndex> bookIndexList = bookService.queryIndexList(bookId, "index_num", current, 200);
        model.addAttribute("bookIndexList", bookIndexList);
        model.addAttribute("bookIndexCount", count);
        seo(model,response,seoMetadata.catalog(book,current));
        return ThreadLocalUtil.getTemplateDir() + "book/book_index";
    }

    /**
     * 内容页
     */
    @SneakyThrows
    @RequestMapping("/book/{bookId}/{bookIndexId}.html")
    public String bookContent(@PathVariable("bookId") Long bookId, @PathVariable("bookIndexId") Long bookIndexId,
        HttpServletRequest request, HttpServletResponse response, Model model) {
        var user = getUserDetails(request);
        var access = readerChapterService.read(bookId, bookIndexId, user == null ? null : user.getId());
        model.addAttribute("book", access.book());
        model.addAttribute("bookIndex", access.index());
        model.addAttribute("preBookIndexId", access.view().previousChapterId() == null ? "0" : access.view().previousChapterId());
        model.addAttribute("nextBookIndexId", access.view().nextChapterId() == null ? "0" : access.view().nextChapterId());
        model.addAttribute("bookContent", access.content());
        model.addAttribute("readerChapter", access.view());
        model.addAttribute("needBuy", !"allowed".equals(access.view().access()));
        ReaderController.noStore(response);
        var metadata=seoMetadata.chapter(access.book(),access.index(),access.view());seo(model,response,metadata);
        if (readerProperties.isEnabled()) {
            var bootstrap=new LinkedHashMap<String,Object>(objectMapper.convertValue(access.view(),Map.class));bootstrap.put("seo",metadata);
            model.addAttribute("readerJson", objectMapper.writeValueAsString(bootstrap)
                .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026"));
            model.addAttribute("readerTts", readerProperties.getTts());
            return "book/reader";
        }
        return ThreadLocalUtil.getTemplateDir() + "book/book_content";
    }

    /**
     * 评论页面
     */
    @RequestMapping("/book/comment-{bookId}.html")
    public String commentList(@PathVariable("bookId") Long bookId, Model model) {
        //查询书籍
        Book book = bookService.queryBookDetail(bookId);
        model.addAttribute("book", book);
        return "book/book_comment";
    }

    /**
     * 评论回复页面
     */
    @RequestMapping("/book/reply-{commentId}.html")
    public String commentReplyList(@PathVariable("commentId") Long commentId, Model model) {
        model.addAttribute("commentId", commentId);
        model.addAttribute("commentContent", bookService.getBookComment(commentId).getCommentContent());
        return "book/book_comment_reply";
    }

    /**
     * 新闻内容页面
     */
    @RequestMapping("/about/newsInfo-{newsId}.html")
    public String newsInfo(@PathVariable("newsId") Long newsId, Model model) {
        //查询新闻
        News news = newsService.queryNewsInfo(newsId);
        model.addAttribute("news", news);
        return "about/news_info";
    }


    /**
     * 作者注册页面
     */
    @RequestMapping("author/register.html")
    public String authorRegister(Author author, HttpServletRequest request, Model model) {
        UserDetails user = getUserDetails(request);
        if (user == null) {
            //未登录
            return "redirect:/user/login.html?originUrl=/author/register.html";
        }

        if (StringUtils.isNotBlank(author.getInviteCode())) {
            //提交作者注册信息
            String errorInfo = authorService.register(user.getId(), author);
            if (StringUtils.isBlank(errorInfo)) {
                //注册成功
                return "redirect:/author/index.html";
            }
            model.addAttribute("LabErr", errorInfo);
            model.addAttribute("author", author);
        }
        return "author/register";
    }


}
