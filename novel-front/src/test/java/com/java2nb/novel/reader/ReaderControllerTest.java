package com.java2nb.novel.reader;
import com.java2nb.novel.controller.*;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReaderControllerTest {
    MockMvc mvc; BookService books; BookContentService storage; UserService users; BookIndex index;
    @BeforeEach void init() {
        books=mock(BookService.class);users=mock(UserService.class);storage=mock(BookContentService.class);
        Book book=new Book();book.setId(1L);book.setStatus((byte)1);book.setBookName("原创测试");when(books.queryBookDetail(1L)).thenReturn(book);
        index=new BookIndex();index.setId(2L);index.setBookId(1L);index.setIndexNum(0);index.setStorageType("db");index.setIsVip((byte)1);
        when(books.queryBookIndex(2L)).thenReturn(index);
        var c=new BookContent();c.setContent("付费私有正文");when(storage.queryBookContent(1L,2L)).thenReturn(c);
        JwtTokenUtil tokens=mock(JwtTokenUtil.class);UserDetails u=new UserDetails();u.setId(9L);
        when(tokens.getUserDetailsFromToken("user")).thenReturn(u);
        var service=new ReaderChapterService(books,users,Map.of("db",storage));
        var controller=new ReaderController(service,books,mock(com.java2nb.novel.seo.SeoMetadataService.class));controller.setJwtTokenUtil(tokens);
        var legacy=new BookController(books,service,mock(IpLocationService.class),mock(LikeService.class));legacy.setJwtTokenUtil(tokens);
        mvc=MockMvcBuilders.standaloneSetup(controller,legacy)
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
            .build();
    }
    @Test void paidJsonAnonymousAndUnpaidDoNotLeak() throws Exception {
        mvc.perform(get("/book/reader/chapter").param("bookId","1").param("bookIndexId","2"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"))
            .andExpect(jsonPath("$.code").value(1001)).andExpect(jsonPath("$.data.paragraphs").isEmpty());
        mvc.perform(get("/book/reader/chapter").param("bookId","1").param("bookIndexId","2").header("Authorization","user"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(5002))
            .andExpect(jsonPath("$.data.contentVersion").isEmpty());verifyNoInteractions(storage);
    }
    @Test void legacyPreviewAlsoUsesAuthorization() throws Exception {
        mvc.perform(get("/book/queryBookIndexAbout").accept("application/json").param("bookId","1").param("lastBookIndexId","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastBookContent").value(""));
        verifyNoInteractions(storage);
        when(users.queryIsBuyBookIndex(9L,2L)).thenReturn(true);
        mvc.perform(get("/book/queryBookIndexAbout").accept("application/json").param("bookId","1").param("lastBookIndexId","2").header("Authorization","user"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastBookContent").value("付费私有正文"));
    }
    @Test void wrongOwnershipHasExplicitFailureEnvelope() throws Exception {
        index.setBookId(3L);
        mvc.perform(get("/book/reader/chapter").param("bookId","1").param("bookIndexId","2"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(5003))
            .andExpect(header().string("Cache-Control","private, no-store"));verifyNoInteractions(storage);
        when(books.queryBookDetail(3L)).thenThrow(new IndexOutOfBoundsException());
        mvc.perform(get("/book/reader/catalog").param("bookId","3"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(5003))
            .andExpect(header().string("Cache-Control","private, no-store"));
    }
}
