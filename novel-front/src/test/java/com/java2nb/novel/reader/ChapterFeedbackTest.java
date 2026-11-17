package com.java2nb.novel.reader;

import com.java2nb.novel.controller.ChapterFeedbackController;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.dto.ChapterFeedbackRequest;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.ChapterFeedbackService;
import com.java2nb.novel.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChapterFeedbackTest {
    private static final long BOOK = 2106418450629967872L, CHAPTER = 2106418564102668288L;
    private BookService books;
    private UserService users;
    private ChapterFeedbackService service;
    private Book book;
    private BookIndex index;
    private MockMvc mvc;

    @BeforeEach void setup() {
        books = mock(BookService.class); users = mock(UserService.class);
        book = new Book(); book.setId(BOOK); book.setBookName("真实书名");
        index = new BookIndex(); index.setId(CHAPTER); index.setBookId(BOOK); index.setIndexName("真实章节");
        when(books.queryBookDetail(BOOK)).thenReturn(book); when(books.queryBookIndex(CHAPTER)).thenReturn(index);
        service = new ChapterFeedbackService(books, users);
        var controller = new ChapterFeedbackController(service);
        var tokens = mock(JwtTokenUtil.class); var account = new UserDetails(); account.setId(9L);
        when(tokens.getUserDetailsFromToken("test-session")).thenReturn(account); controller.setJwtTokenUtil(tokens);
        var validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator)
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
    }

    @Test void anonymousCannotWrite() throws Exception {
        mvc.perform(post("/book/reader/feedback").param("bookId", "" + BOOK).param("bookIndexId", "" + CHAPTER).param("type", "missing"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001))
            .andExpect(header().string("Cache-Control", "private, no-store"));
        verifyNoInteractions(books, users);
    }

    @ParameterizedTest @ValueSource(strings = {"typo", "missing", "duplicate", "order", "other"})
    void savesAllTypesWithServerContextAndSessionOwner(String type) throws Exception {
        mvc.perform(post("/book/reader/feedback").header("Authorization", "test-session")
            .param("bookId", "" + BOOK).param("bookIndexId", "" + CHAPTER).param("type", type)
            .param("details", " 原文 <标记> & 错字\n第二行 ").param("userId", "88")
            .param("bookName", "伪造书名").param("chapterName", "伪造章节"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
            .andExpect(header().string("Vary", "Cookie, Authorization"));
        var text = ArgumentCaptor.forClass(String.class); verify(users).addFeedBack(eq(9L), text.capture());
        assertTrue(text.getValue().contains("书名：真实书名\n章节：真实章节"));
        assertTrue(text.getValue().contains("/book/" + BOOK + "/" + CHAPTER + ".html"));
        assertTrue(text.getValue().endsWith("说明：原文 <标记> & 错字\n第二行")); assertFalse(text.getValue().contains("伪造"));
    }

    @Test void wrongOwnershipOrMissingMetadataCannotWrite() throws Exception {
        index.setBookId(BOOK + 1);
        mvc.perform(post("/book/reader/feedback").header("Authorization", "test-session")
            .param("bookId", "" + BOOK).param("bookIndexId", "" + CHAPTER).param("type", "typo"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        when(books.queryBookDetail(BOOK)).thenThrow(new IndexOutOfBoundsException());
        assertThrows(ResponseStatusException.class, () -> service.submit(9L, request("typo", "")));
        verifyNoInteractions(users);
    }

    @Test void invalidOrTooLongInputsNeverReachStorage() throws Exception {
        for (String type : new String[]{"", "unknown", "<script>"}) {
            mvc.perform(post("/book/reader/feedback").header("Authorization", "test-session")
                .param("bookId", "" + BOOK).param("bookIndexId", "" + CHAPTER).param("type", type))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        mvc.perform(post("/book/reader/feedback").header("Authorization", "test-session")
            .param("bookId", "" + BOOK).param("bookIndexId", "" + CHAPTER).param("type", "typo").param("details", "字".repeat(201)))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/book/reader/feedback").header("Authorization", "test-session")
            .param("bookId", "0").param("bookIndexId", "-1").param("type", "typo")).andExpect(status().isBadRequest());
        mvc.perform(post("/book/reader/feedback").header("Authorization", "test-session").param("type", "typo"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(books, users);
    }

    @Test void otherRequiresMeaningfulDetailsAndTypedReportMayOmitThem() throws Exception {
        assertThrows(ResponseStatusException.class, () -> service.submit(9L, request("other", " \n ")));
        verifyNoInteractions(books, users);
        service.submit(9L, request("missing", null)); verify(users).addFeedBack(eq(9L), contains("未补充说明"));
    }

    @Test void longTitlesAndMaximumDetailsFitExistingInboxWithoutLosingIds() {
        book.setBookName("书".repeat(200)); index.setIndexName("章".repeat(200));
        service.submit(9L, request("missing", "字".repeat(200)));
        var text = ArgumentCaptor.forClass(String.class); verify(users).addFeedBack(eq(9L), text.capture());
        String saved = text.getValue(); assertTrue(saved.codePointCount(0, saved.length()) <= 512);
        assertTrue(saved.contains("/" + CHAPTER + ".html")); assertTrue(saved.endsWith("字".repeat(200)));
    }

    @Test void paidOrEmptyChapterCanBeReportedWithoutReadingOrPurchasing() throws Exception {
        index.setIsVip((byte) 1);
        mvc.perform(post("/book/reader/feedback").header("Authorization", "test-session")
            .param("bookId", "" + BOOK).param("bookIndexId", "" + CHAPTER).param("type", "missing"))
            .andExpect(status().isOk());
        verify(users).addFeedBack(eq(9L), anyString()); verifyNoMoreInteractions(users);
        verify(books).queryBookDetail(BOOK); verify(books).queryBookIndex(CHAPTER); verifyNoMoreInteractions(books);
    }

    private ChapterFeedbackRequest request(String type, String details) {
        var request = new ChapterFeedbackRequest(); request.setBookId(BOOK); request.setBookIndexId(CHAPTER);
        request.setType(type); request.setDetails(details); return request;
    }
}
