package com.java2nb.novel.controller;

import com.java2nb.common.utils.R;
import com.java2nb.novel.domain.WebsiteInfoDO;
import com.java2nb.novel.service.WebsiteInfoService;
import io.swagger.v3.oas.annotations.Operation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 网站信息表
 *
 * @author xiongxy
 * @email 1179705413@qq.com
 * @date 2023-04-14 11:05:43
 */

@Controller
@RequestMapping("/novel/websiteInfo")
public class WebsiteInfoController {

    @Autowired
    private WebsiteInfoService websiteInfoService;

    @GetMapping()
    @RequiresPermissions("novel:websiteInfo:websiteInfo")
    String detail(Model model) {
        WebsiteInfoDO websiteInfo = websiteInfoService.get(1L);
        model.addAttribute("websiteInfo", websiteInfo);
        return "novel/websiteInfo/detail";
    }

    /**
     * 修改
     */
    @Operation(summary = "修改网站信息表", description = "修改网站信息表")
    @ResponseBody
    @PostMapping("/update")
    @RequiresPermissions("novel:websiteInfo:edit")
    public R update(WebsiteInfoDO websiteInfo) {
        websiteInfoService.update(websiteInfo);
        return R.ok();
    }

}
