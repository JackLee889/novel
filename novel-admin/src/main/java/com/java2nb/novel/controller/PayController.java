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


import com.java2nb.novel.domain.PayDO;
import com.java2nb.novel.service.PayService;
import com.java2nb.common.utils.PageBean;
import com.java2nb.common.utils.Query;
import com.java2nb.common.utils.R;

/**
 * 充值订单
 *
 * @author xiongxy
 * @email 1179705413@qq.com
 * @date 2020-12-01 03:49:57
 */

@Controller
@RequestMapping("/novel/pay")
public class PayController {
    @Autowired
    private PayService payService;

    @GetMapping()
    @RequiresPermissions("novel:pay:pay")
    String Pay() {
        return "novel/pay/pay";
    }

    @Operation(summary = "获取充值订单列表", description = "获取充值订单列表")
    @ResponseBody
    @GetMapping("/list")
    @RequiresPermissions("novel:pay:pay")
    public R list(@RequestParam Map<String, Object> params) {
        //查询列表数据
        Query query = new Query(params);
        List<PayDO> payList = payService.list(query);
        int total = payService.count(query);
        PageBean pageBean = new PageBean(payList, total);
        return R.ok().put("data", pageBean);
    }

    @Operation(summary = "新增充值订单页面", description = "新增充值订单页面")
    @GetMapping("/add")
    @RequiresPermissions("novel:pay:add")
    String add() {
        return "novel/pay/add";
    }

    @Operation(summary = "修改充值订单页面", description = "修改充值订单页面")
    @GetMapping("/edit/{id}")
    @RequiresPermissions("novel:pay:edit")
    String edit(@PathVariable("id") Long id, Model model) {
            PayDO pay = payService.get(id);
        model.addAttribute("pay", pay);
        return "novel/pay/edit";
    }

    @Operation(summary = "查看充值订单页面", description = "查看充值订单页面")
    @GetMapping("/detail/{id}")
    @RequiresPermissions("novel:pay:detail")
    String detail(@PathVariable("id") Long id, Model model) {
			PayDO pay = payService.get(id);
        model.addAttribute("pay", pay);
        return "novel/pay/detail";
    }

    /**
     * 保存
     */
    @Operation(summary = "新增充值订单", description = "新增充值订单")
    @ResponseBody
    @PostMapping("/save")
    @RequiresPermissions("novel:pay:add")
    public R save( PayDO pay) {
        if (payService.save(pay) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 修改
     */
    @Operation(summary = "修改充值订单", description = "修改充值订单")
    @ResponseBody
    @PostMapping("/update")
    @RequiresPermissions("novel:pay:edit")
    public R update( PayDO pay) {
            payService.update(pay);
        return R.ok();
    }

    /**
     * 删除
     */
    @Operation(summary = "删除充值订单", description = "删除充值订单")
    @PostMapping("/remove")
    @ResponseBody
    @RequiresPermissions("novel:pay:remove")
    public R remove( Long id) {
        if (payService.remove(id) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 删除
     */
    @Operation(summary = "批量删除充值订单", description = "批量删除充值订单")
    @PostMapping("/batchRemove")
    @ResponseBody
    @RequiresPermissions("novel:pay:batchRemove")
    public R remove(@RequestParam("ids[]") Long[] ids) {
            payService.batchRemove(ids);
        return R.ok();
    }

}
