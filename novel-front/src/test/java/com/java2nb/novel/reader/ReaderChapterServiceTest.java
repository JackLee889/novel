package com.java2nb.novel.reader;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.service.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReaderChapterServiceTest {
    BookService books; UserService users; BookContentService storage; ReaderChapterService service;
    BookIndex index;
    @BeforeEach void init() {
        books=mock(BookService.class);users=mock(UserService.class);storage=mock(BookContentService.class);
        service=new ReaderChapterService(books,users,Map.of("db",storage));
        var book=new Book();book.setId(1L);book.setStatus((byte)1);book.setBookName("原创测试");when(books.queryBookDetail(1L)).thenReturn(book);
        index=new BookIndex();index.setId(9007199254740993L);index.setBookId(1L);index.setIndexNum(0);index.setStorageType("db");
        when(books.queryBookIndex(index.getId())).thenReturn(index);
        var content=new BookContent();content.setContent("<p>第一段 &lt;测试&gt;。</p><script>危险内容</script><p>第二段<br>第三行。</p>");
        when(storage.queryBookContent(1L,index.getId())).thenReturn(content);
    }
    @Test void freeAnonymousAndStringIds() {
        var v=service.read(1L,index.getId(),null).view();
        assertEquals("9007199254740993",v.bookIndexId());assertEquals("allowed",v.access());
        assertEquals(3,v.paragraphs().size());assertEquals("第一段 <测试>。",v.paragraphs().getFirst().text());
        assertEquals(64,v.contentVersion().length());assertFalse(v.paragraphs().toString().contains("危险内容"));
    }
    @Test void anonymousVipNeverReadsStorage() {
        index.setIsVip((byte)1);var v=service.read(1L,index.getId(),null).view();
        assertEquals("need_login",v.access());assertTrue(v.paragraphs().isEmpty());assertNull(v.contentVersion());
        verifyNoInteractions(storage,users);
    }
    @Test void unpaidNeverReadsStorage() {
        index.setIsVip((byte)1);var v=service.read(1L,index.getId(),2L).view();
        assertEquals("need_purchase",v.access());assertTrue(v.paragraphs().isEmpty());verifyNoInteractions(storage);
    }
    @Test void purchasedAndFileStoreDispatch() {
        index.setIsVip((byte)1);index.setStorageType("file");
        service=new ReaderChapterService(books,users,Map.of("file",storage));
        when(users.queryIsBuyBookIndex(2L,index.getId())).thenReturn(true);
        assertEquals("allowed",service.read(1L,index.getId(),2L).view().access());
        verify(storage).queryBookContent(1L,index.getId());
    }
    @Test void bookOwnershipMismatchNeverReadsStorage() {
        index.setBookId(2L);assertThrows(ResponseStatusException.class,()->service.read(1L,index.getId(),2L));
        verifyNoInteractions(storage,users);
    }
    @Test void absentAndInvalidIdsFailClosed() {
        assertThrows(ResponseStatusException.class,()->service.read(1L,10L,null));
        assertThrows(ResponseStatusException.class,()->service.read(0L,index.getId(),null));
        when(books.queryBookIndex(index.getId())).thenThrow(new IndexOutOfBoundsException());
        assertThrows(ResponseStatusException.class,()->service.read(1L,index.getId(),null));verifyNoInteractions(storage);
    }
    @Test void unpublishedNeverReadsStorageEvenWhenLegacyQueryReturnsIt() {
        var hidden=new Book();hidden.setId(1L);hidden.setStatus((byte)0);when(books.queryBookDetail(1L)).thenReturn(hidden);
        assertThrows(ResponseStatusException.class,()->service.read(1L,index.getId(),null));verifyNoInteractions(storage,users);
    }
    @Test void emptyRenderedBodyIsNotAValidChapter() {
        var empty=new BookContent();empty.setContent("<script>bad</script><p> </p>");when(storage.queryBookContent(1L,index.getId())).thenReturn(empty);
        assertThrows(ResponseStatusException.class,()->service.read(1L,index.getId(),null));
    }
}
