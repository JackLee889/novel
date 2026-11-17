package com.java2nb.common.security;

import com.java2nb.common.config.Constant;
import com.java2nb.common.config.JnConfig;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** The configured upload directory is the sole boundary for file operations. */
@Component
public class UploadPathResolver {
    private final JnConfig config;

    public UploadPathResolver(JnConfig config) { this.config = config; }

    public Path resolve(String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.length() > 4096
                || relative.indexOf('\\') >= 0 || relative.indexOf('%') >= 0
                || relative.indexOf('\0') >= 0 || relative.indexOf(':') >= 0
                || relative.startsWith("/")) {
            throw new IllegalArgumentException("文件路径无效");
        }
        Path input = Path.of(relative);
        for (Path part : input) {
            if (part.toString().equals("..") || part.toString().equals(".")) {
                throw new IllegalArgumentException("文件路径越界");
            }
        }
        Path root = Path.of(config.getUploadPath()).toRealPath();
        Path target = root.resolve(input).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("文件路径越界");
        }
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("文件路径不能包含符号链接");
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().startsWith(root)) {
                throw new IllegalArgumentException("文件路径越界");
            }
        }
        return target;
    }

    public Path resolveUrl(String url) throws IOException {
        if (url == null || !url.startsWith(Constant.UPLOAD_FILES_PREFIX)) {
            throw new IllegalArgumentException("上传文件地址无效");
        }
        return resolve(url.substring(Constant.UPLOAD_FILES_PREFIX.length()));
    }

    public Path existingFile(String relative) throws IOException {
        Path target = resolve(relative);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.NoSuchFileException(relative);
        }
        return target;
    }
}
