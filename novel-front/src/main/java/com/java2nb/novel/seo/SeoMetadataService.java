package com.java2nb.novel.seo;

import com.java2nb.novel.core.config.WebsiteProperties;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.vo.ReaderChapterVO;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** The same public metadata is used in HTML and authorized chapter navigation. */
@Service("seoMetadataService")
@RequiredArgsConstructor
public class SeoMetadataService {
    private final SeoService policy;
    private final WebsiteProperties website;
    private final ServletContext servletContext;
    private final ObjectMapper objectMapper;

    public record Meta(String title, String description, String keywords, String canonical,
                       String robots, String jsonLd) {}
    public record Crumb(String name, String path) {}

    public Meta home() {
        var settings=policy.settings();
        Meta result=page("/",value(settings==null?null:settings.defaultTitle(),"小说阅读与作品分类"),defaultDescription(),true,List.of());
        String title=settings!=null&&!plain(settings.defaultTitle()).isBlank()?result.title():name()+" - 小说阅读与作品分类";
        return new Meta(title,result.description(),result.keywords(),result.canonical(),result.robots(),result.jsonLd());
    }

    public Map<String,String> verifications(){
        var settings=policy.settings();return settings==null?Map.of("google","","baidu",""):
            Map.of("google",Objects.toString(settings.googleVerification(),""),"baidu",Objects.toString(settings.baiduVerification(),""));
    }

    public Meta page(String path, String title, String description, boolean eligible, List<Crumb> crumbs) {
        String canonical = canonical(path);
        boolean index = eligible && policy.siteIndexEnabled() && !canonical.isBlank();
        return new Meta(plain(title) + " - " + name(), excerpt(description), globalKeywords(), canonical,
            index ? "index,follow" : "noindex,follow", jsonLd(crumbs, null));
    }

    public Meta book(Book book) {
        var override = policy.bookOverride(book.getId());
        String title = SeoText.bookTitle(book, override, "");
        String description = SeoText.bookDescription(book, override);
        String keywords = SeoText.bookKeywords(book, override);
        String path = "/book/" + book.getId() + ".html";
        Meta meta = page(path, title, description, policy.isIndexable(book), crumbs(book));
        var structuredBook = new LinkedHashMap<String,Object>();
        structuredBook.put("@type", "Book"); structuredBook.put("name", plain(book.getBookName()));
        if (!plain(book.getAuthorName()).isBlank()) structuredBook.put("author", Map.of("@type", "Person", "name", plain(book.getAuthorName())));
        structuredBook.put("description", plain(book.getBookDesc())); structuredBook.put("inLanguage", "zh-CN");
        if (!meta.canonical().isBlank()) structuredBook.put("url", meta.canonical());
        String image = book.getPicUrl();
        if (image != null && !image.isBlank()) {
            if (image.startsWith("/")) image = canonical(image);
            if (image.startsWith("https://") || image.startsWith("http://")) structuredBook.put("image", image);
        }
        return new Meta(meta.title(), meta.description(), plain(keywords), meta.canonical(), meta.robots(),
            jsonLd(crumbs(book), structuredBook));
    }

    public Meta catalog(Book book, int page) {
        String path = "/book/indexList-" + book.getId() + ".html" + (page > 1 ? "?page=" + page : "");
        var crumbs = new ArrayList<>(readingCrumbs(book)); crumbs.add(new Crumb("章节目录", path));
        Meta result = page(path, book.getBookName() + "章节目录 - 第" + page + "页",
            book.getBookName() + "，" + book.getAuthorName() + "著，章节目录第" + page + "页。", policy.isIndexable(book), crumbs);
        return new Meta(result.title(),result.description(),String.join(",",nonBlank(book.getBookName(),"章节目录",book.getAuthorName())),
            result.canonical(),result.robots(),result.jsonLd());
    }

