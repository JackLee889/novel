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


import com.java2nb.novel.domain.UserDO;
import com.java2nb.novel.service.UserService;
import com.java2nb.common.utils.PageBean;
import com.java2nb.common.utils.Query;
import com.java2nb.common.utils.R;

/**
 * 
 *
 * @author xiongxy
 * @email 1179705413@qq.com
 * @date 2020-12-01 03:49:08
 */

@Controller
@RequestMapping("/novel/user")
public class UserController {
    @Autowired
    private UserService userService;

    @GetMapping()
    @RequiresPermissions("novel:user:user")
    String User() {
        return "novel/user/user";
    }

    @Operation(summary = "获取列表", description = "获取列表")
    @ResponseBody
    @GetMapping("/list")
    @RequiresPermissions("novel:user:user")
    public R list(@RequestParam Map<String, Object> params) {
        //查询列表数据
        Query query = new Query(params);
        List<UserDO> userList = userService.list(query);
        int total = userService.count(query);
        PageBean pageBean = new PageBean(userList, total);
        return R.ok().put("data", pageBean);
    }

    @Operation(summary = "新增页面", description = "新增页面")
    @GetMapping("/add")
    @RequiresPermissions("novel:user:add")
    String add() {
        return "novel/user/add";
    }

    @Operation(summary = "修改页面", description = "修改页面")
    @GetMapping("/edit/{id}")
    @RequiresPermissions("novel:user:edit")
    String edit(@PathVariable("id") Long id, Model model) {
            UserDO user = userService.get(id);
        model.addAttribute("user", user);
        return "novel/user/edit";
    }

    @Operation(summary = "查看页面", description = "查看页面")
    @GetMapping("/detail/{id}")
    @RequiresPermissions("novel:user:detail")
    String detail(@PathVariable("id") Long id, Model model) {
			UserDO user = userService.get(id);
        model.addAttribute("user", user);
        return "novel/user/detail";
    }

    /**
     * 保存
     */
    @Operation(summary = "新增", description = "新增")
    @ResponseBody
    @PostMapping("/save")
    @RequiresPermissions("novel:user:add")
    public R save( UserDO user) {
        if (userService.save(user) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 修改
     */
    @Operation(summary = "修改", description = "修改")
    @ResponseBody
    @PostMapping("/update")
    @RequiresPermissions("novel:user:edit")
    public R update( UserDO user) {
            userService.update(user);
        return R.ok();
    }

    /**
     * 删除
     */
    @Operation(summary = "删除", description = "删除")
    @PostMapping("/remove")
    @ResponseBody
    @RequiresPermissions("novel:user:remove")
    public R remove( Long id) {
        if (userService.remove(id) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 删除
     */
    @Operation(summary = "批量删除", description = "批量删除")
    @PostMapping("/batchRemove")
    @ResponseBody
    @RequiresPermissions("novel:user:batchRemove")
    public R remove(@RequestParam("ids[]") Long[] ids) {
            userService.batchRemove(ids);
        return R.ok();
    }

}
