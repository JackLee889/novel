package com.java2nb.novel.controller;

import com.java2nb.novel.core.cache.CacheKey;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.utils.CrawlAccessChallengeException;
import com.java2nb.novel.utils.CrawlMetadataException;
import io.github.xxyopen.model.page.PageBean;

import com.java2nb.novel.entity.CrawlSingleTask;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.entity.BookCategory;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.CrawlService;
import io.github.xxyopen.model.resp.RestResult;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Administrator
 */
@RestController
@RequestMapping("crawl")
@RequiredArgsConstructor
public class CrawlController {

    private final CrawlService crawlService;

    private final CacheService cacheService;
    private final BookService bookService;
    private final CrawlHttpClient crawlHttpClient;

    @GetMapping("listBookCategories")
    public RestResult<List<BookCategory>> listBookCategories() {
        return RestResult.ok(bookService.listBookCategories());
    }
    /**
     * 新增爬虫源
     * */
    @PostMapping("addCrawlSource")
    public RestResult<Void> addCrawlSource(CrawlSource source){
        crawlService.addCrawlSource(source);

        return RestResult.ok();

    }

    /**
     * 爬虫源分页列表查询
     * */
    @GetMapping("listCrawlByPage")
    public RestResult<PageBean<CrawlSource>> listCrawlByPage(@RequestParam(value = "curr", defaultValue = "1") int page, @RequestParam(value = "limit", defaultValue = "10") int pageSize){

        return RestResult.ok(crawlService.listCrawlByPage(page,pageSize));
    }
    /**
     * 获取爬虫源
     * */
    @GetMapping("getCrawlSource/{id}")
    public RestResult<CrawlSource> getCrawlSource(@PathVariable("id") Integer id){
        CrawlSource crawlSource=  crawlService.getCrawlSource(id);
        return RestResult.ok(crawlSource);

    }

    /**
     * 测试规则
     *
     * [审查16] 原来固定用默认 UTF-8 抓取且不接收 charset，正式采集却用 RuleBean.charset，
     * GBK 站点在规则测试里会乱码；而且抓取失败也返回 RestResult.ok，只有一句 "html is null"，
     * 无法区分「没抓到页面」和「抓到了但规则没命中」。现在接收 charset 并给出明确的成功标志与命中数量。
     */
    @PostMapping("testParse")
    public RestResult<Object> testParse(String rule, String url, String isRefresh, String charset)
        throws InterruptedException {

        Map<String,Object> resultMap=new HashMap<>();

        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            resultMap.put("成功", false);
            resultMap.put("说明", "url 为空或协议不受支持");
            return RestResult.ok(resultMap);
        }

        boolean useDefaultCharset = StringUtils.isBlank(charset);
        String requestCharset = useDefaultCharset ? null : Charset.forName(charset.trim()).name();
        String cacheKey = CacheKey.BOOK_TEST_PARSE + (useDefaultCharset ? "default" : requestCharset)
            + ":" + url;

        String html = null;
        boolean fromCache = false;
        if (!"1".equals(isRefresh)) {
            html = cacheService.get(cacheKey);
            fromCache = html != null;
        }
        if (html == null) {
            try {
                html = crawlHttpClient.fetchPage(url, useDefaultCharset ? "utf-8" : requestCharset);
            } catch (CrawlAccessChallengeException e) {
                resultMap.put("成功", false);
                resultMap.put("说明", e.getMessage());
                resultMap.put("重试等待秒数", e.getRetryAfterSeconds());
                return RestResult.ok(resultMap);
            } catch (CrawlMetadataException e) {
                resultMap.put("成功", false);
                resultMap.put("错误类型", e.getReason().name());
                resultMap.put("说明", e.getMessage());
                return RestResult.ok(resultMap);
            }
            if (html == null) {
                // 明确失败，而不是包装成成功
                resultMap.put("成功", false);
                resultMap.put("说明", "抓取失败，未取得页面内容");
                resultMap.put("url", url);
                return RestResult.ok(resultMap);
            }
            cacheService.set(cacheKey, html, 60 * 10);
        }

        resultMap.put("命中缓存", fromCache);
        resultMap.put("使用编码", useDefaultCharset ? "默认" : requestCharset);
        resultMap.put("页面长度", html.length());

        Pattern pattern = Pattern.compile(rule);
        Matcher matcher = pattern.matcher(html);
        int count = 0;
        String first = null;
        while (matcher.find() && count < 50) {
            count++;
            if (first == null && matcher.groupCount() >= 1) {
                first = matcher.group(1);
            }
        }
        resultMap.put("是否匹配", count > 0);
        resultMap.put("匹配数量", count);
        if (first != null) {
            resultMap.put("匹配结果", first);
        }
        resultMap.put("成功", count > 0);
        return RestResult.ok(resultMap);
    }
    /**
     * 修改爬虫源
     * */
    @PostMapping("updateCrawlSource")
    public RestResult<Void> updateCrawlSource(CrawlSource source) {
        crawlService.updateCrawlSource(source);
        return RestResult.ok();

    }
    /**
     * 开启或停止爬虫
     * */
    @PostMapping("openOrCloseCrawl")
    public RestResult<Void> openOrCloseCrawl(Integer sourceId,Byte sourceStatus){

        crawlService.openOrCloseCrawl(sourceId,sourceStatus);

        return RestResult.ok();
    }

    /**
     * 新增单本采集任务
     * */
    @PostMapping("addCrawlSingleTask")
    public RestResult<Void> addCrawlSingleTask(CrawlSingleTask singleTask){
        crawlService.addCrawlSingleTask(singleTask);

        return RestResult.ok();

    }

    /**
     * 单本采集任务分页列表查询
     * */
    @GetMapping("listCrawlSingleTaskByPage")
    public RestResult<PageBean<CrawlSingleTask>> listCrawlSingleTaskByPage(@RequestParam(value = "curr", defaultValue = "1") int page, @RequestParam(value = "limit", defaultValue = "10") int pageSize){

        return RestResult.ok(crawlService.listCrawlSingleTaskByPage(page,pageSize));
    }

    /**
     * 删除采集任务
     * */
    @DeleteMapping("delCrawlSingleTask/{id}")
    public RestResult<Void> delCrawlSingleTask(@PathVariable("id") Long id){

        crawlService.delCrawlSingleTask(id);

        return RestResult.ok();
    }

    /**
     * 采集任务进度查询
     * */
    @GetMapping("getTaskProgress/{id}")
    public RestResult<Integer> getTaskProgress(@PathVariable("id") Long id){
        return RestResult.ok(crawlService.getTaskProgress(id));
    }




}
