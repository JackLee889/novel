package com.java2nb.novel.vo;

import java.util.List;

/** IDs intentionally remain strings, including in SSR bootstrap JSON. */
public record ReaderChapterVO(String bookId, String bookIndexId, String bookName,
    String chapterName, Integer chapterNumber, Integer price, String previousChapterId,
    String nextChapterId, String contentVersion, String access, String accountScope,
    List<Paragraph> paragraphs) {
    public record Paragraph(String id, String text) {}
}