    public Meta chapter(Book book, BookIndex index, ReaderChapterVO view) {
        boolean free = !Byte.valueOf((byte)1).equals(index.getIsVip());
        boolean readable = free && "allowed".equals(view.access()) && !view.paragraphs().isEmpty();
        String path = "/book/" + book.getId() + "/" + index.getId() + ".html";
        String body = readable ? String.join(" ", view.paragraphs().stream().limit(5).map(ReaderChapterVO.Paragraph::text).toList())
            : book.getBookName() + "，" + index.getIndexName() + "。";
        var crumbs = new ArrayList<>(readingCrumbs(book));
        crumbs.add(new Crumb("章节目录", "/book/indexList-" + book.getId() + ".html"));
        crumbs.add(new Crumb(index.getIndexName(),path));
        Meta result = page(path,index.getIndexName() + " - " + book.getBookName(),body,
            readable && policy.chapterIndexable(book,index),crumbs);
        return new Meta(result.title(),result.description(),String.join(",",nonBlank(book.getBookName(),index.getIndexName(),book.getAuthorName())),
            result.canonical(),result.robots(),result.jsonLd());
    }

    public void apply(HttpServletResponse response, Meta meta) {
        response.setHeader("X-Robots-Tag",meta.robots());
    }

    public String canonical(String path) {
        String root = policy.publicSiteUrl();
        if (root == null || root.isBlank() || path == null || !path.startsWith("/") || path.startsWith("//")) return "";
        return root.replaceAll("/+$", "") + path;
    }

    public String name() { return plain(value(siteField("name"),value(website.getName(),"小说精品屋"))); }
    public String globalKeywords() {
        var settings=policy.settings();
        return plain(value(settings == null ? null : settings.keywords(),value(siteField("keyword"),website.getKeyword())));
    }
    public String defaultDescription() {
        var settings=policy.settings();
        return value(settings == null ? null : settings.defaultDescription(),value(siteField("description"),website.getDescription()));
    }
    private String siteField(String field) {
        Object info=servletContext.getAttribute("website");
        if (info instanceof WebsiteInfo w) return switch(field){case "name"->w.getName();case "keyword"->w.getKeyword();default->w.getDescription();};
        if (info instanceof Map<?,?> map) return Objects.toString(map.get(field),"");
        return "";
    }
    private List<Crumb> crumbs(Book book) {
        var result=new ArrayList<Crumb>(); result.add(new Crumb("首页","/")); result.add(new Crumb("全部作品","/book/bookclass.html"));
        if (book.getCatId()!=null && book.getCatName()!=null) result.add(new Crumb(book.getCatName(),"/book/bookclass.html?c="+book.getCatId()));
        result.add(new Crumb(book.getBookName(),"/book/"+book.getId()+".html")); return result;
    }
    private List<Crumb> readingCrumbs(Book book) {
        return List.of(new Crumb("首页","/"),new Crumb(book.getBookName(),"/book/"+book.getId()+".html"));
    }
    private String jsonLd(List<Crumb> crumbs, Map<String,Object> book) {
        if (canonical("/").isBlank()) return "";
        var graph=new ArrayList<Object>();
        if (book!=null) graph.add(book);
        if (crumbs!=null && !crumbs.isEmpty()) {
            var items=new ArrayList<Object>(); int position=1;
            for (var crumb:crumbs) items.add(Map.of("@type","ListItem","position",position++,"name",plain(crumb.name()),"item",canonical(crumb.path())));
            graph.add(Map.of("@type","BreadcrumbList","itemListElement",items));
        }
        if (graph.isEmpty()) return "";
        return objectMapper.writeValueAsString(Map.of("@context","https://schema.org","@graph",graph))
            .replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026");
    }
    public String descriptionText(String value) { return plain(value); }
    public static String plain(String value) { return SeoText.plain(value); }
    public static String excerpt(String value) { return SeoText.excerpt(value); }
    private static String value(String preferred,String fallback) { return preferred!=null&&!preferred.isBlank()?preferred:Objects.toString(fallback,""); }
    private static List<String> nonBlank(String... values) { return Arrays.stream(values).filter(Objects::nonNull).map(SeoMetadataService::plain).filter(s->!s.isBlank()).distinct().toList(); }
}
