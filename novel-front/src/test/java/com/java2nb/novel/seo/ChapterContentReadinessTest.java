package com.java2nb.novel.seo;

import com.java2nb.novel.service.ReaderChapterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChapterContentReadinessTest {
    @TempDir Path root;
    @Test void readinessUsesTheReadersVisibleParagraphRules(){
        for(String value:List.of("", " \r\n\t", "<p>&nbsp;</p><br/>", "<p>&ensp;&#8194;&#x2003;</p>", "<script>hidden</script><style>p{}</style><!--comment-->", "<p>真实正文</p>", "文本\n第二段", "&lt;字&gt;", "<img src='/x'>")){
            assertEquals(!ReaderChapterService.paragraphs(value).isEmpty(),ChapterContentReadiness.hasReadableText(value),value);
        }
        assertFalse(ChapterContentReadiness.hasReadableText(null));
    }
    @Test void txtValidationRejectsWhitespaceMarkupMalformedUtf8AndOversizedFiles()throws Exception{
        Path book=root.resolve("7");Files.createDirectories(book);Path file=book.resolve("8.txt");
        assertFalse(ChapterContentReadiness.fileReady(root,7,8));
        for(String empty:List.of("", " \r\n\t", "<p>&nbsp;&#8194;</p>", "<script>hidden</script><style>p{}</style><!--comment-->")){
            Files.writeString(file,empty,StandardCharsets.UTF_8);assertFalse(ChapterContentReadiness.fileReady(root,7,8));
        }
        Files.write(file,new byte[]{(byte)0xc3,(byte)0x28});assertFalse(ChapterContentReadiness.fileReady(root,7,8));
        Files.write(file,new byte[ChapterContentReadiness.MAX_TXT_BYTES+1]);assertFalse(ChapterContentReadiness.fileReady(root,7,8));
        Files.writeString(file,"<p>正文一</p>\r\n<p>正文二</p>",StandardCharsets.UTF_8);assertTrue(ChapterContentReadiness.fileReady(root,7,8));
        assertFalse(ChapterContentReadiness.fileReady(root,0,8));assertFalse(ChapterContentReadiness.fileReady(root,7,-8));
    }
    @Test void txtReadinessMatchesTheLegacyLineConcatenationBeforeReaderParsing()throws Exception{
        Path book=root.resolve("7");Files.createDirectories(book);Path file=book.resolve("8.txt");String content="<p>\n&nbsp;\r\n</p><script>\nhidden\n</script>";
        Files.writeString(file,content,StandardCharsets.UTF_8);
        String loaded=String.join("",Files.readAllLines(file,StandardCharsets.UTF_8));
        assertEquals(!ReaderChapterService.paragraphs(loaded).isEmpty(),ChapterContentReadiness.fileReady(root,7,8));
    }
}
