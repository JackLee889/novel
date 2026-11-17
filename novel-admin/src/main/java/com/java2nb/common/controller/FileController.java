package com.java2nb.common.controller;

import com.java2nb.common.config.Constant;
import com.java2nb.common.config.JnConfig;
import com.java2nb.common.domain.FileDO;
import com.java2nb.common.service.FileService;
import com.java2nb.common.security.UploadPathResolver;
import com.java2nb.common.utils.*;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.Channels;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;

/**
 * 文件上传
 *
 * @author xiongxy
 * @email 1179705413@qq.com
 * @date 2019-09-19 16:02:20
 */
@Controller
@RequestMapping("/common/sysFile")
public class FileController extends BaseController {

    @Autowired
    private FileService sysFileService;

    @Autowired
    private JnConfig jnConfig;

    @Autowired
    private UploadPathResolver uploadPaths;

    @GetMapping()
    @RequiresPermissions("common:sysFile:sysFile")
    String sysFile(Model model) {
        Map<String, Object> params = new HashMap<>(16);
        return "common/file/file";
    }

    @ResponseBody
    @GetMapping("/list")
    @RequiresPermissions("common:sysFile:sysFile")
    public PageBean list(@RequestParam Map<String, Object> params) {
        // 查询列表数据
        Query query = new Query(params);
        List<FileDO> sysFileList = sysFileService.list(query);
        int total = sysFileService.count(query);
        PageBean pageBean = new PageBean(sysFileList, total);
        return pageBean;
    }

    @GetMapping("/add")
    @RequiresPermissions("common:sysFile:sysFile")
    String add() {
        return "common/sysFile/add";
    }

    @GetMapping("/edit")
    @RequiresPermissions("common:sysFile:sysFile")
    String edit(Long id, Model model) {
        FileDO sysFile = sysFileService.get(id);
        model.addAttribute("sysFile", sysFile);
        return "common/sysFile/edit";
    }

    /**
     * 信息
     */
    @RequestMapping("/info/{id}")
    @RequiresPermissions("common:info")
    public R info(@PathVariable("id") Long id) {
        FileDO sysFile = sysFileService.get(id);
        return R.ok().put("sysFile", sysFile);
    }

    /**
     * 保存
     */
    @ResponseBody
    @PostMapping("/save")
    @RequiresPermissions("common:save")
    public R save(FileDO sysFile) {
        if (sysFileService.save(sysFile) > 0) {
            return R.ok();
        }
        return R.error();
    }

    /**
     * 修改
     */
    @PostMapping("/update")
    @RequiresPermissions("common:update")
    public R update(@RequestBody FileDO sysFile) {
        sysFileService.update(sysFile);

        return R.ok();
    }

    /**
     * 删除
     */
    @PostMapping("/remove")
    @ResponseBody
    @RequiresPermissions("common:remove")
    public R remove(Long id, HttpServletRequest request) {
        if ("test".equals(getUsername())) {
            return R.error(1, "演示系统不允许修改,完整体验请部署程序");
        }
        if (sysFileService.remove(id) > 0) {
            return R.ok();
        } else {
            return R.error();
        }
    }

    /**
     * 删除
     */
    @PostMapping("/batchRemove")
    @ResponseBody
    @RequiresPermissions("common:remove")
    public R remove(@RequestParam("ids[]") Long[] ids) {
        if ("test".equals(getUsername())) {
            return R.error(1, "演示系统不允许修改,完整体验请部署程序");
        }
        sysFileService.batchRemove(ids);
        return R.ok();
    }

    @ResponseBody
    @PostMapping("/upload")
    @RequiresAuthentication
    R upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        if ("test".equals(getUsername())) {
            return R.error(1, "演示系统不允许修改,完整体验请部署程序");
        }
        Date date = new Date();
        String year = DateUtils.format(date, DateUtils.YEAR_PATTERN);
        String month = DateUtils.format(date, DateUtils.MONTH_PATTERN);
        String day = DateUtils.format(date, DateUtils.DAY_PATTERN);

        String originalName = file.getOriginalFilename();
        if (file.isEmpty() || originalName == null || !originalName.contains(".")) {
            return R.error(400, "请选择有效的文件");
        }
        String extension = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("jpg", "jpeg", "png", "gif", "webp", "pdf", "txt", "zip", "docx", "xlsx").contains(extension)) {
            return R.error(400, "不支持的文件类型");
        }
        String fileName = UUID.randomUUID() + "." + extension;
        String fileDir = year + "/" + month + "/" + day + "/";
        FileDO sysFile = new FileDO(FileType.fileType(fileName), Constant.UPLOAD_FILES_PREFIX + fileDir + fileName,
            date);
        try {
            Path target = uploadPaths.resolve(fileDir + fileName);
            Files.createDirectories(target.getParent());
            // Validate again after creating parents; never follow a substituted file link.
            target = uploadPaths.resolve(fileDir + fileName);
            try (OutputStream stream = Channels.newOutputStream(Files.newByteChannel(target,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)));
                    InputStream input = file.getInputStream()) {
                input.transferTo(stream);
            }
        } catch (Exception e) {
            return R.error();
        }

        if (sysFileService.save(sysFile) > 0) {
            return R.ok().put("fileName", sysFile.getUrl());
        }
        return R.error();
    }

    /**
     * 文件下载
     */
    @GetMapping(value = "/download")
    @RequiresPermissions("common:sysFile:sysFile")
    public void fileDownload(String filePath, String fileName, HttpServletResponse resp) throws Exception {
        Path target = uploadPaths.existingFile(filePath);
        String downloadName = fileName == null || fileName.isBlank() ? target.getFileName().toString() : fileName;
        if (downloadName.indexOf('\r') >= 0 || downloadName.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("文件名称无效");
        }
        resp.setHeader("Content-Disposition", ContentDisposition.attachment()
                .filename(downloadName, StandardCharsets.UTF_8).build().toString());
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setContentType("application/octet-stream");
        resp.setContentLengthLong(Files.size(target));
        try (InputStream in = Channels.newInputStream(Files.newByteChannel(target,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            in.transferTo(resp.getOutputStream());
        }
    }


}
