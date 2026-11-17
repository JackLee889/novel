package com.java2nb.novel.controller;

import com.java2nb.common.config.CacheKey;
import com.java2nb.common.utils.PageBean;
import com.java2nb.common.utils.Query;
import com.java2nb.common.utils.R;
import com.java2nb.novel.domain.NewsDO;
import com.java2nb.novel.service.NewsService;
import io.swagger.v3.oas.annotations.Operation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 新闻表
 *
 * @author xiongxy
 * @email 1179705413@qq.com
 * @date 2020-12-01 10:05:51
 */

@Controller
@RequestMapping("/novel/news")
public class NewsController {

    @Autowired
    private NewsService newsService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @GetMapping()
    @RequiresPermissions("novel:news:news")
    String News() {
        return "novel/news/news";
    }

    @Operation(summary = "获取新闻表列表", description = "获取新闻表列表")
    @ResponseBody
    @GetMapping("/list")
    @RequiresPermissions("novel:news:news")
    public R list(@RequestParam Map<String, Object> params) {
        //查询列表数据
        Query query = new Query(params);
        List<NewsDO> newsList = newsService.list(query);
        int total = newsService.count(query);
        PageBean pageBean = new PageBean(newsList, total);
        return R.ok().put("data", pageBean);
    }

    @Operation(summary = "新增新闻表页面", description = "新增新闻表页面")
    @GetMapping("/add")
    @RequiresPermissions("novel:news:add")
    String add() {
        return "novel/news/add";
    }

    @Operation(summary = "修改新闻表页面", description = "修改新闻表页面")
    @GetMapping("/edit/{id}")
    @RequiresPermissions("novel:news:edit")
    String edit(@PathVariable("id") Long id, Model model) {
        NewsDO news = newsService.get(id);
        model.addAttribute("news", news);
        return "novel/news/edit";
    }

    @Operation(summary = "查看新闻表页面", description = "查看新闻表页面")
    @GetMapping("/detail/{id}")
    @RequiresPermissions("novel:news:detail")
    String detail(@PathVariable("id") Long id, Model model) {
        NewsDO news = newsService.get(id);
        model.addAttribute("news", news);
        return "novel/news/detail";
    }

    /**
     * 保存
     */
    @Operation(summary = "新增新闻表", description = "新增新闻表")
    @ResponseBody
    @PostMapping("/save")
    @RequiresPermissions("novel:news:add")
    public R save(NewsDO news) {
        if (newsService.save(news) > 0) {
            redisTemplate.delete(CacheKey.INDEX_NEWS_KEY);
            return R.ok();
        }
        return R.error();
    }

    /**
     * 修改
     */
    @Operation(summary = "修改新闻表", description = "修改新闻表")
    @ResponseBody
    @PostMapping("/update")
    @RequiresPermissions("novel:news:edit")
    public R update(NewsDO news) {
        newsService.update(news);
        redisTemplate.delete(CacheKey.INDEX_NEWS_KEY);
        return R.ok();
    }

    /**
     * 删除
     */
    @Operation(summary = "删除新闻表", description = "删除新闻表")
    @PostMapping("/remove")
    @ResponseBody
    @RequiresPermissions("novel:news:remove")
    public R remove(Long id) {
        if (newsService.remove(id) > 0) {
            redisTemplate.delete(CacheKey.INDEX_NEWS_KEY);
            return R.ok();
        }
        return R.error();
    }

    /**
     * 删除
     */
    @Operation(summary = "批量删除新闻表", description = "批量删除新闻表")
    @PostMapping("/batchRemove")
    @ResponseBody
    @RequiresPermissions("novel:news:batchRemove")
    public R remove(@RequestParam("ids[]") Long[] ids) {
        newsService.batchRemove(ids);
        redisTemplate.delete(CacheKey.INDEX_NEWS_KEY);
        return R.ok();
    }

}
