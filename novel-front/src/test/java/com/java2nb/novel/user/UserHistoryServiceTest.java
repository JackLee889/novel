package com.java2nb.novel.user;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.entity.User;
import com.java2nb.novel.entity.UserReadHistory;
import com.java2nb.novel.mapper.*;
import com.java2nb.novel.service.impl.UserServiceImpl;
import io.github.xxyopen.web.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.dynamic.sql.update.render.UpdateStatementProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserHistoryServiceTest {
    private UserServiceImpl service;
    private FrontUserMapper users;
    private FrontUserReadHistoryMapper history;
    private FrontUserBookshelfMapper shelf;
    private FrontBookMapper books;
    private BookIndexMapper chapters;

    @BeforeEach
    void setup() {
        users = mock(FrontUserMapper.class);history = mock(FrontUserReadHistoryMapper.class);
        shelf = mock(FrontUserBookshelfMapper.class);books = mock(FrontBookMapper.class);
        chapters = mock(BookIndexMapper.class);
        // Run the real methods without initializing the unrelated, host-MAC-dependent IdWorker.
        service = mock(UserServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "userBookshelfMapper", shelf);
        ReflectionTestUtils.setField(service, "userReadHistoryMapper", history);
        ReflectionTestUtils.setField(service, "bookMapper", books);
        ReflectionTestUtils.setField(service, "bookIndexMapper", chapters);
        when(books.selectByPrimaryKey(1L)).thenReturn(Optional.of(new Book()));
        BookIndex chapter = new BookIndex();chapter.setId(2L);chapter.setBookId(1L);
        when(chapters.selectByPrimaryKey(2L)).thenReturn(Optional.of(chapter));
    }

    @Test
    void nullAndNonPositiveInputsLeaveAllExistingRecordsUntouched() {
        assertThrows(BusinessException.class, () -> service.addReadHistory(9L, 1L, null));
        assertThrows(BusinessException.class, () -> service.addReadHistory(9L, -1L, 2L));
        assertThrows(BusinessException.class, () -> service.addReadHistory(null, 1L, 2L));
        verifyNoInteractions(history, shelf, books, chapters);
    }

    @Test
    void nonexistentBookAndChapterAreRejectedBeforeAnyWrite() {
        assertThrows(BusinessException.class, () -> service.addReadHistory(9L, 99L, 2L));
        assertThrows(BusinessException.class, () -> service.addReadHistory(9L, 1L, 99L));
        verifyNoInteractions(history, shelf);
    }

    @Test
    void crossBookChapterCannotOverwriteHistoryOrShelf() {
        BookIndex foreign = new BookIndex();foreign.setBookId(3L);
        when(chapters.selectByPrimaryKey(2L)).thenReturn(Optional.of(foreign));
        assertThrows(BusinessException.class, () -> service.addReadHistory(9L, 1L, 2L));
        verifyNoInteractions(history, shelf);
    }

    @Test
    void validChapterUpdatesHistoryAndShelfInsideTheExistingFlow() {
        service.addReadHistory(9L, 1L, 2L);
        ArgumentCaptor<UserReadHistory> saved = ArgumentCaptor.forClass(UserReadHistory.class);
        verify(history).insertSelective(saved.capture());
        assertEquals(9L, saved.getValue().getUserId());
        assertEquals(1L, saved.getValue().getBookId());
        assertEquals(2L, saved.getValue().getPreContentId());
        assertNotNull(saved.getValue().getUpdateTime());
        verify(shelf).update(any(UpdateStatementProvider.class));
    }

    @Test
    void serviceRejectsMissingPasswordsBeforeHashingOrQuerying() {
        assertThrows(BusinessException.class, () -> service.login(null));
        User user = new User();user.setUsername("19910710411");
        assertThrows(BusinessException.class, () -> service.login(user));
        user.setPassword("   ");
        assertThrows(BusinessException.class, () -> service.login(user));
        verifyNoInteractions(users);
    }

    @Test
    void invalidPageSizesNeverReachTheMapper() {
        assertThrows(BusinessException.class, () -> service.listReadHistoryByPage(9L, 0, 12));
        assertThrows(BusinessException.class, () -> service.listReadHistoryByPage(9L, 1, 0));
        assertThrows(BusinessException.class, () -> service.listReadHistoryByPage(9L, 1, 101));
        verifyNoInteractions(history);
    }
}
