package com.java2nb.common.service.impl;

import com.java2nb.common.config.Constant;
import com.java2nb.common.config.JnConfig;
import com.java2nb.common.dao.FileDao;
import com.java2nb.common.domain.FileDO;
import com.java2nb.common.service.FileService;
import com.java2nb.common.security.UploadPathResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


@Service
public class FileServiceImpl implements FileService {

    @Autowired
    private FileDao sysFileMapper;

    @Autowired
    private JnConfig jnConfig;

    @Autowired
    private UploadPathResolver uploadPaths;

    @Override
    public FileDO get(Long id) {
        return sysFileMapper.get(id);
    }

    @Override
    public List<FileDO> list(Map<String, Object> map) {
        return sysFileMapper.list(map);
    }

    @Override
    public int count(Map<String, Object> map) {
        return sysFileMapper.count(map);
    }

    @Override
    public int save(FileDO sysFile) {
        validateUrl(sysFile.getUrl());
        return sysFileMapper.save(sysFile);
    }

    @Override
    public int update(FileDO sysFile) {
        validateUrl(sysFile.getUrl());
        return sysFileMapper.update(sysFile);
    }

    @Override
    public int remove(Long id) {
        FileDO record = sysFileMapper.get(id);
        if (record == null) { return 0; }
        Path target = validateUrl(record.getUrl());
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalArgumentException("删除上传文件失败", e);
        }
        return sysFileMapper.remove(id);
    }

    @Override
    public int batchRemove(Long[] ids) {
        for (Long id : ids) {
            FileDO record = sysFileMapper.get(id);
            if (record != null) { validateUrl(record.getUrl()); }
        }
        int count = 0;
        for (Long id : ids) { count += remove(id); }
        return count;
    }

    @Override
    public Boolean isExist(String url) {
        try {
            return Files.isRegularFile(uploadPaths.resolveUrl(url), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private Path validateUrl(String url) {
        try { return uploadPaths.resolveUrl(url); }
        catch (IOException e) { throw new IllegalArgumentException("上传目录不可用", e); }
    }
}
