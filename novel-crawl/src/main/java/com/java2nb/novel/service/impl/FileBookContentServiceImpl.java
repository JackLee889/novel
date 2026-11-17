package com.java2nb.novel.service.impl;

import com.java2nb.novel.core.utils.FileUtil;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.service.BookContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.nio.charset.Charset;

@Service(value = "txt")
@RequiredArgsConstructor
public class FileBookContentServiceImpl implements BookContentService {

    @Value("${content.save.path}")
    private String fileSavePath;

    @Override
    public void saveBookContent(List<BookContent> bookContentList,Long bookId) {
        bookContentList.forEach(bookContent -> saveBookContent(bookContent,bookId));

    }

    @Override
    public void saveBookContent(BookContent bookContent,Long bookId) {
        writeContentAtomically(bookContent, bookId);
    }

    @Override
    public void updateBookContent(BookContent bookContent,Long bookId) {
        writeContentAtomically(bookContent, bookId);
    }

    @Override
    public boolean sameContent(BookContent content,Long bookId) {
        Path target=Paths.get(fileSavePath).resolve(bookId.toString()).resolve(content.getIndexId()+".txt");
        if(!Files.exists(target))return false;
        try{return Objects.equals(Files.readString(target,Charset.defaultCharset()),content.getContent());}
        catch(IOException error){throw new UncheckedIOException("读取原章节失败: "+target,error);}
    }

    /**
     * [审查18] 原来直接覆盖目标 txt 文件：写入中途失败会留下截断内容，而数据库事务回滚无法恢复文件，
     * 于是文件与数据库目录/字数不一致。
     * 现在先写到临时文件，成功后再原子替换 —— 目标文件要么是旧的完整版本，要么是新的完整版本。
     * 内容仍交给 FileUtil 写入，保持原有编码与目录结构不变。
     */
    private void writeContentAtomically(BookContent bookContent, Long bookId) {
        String realRel = "/" + bookId + "/" + bookContent.getIndexId() + ".txt";
        String tmpRel = realRel + ".tmp";

        FileUtil.writeContentToFile(fileSavePath, tmpRel, bookContent.getContent());

        Path tmp = Paths.get(fileSavePath, tmpRel);
        Path target = Paths.get(fileSavePath, realRel);
        try {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("替换章节文件失败: " + target, e);
        }
    }
}
