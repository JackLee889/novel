package com.java2nb.novel.controller;

import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import io.swagger.v3.oas.annotations.Operation;


import com.java2nb.novel.domain.BookIndexDO;
import com.java2nb.novel.service.BookIndexService;
import com.java2nb.common.utils.PageBean;
import com.java2nb.common.utils.Query;
import com.java2nb.common.utils.R;

/**
 * 小说目录表
 *
 * @author xiongxy
 * @email 1179705413@qq.com
 * @date 2023-04-14 19:51:54
 */

@Controller
@RequestMapping("/novel/bookIndex")
public class BookIndexController {
    @Autowired
    private BookIndexService bookIndexService;

    @GetMapping()
    @RequiresPermissions("novel:bookIndex:bookIndex")
    String BookIndex() {
        return "novel/bookIndex/bookIndex";
    }

    @Operation(summary = "获取小说目录表列表", description = "获取小说目录表列表")
    @ResponseBody
    @GetMapping("/list")
    @RequiresPermissions("novel:bookIndex:bookIndex")
    public R list(@RequestParam Map<String, Object> params) {
        //查询列表数据
        Query query = new Query(params);
        List<BookIndexDO> bookIndexList = bookIndexService.list(query);
        int total = bookIndexService.count(query);
        PageBean pageBean = new PageBean(bookIndexList, total);
        return R.ok().put("data", pageBean);
    }

    @Operation(summary = "新增小说目录表页面", description = "新增小说目录表页面")
    @GetMapping("/add")
    @RequiresPermissions("novel:bookIndex:add")
    String add() {
        return "novel/bookIndex/add";
    }

    @Operation(summary = "修改小说目录表页面", description = "修改小说目录表页面")
    @GetMapping("/edit/{id}")
    @RequiresPermissions("novel:bookIndex:edit")
    String edit(@PathVariable("id") Long id, Model model) {
            BookIndexDO bookIndex = bookIndexService.get(id);
        model.addAttribute("bookIndex", bookIndex);
        return "novel/bookIndex/edit";
    }

    @Operation(summary = "查看小说目录表页面", description = "查看小说目录表页面")
    @GetMapping("/detail/{id}")
    @RequiresPermissions("novel:bookIndex:detail")
    String detail(@PathVariable("id") Long id, Model model) {
			BookIndexDO bookIndex = bookIndexService.get(id);
        model.addAttribute("bookIndex", bookIndex);
        return "novel/bookIndex/detail";
    }

    /**
     * 保存
     */
    @Operation(summary = "新增小说目录表", description = "新增小说目录表")
    @ResponseBody
    @PostMapping("/save")
    @RequiresPermissions("novel:bookIndex:add")
    public R save( BookIndexDO bookIndex) {
        if (bookIndexService.save(bookIndex) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 修改
     */
    @Operation(summary = "修改小说目录表", description = "修改小说目录表")
    @ResponseBody
    @PostMapping("/update")
    @RequiresPermissions("novel:bookIndex:edit")
    public R update( BookIndexDO bookIndex) {
            bookIndexService.update(bookIndex);
        return R.ok();
    }

    /**
     * 删除
     */
    @Operation(summary = "删除小说目录表", description = "删除小说目录表")
    @PostMapping("/remove")
    @ResponseBody
    @RequiresPermissions("novel:bookIndex:remove")
    public R remove( Long id) {
        if (bookIndexService.remove(id) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 删除
     */
    @Operation(summary = "批量删除小说目录表", description = "批量删除小说目录表")
    @PostMapping("/batchRemove")
    @ResponseBody
    @RequiresPermissions("novel:bookIndex:batchRemove")
    public R remove(@RequestParam("ids[]") Long[] ids) {
            bookIndexService.batchRemove(ids);
        return R.ok();
    }

}
