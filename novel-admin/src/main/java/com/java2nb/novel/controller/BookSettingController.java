package com.java2nb.novel.controller;

import com.java2nb.common.config.CacheKey;
import com.java2nb.common.utils.PageBean;
import com.java2nb.common.utils.Query;
import com.java2nb.common.utils.R;
import com.java2nb.novel.domain.BookSettingDO;
import com.java2nb.novel.service.BookSettingService;
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
 * 首页小说推荐
 *
 * @author xiongxy
 * @email 1179705413@qq.com
 * @date 2023-04-18 10:01:13
 */

@Controller
@RequestMapping("/novel/bookSetting")
public class BookSettingController {

    @Autowired
    private BookSettingService bookSettingService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @GetMapping()
    @RequiresPermissions("novel:bookSetting:bookSetting")
    String BookSetting() {
        return "novel/bookSetting/bookSetting";
    }

    @Operation(summary = "获取首页小说设置表列表", description = "获取首页小说设置表列表")
    @ResponseBody
    @GetMapping("/list")
    @RequiresPermissions("novel:bookSetting:bookSetting")
    public R list(@RequestParam Map<String, Object> params) {
        //查询列表数据
        Query query = new Query(params);
        List<BookSettingDO> bookSettingList = bookSettingService.list(query);
        int total = bookSettingService.count(query);
        PageBean pageBean = new PageBean(bookSettingList, total);
        return R.ok().put("data", pageBean);
    }

    @Operation(summary = "新增首页小说设置表页面", description = "新增首页小说设置表页面")
    @GetMapping("/add")
    @RequiresPermissions("novel:bookSetting:add")
    String add() {
        return "novel/bookSetting/add";
    }

    @Operation(summary = "修改首页小说设置表页面", description = "修改首页小说设置表页面")
    @GetMapping("/edit/{id}")
    @RequiresPermissions("novel:bookSetting:edit")
    String edit(@PathVariable("id") Long id, Model model) {
        BookSettingDO bookSetting = bookSettingService.get(id);
        model.addAttribute("bookSetting", bookSetting);
        return "novel/bookSetting/edit";
    }

    @Operation(summary = "查看首页小说设置表页面", description = "查看首页小说设置表页面")
    @GetMapping("/detail/{id}")
    @RequiresPermissions("novel:bookSetting:detail")
    String detail(@PathVariable("id") Long id, Model model) {
        BookSettingDO bookSetting = bookSettingService.get(id);
        model.addAttribute("bookSetting", bookSetting);
        return "novel/bookSetting/detail";
    }

    /**
     * 保存
     */
    @Operation(summary = "新增首页小说设置表", description = "新增首页小说设置表")
    @ResponseBody
    @PostMapping("/save")
    @RequiresPermissions("novel:bookSetting:add")
    public R save(BookSettingDO bookSetting) {
        if (bookSettingService.save(bookSetting) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 修改
     */
    @Operation(summary = "修改首页小说设置表", description = "修改首页小说设置表")
    @ResponseBody
    @PostMapping("/update")
    @RequiresPermissions("novel:bookSetting:edit")
    public R update(BookSettingDO bookSetting) {
        bookSettingService.update(bookSetting);
        redisTemplate.delete(CacheKey.INDEX_BOOK_SETTINGS_KEY);
        return R.ok();
    }

    /**
     * 删除
     */
    @Operation(summary = "删除首页小说设置表", description = "删除首页小说设置表")
    @PostMapping("/remove")
    @ResponseBody
    @RequiresPermissions("novel:bookSetting:remove")
    public R remove(Long id) {
        if (bookSettingService.remove(id) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 删除
     */
    @Operation(summary = "批量删除首页小说设置表", description = "批量删除首页小说设置表")
    @PostMapping("/batchRemove")
    @ResponseBody
    @RequiresPermissions("novel:bookSetting:batchRemove")
    public R remove(@RequestParam("ids[]") Long[] ids) {
        bookSettingService.batchRemove(ids);
        return R.ok();
    }

}
