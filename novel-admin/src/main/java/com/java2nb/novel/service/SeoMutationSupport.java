package com.java2nb.novel.service;

import com.java2nb.novel.domain.BookDO;
import com.java2nb.novel.domain.BookIndexDO;
import com.java2nb.novel.seo.SeoRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Called inside existing business write transactions, never after an independent commit. */
@Service
public class SeoMutationSupport {
    private final SeoRepository repository;
    public SeoMutationSupport(SeoRepository repository) { this.repository = repository; }
    public void begin() { repository.lockMutation(); }
    public void dirty(Long bookId) { if (bookId != null && bookId > 0) repository.markBookDirty(bookId); }
    public void removed(Long bookId) { if (bookId != null && bookId > 0) repository.markBookRemoved(bookId); }
    public static boolean bookChanged(BookDO before, BookDO change) {
        if (before == null) return false;
        return changed(before.getBookName(), change.getBookName()) || changed(before.getAuthorName(), change.getAuthorName())
            || changed(before.getBookDesc(), change.getBookDesc()) || changed(before.getPicUrl(), change.getPicUrl())
            || changed(before.getStatus(), change.getStatus()) || changed(before.getIsVip(), change.getIsVip())
            || changed(before.getWorkDirection(), change.getWorkDirection()) || changed(before.getCatId(), change.getCatId())
            || changed(before.getCatName(), change.getCatName()) || changed(before.getBookStatus(), change.getBookStatus())
            || changed(before.getWordCount(), change.getWordCount())
            || changed(before.getLastIndexId(), change.getLastIndexId()) || changed(before.getLastIndexName(), change.getLastIndexName());
    }
    public static boolean indexChanged(BookIndexDO before, BookIndexDO change) {
        if (before == null) return false;
        return changed(before.getBookId(), change.getBookId()) || changed(before.getIndexNum(), change.getIndexNum())
            || changed(before.getIndexName(), change.getIndexName()) || changed(before.getIsVip(), change.getIsVip())
            || changed(before.getBookPrice(), change.getBookPrice()) || changed(before.getStorageType(), change.getStorageType())
            || changed(before.getWordCount(), change.getWordCount());
    }
    public static boolean changed(Object before, Object supplied) { return supplied != null && !Objects.equals(before, supplied); }
}
