package com.java2nb.novel.seo;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookIndex;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

/** Public metadata policy, independent of User-Agent and the incoming host. */
@Service
@RequiredArgsConstructor
public class SeoService {
    private final SeoProperties properties;
    private final SeoRepository repository;
    public String publicSiteUrl() { return SeoValidation.publicOrigin(properties.getSiteUrl()); }
    public SeoSiteSettings settings() {
        if (!properties.isEnabled()) return SeoSiteSettings.disabled();
        try { return repository.settings(); } catch(DataAccessException e) { return SeoSiteSettings.disabled(); }
    }
    public boolean siteIndexEnabled() { return properties.isEnabled() && !publicSiteUrl().isEmpty() && settings().indexEnabled(); }
    public boolean isPublic(Book book) { return PublicBookPolicy.isPublic(book); }
    public SeoBookOverride bookOverride(long bookId) {
        if (!properties.isEnabled()) return SeoBookOverride.inherited(bookId);
        try { return repository.bookOverride(bookId); } catch(DataAccessException e) { return new SeoBookOverride(bookId,false,"","",""); }
    }
    public boolean isIndexable(Book book) {return siteIndexEnabled() && isPublic(book) && book.getLastIndexId()!=null && book.getWordCount()!=null && book.getWordCount()>0 && !Boolean.FALSE.equals(bookOverride(book.getId()).indexEnabled());}
    public boolean chapterIndexable(Book book,BookIndex chapter) {return isIndexable(book) && settings().chaptersIndexEnabled() && PublicBookPolicy.publicChapter(book,chapter);}
    public void markBookDirty(long bookId) { if(properties.isEnabled()) repository.markBookDirty(bookId); }
    public void lockMutation() {if(properties.isEnabled())repository.lockMutation();}
    public void markBookPublished(long bookId) { markBookDirty(bookId); }
    public int catalogPageSize() { return Math.max(1,Math.min(200,properties.getCatalogPageSize())); }
}
