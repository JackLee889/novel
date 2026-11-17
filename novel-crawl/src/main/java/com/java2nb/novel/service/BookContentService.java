package com.java2nb.novel.service;

import com.java2nb.novel.entity.BookContent;

import java.util.List;

public interface BookContentService {

    void saveBookContent(List<BookContent> bookContentList,Long bookId);

    void saveBookContent(BookContent bookContent,Long bookId);

    void updateBookContent(BookContent bookContent,Long bookId);

    /** Compare persisted content only when SEO change tracking is enabled. Unknown backends report a revision. */
    default boolean sameContent(BookContent content,Long bookId) { return false; }


}
