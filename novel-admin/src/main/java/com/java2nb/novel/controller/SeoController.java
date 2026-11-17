package com.java2nb.novel.controller;

import com.java2nb.common.annotation.Log;
import com.java2nb.common.controller.BaseController;
import com.java2nb.common.utils.R;
import com.java2nb.novel.service.SeoAdminService;
import org.apache.shiro.authz.annotation.Logical;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@RequestMapping("/novel/seo")
public class SeoController extends BaseController {
    private final SeoAdminService service;
    public SeoController(SeoAdminService service) { this.service = service; }

    @GetMapping
    @RequiresPermissions(value = {"seo:config:view", "seo:book:view", "seo:sitemap:view", "seo:diagnosis:view"}, logical = Logical.OR)
    public String page() { return "novel/seo/manage"; }

    @ResponseBody @GetMapping("/config") @RequiresPermissions("seo:config:view")
    public R config() { return R.ok().put("data", safe(service.config())); }
    @Log("修改SEO站点设置") @ResponseBody @PostMapping("/config") @RequiresPermissions("seo:config:edit")
    public R updateConfig(@RequestBody SeoAdminService.ConfigChange request) {
        return R.ok().put("data", safe(service.updateConfig(request, getUserId())));
    }
    @ResponseBody @GetMapping("/books") @RequiresPermissions("seo:book:view")
    public R books(@RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "20") int limit) { return R.ok().put("data", safe(service.books(query, offset, limit))); }
    @ResponseBody @GetMapping("/books/{bookId}") @RequiresPermissions("seo:book:view")
    public R preview(@PathVariable String bookId) { return R.ok().put("data", safe(service.preview(id(bookId), null))); }
    @Log("修改单书SEO覆盖") @ResponseBody @PostMapping("/books/{bookId}") @RequiresPermissions("seo:book:edit")
    public R updateBook(@PathVariable String bookId, @RequestBody SeoAdminService.BookChange request) {
        return R.ok().put("data", safe(service.updateBook(id(bookId), request, getUserId())));
    }
    @ResponseBody @GetMapping("/sitemaps") @RequiresPermissions("seo:sitemap:view")
    public R sitemaps() { return R.ok().put("data", safe(service.sitemaps())); }
    @Log("SEO重建入队") @ResponseBody @PostMapping("/rebuild") @RequiresPermissions("seo:sitemap:rebuild")
    public R rebuild(@RequestBody SeoAdminService.Rebuild request) {
        return R.ok("任务已入队，后台按批处理").put("jobId", service.enqueue(request));
    }
    @ResponseBody @GetMapping("/diagnosis") @RequiresPermissions("seo:diagnosis:view")
    public R diagnosisStatus() { return R.ok().put("data", safe(service.sitemaps())); }
    @Log("执行SEO页面诊断") @ResponseBody @PostMapping("/diagnosis") @RequiresPermissions("seo:diagnosis:run")
    public R diagnose(@RequestBody SeoAdminService.Diagnosis request) { return R.ok().put("data", safe(service.diagnose(request))); }

    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("ID无效");
        try { return Long.parseLong(value); } catch (NumberFormatException ex) { throw new IllegalArgumentException("ID超出范围"); }
    }
    // JSON numbers would round existing Snowflake IDs in JavaScript; all Long values remain strings.
    static Object safe(Object value) {
        if (value instanceof Long number) return number.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>(); map.forEach((k, v) -> result.put(k.toString(), safe(v))); return result;
        }
        if (value instanceof Iterable<?> items) {
            var result = new java.util.ArrayList<>(); items.forEach(item -> result.add(safe(item))); return result;
        }
        if (value != null && value.getClass().isRecord()) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var component : value.getClass().getRecordComponents()) {
                try { result.put(component.getName(), safe(component.getAccessor().invoke(value))); }
                catch (ReflectiveOperationException ex) { throw new IllegalStateException("无法读取SEO配置", ex); }
            }
            return result;
        }
        return value;
    }
}
