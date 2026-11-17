package com.java2nb.novel.service;

import com.java2nb.novel.entity.*;
import com.java2nb.novel.vo.ReaderChapterVO;
import com.java2nb.novel.seo.PublicBookPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** One authorization boundary for SSR, navigation and prefetch. Never purchases. */
@Service
@RequiredArgsConstructor
public class ReaderChapterService {
    private final BookService bookService;
    private final UserService userService;
    private final Map<String, BookContentService> contentServices;
    private ReaderChapterCache chapterCache=ReaderChapterCache.disabled();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setChapterCache(ReaderChapterCache cache){chapterCache=cache;}

    public record ChapterAccess(Book book, BookIndex index, BookContent content, ReaderChapterVO view) {}

    public ChapterAccess read(Long bookId, Long chapterId, Long userId) {
        if (bookId == null || chapterId == null || bookId <= 0 || chapterId <= 0) throw missing();
        Book book;
        BookIndex index;
        try {
            book = bookService.queryBookDetail(bookId);
            index = bookService.queryBookIndex(chapterId);
        } catch (IndexOutOfBoundsException | NoSuchElementException e) { throw missing(); }
        if (!PublicBookPolicy.isPublic(book) || index == null || !Objects.equals(index.getBookId(), bookId)) throw missing();
        String access = "allowed";
        if (Byte.valueOf((byte) 1).equals(index.getIsVip())) {
            if (userId == null) access = "need_login";
            else if (!userService.queryIsBuyBookIndex(userId, chapterId)) access = "need_purchase";
        }
        BookContent content = new BookContent();
        content.setContent("");
        List<ReaderChapterVO.Paragraph> paragraphs = List.of();
        String version = null;
        Long previous=null,next=null;
        // Do not even consult the storage service for unauthorized chapters.
        if ("allowed".equals(access)) {
            String storageType=index.getStorageType();
            BookContentService storage = contentServices.get(storageType==null||storageType.isBlank()?"db":storageType);
            if (storage == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "正文存储暂不可用");
            String kind=storageType==null||storageType.isBlank()?"db":storageType;
            ReaderChapterCache.Page page=chapterCache.read(bookId,chapterId,kind,index.getIndexNum(),()->load(bookId,chapterId,index,storage));
            content=page.content();paragraphs=page.paragraphs();version=page.version();previous=page.previous();next=page.next();
        } else {
            // Entitlement is always fresh; restricted responses never consult cached content.
            previous=bookService.queryPreBookIndexId(bookId,index.getIndexNum());
            next=bookService.queryNextBookIndexId(bookId,index.getIndexNum());
        }
        ReaderChapterVO view = new ReaderChapterVO(bookId.toString(), chapterId.toString(), book.getBookName(),
            index.getIndexName(), index.getIndexNum(), index.getBookPrice(),
            id(previous), id(next), version, access,
            userId == null ? "anonymous" : userId.toString(), paragraphs);
        return new ChapterAccess(book, index, content, view);
    }

    private ReaderChapterCache.Page load(long bookId,long chapterId,BookIndex index,BookContentService storage){
        BookContent content=storage.queryBookContent(bookId,chapterId);
        if(content==null || content.getContent()==null)throw missing();
        var paragraphs=paragraphs(content.getContent());if(paragraphs.isEmpty())throw missing();
        var navigation=chapterCache.navigation(bookId,index.getIndexNum(),()->new ReaderChapterCache.Navigation(
            bookService.queryPreBookIndexId(bookId,index.getIndexNum()),bookService.queryNextBookIndexId(bookId,index.getIndexNum())));
        return new ReaderChapterCache.Page(content.getId(),content.getIndexId(),content.getContent(),paragraphs,
            digest(paragraphs),navigation.previous(),navigation.next());
    }

    public static String id(Long id) { return id == null || id == 0 ? null : id.toString(); }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "书籍或章节不存在");
    }
    public static List<ReaderChapterVO.Paragraph> paragraphs(String html) {
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1\\s*>", "")
            .replaceAll("(?i)<br\\s*/?>|</(?:p|div|li|h[1-6])\\s*>", "\n")
            .replaceAll("(?s)<!--.*?-->", "")
            .replaceAll("</?[A-Za-z][^>]*>", "");
        text = HtmlUtils.htmlUnescape(text).replace('\u00a0', ' ').replace("\r", "");
        List<ReaderChapterVO.Paragraph> result = new ArrayList<>();
        for (String line : text.split("\n+")) {
            String value = line.strip();
            if (!value.isBlank()) result.add(new ReaderChapterVO.Paragraph("p" + result.size(), value));
        }
        return List.copyOf(result);
    }
    private static String digest(List<ReaderChapterVO.Paragraph> paragraphs) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(("reader-text-v1\n" +
                String.join("\n", paragraphs.stream().map(ReaderChapterVO.Paragraph::text).toList()))
                .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
