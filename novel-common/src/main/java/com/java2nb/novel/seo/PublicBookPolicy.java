package com.java2nb.novel.seo;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookIndex;

/** The same publishing boundary applies to people, APIs and search crawlers. */
public final class PublicBookPolicy {
    private PublicBookPolicy() {}
    public static boolean isPublic(Book book) {
        return book != null && Byte.valueOf((byte) 1).equals(book.getStatus());
    }
    public static boolean publicChapter(Book book, BookIndex chapter) {
        return isPublic(book) && book.getId()!=null && chapter != null && book.getId().equals(chapter.getBookId())
            && !Byte.valueOf((byte) 1).equals(chapter.getIsVip())
            && chapter.getWordCount() != null && chapter.getWordCount() > 0;
    }
}
