package com.java2nb.novel.service;

import com.java2nb.novel.dto.ChapterFeedbackRequest;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookIndex;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Uses the existing member feedback inbox, without reading or changing chapter content. */
@Service
@RequiredArgsConstructor
public class ChapterFeedbackService {
    private static final Map<String, String> TYPES = Map.of(
        "typo", "错字 / 乱码", "missing", "内容缺失 / 正文为空", "duplicate", "重复内容",
        "order", "章节顺序 / 标题", "other", "其他问题");
    private final BookService books;
    private final UserService users;

    public void submit(Long userId, ChapterFeedbackRequest request) {
        if (userId == null || userId <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后反馈");
        if (request.getBookId() == null || request.getBookId() <= 0 || request.getBookIndexId() == null || request.getBookIndexId() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效章节");
        String type = request.getType() == null ? null : TYPES.get(request.getType());
        String details = request.getDetails() == null ? "" : request.getDetails().strip();
        if (type == null || details.length() > 200 || ("other".equals(request.getType()) && details.isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择问题类型；其他问题请填写说明，最多 200 字");
        Book book;
        BookIndex index;
        try {
            book = books.queryBookDetail(request.getBookId());
            index = books.queryBookIndex(request.getBookIndexId());
        } catch (IndexOutOfBoundsException | NoSuchElementException error) {
            throw missing();
        }
        if (book == null || index == null || !Objects.equals(index.getBookId(), request.getBookId())) throw missing();
        String content = "【章节报错】\n类型：" + type + "\n书名：" + label(book.getBookName(), 60)
            + "\n章节：" + label(index.getIndexName(), 100) + "\n页面：/book/" + request.getBookId()
            + "/" + request.getBookIndexId() + ".html\n说明：" + (details.isEmpty() ? "未补充说明" : details);
        // Keep complete chapter IDs and the report inside the existing varchar(512) inbox.
        if (content.codePointCount(0, content.length()) > 512)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "补充说明过长，请缩短后重试");
        users.addFeedBack(userId, content);
    }

    private static String label(String value, int limit) {
        String text = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
        return text.codePointCount(0, text.length()) <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit - 1)) + "…";
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "书籍或章节不存在，或章节不属于该书");
    }
}
