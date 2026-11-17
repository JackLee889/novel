package com.java2nb.novel.reader;
import tools.jackson.databind.ObjectMapper;
import com.java2nb.novel.controller.page.PageController;
import com.java2nb.novel.core.config.ReaderProperties;
import com.java2nb.novel.core.utils.ThreadLocalUtil;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.service.*;
import com.java2nb.novel.vo.ReaderChapterVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.ui.ExtendedModelMap;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReaderPageTest {
    @Test void ssrUsesSharedAccessAndEscapesBootstrap() throws Exception {
        var service=mock(ReaderChapterService.class); var props=new ReaderProperties();
        var view=new ReaderChapterVO("1","9007199254740993","</script><script>bad</script>","章节",0,0,null,null,"v1","allowed","anonymous",
            List.of(new ReaderChapterVO.Paragraph("p0","原文 <测试>")));
        when(service.read(1L,9007199254740993L,null)).thenReturn(new ReaderChapterService.ChapterAccess(new Book(),new BookIndex(),new BookContent(),view));
        var controller=new PageController(mock(BookService.class),service,props,new ObjectMapper(),mock(com.java2nb.novel.seo.SeoMetadataService.class),
            mock(NewsService.class),mock(AuthorService.class),mock(UserService.class),mock(ThreadPoolExecutor.class),Map.of());
        var model=new ExtendedModelMap();var response=new MockHttpServletResponse();
        assertEquals("book/reader",controller.bookContent(1L,9007199254740993L,new MockHttpServletRequest(),response,model));
        String json=(String)model.get("readerJson");assertFalse(json.contains("<"));assertTrue(json.contains("\\u003c"));
        assertEquals("9007199254740993",new ObjectMapper().readTree(json).get("bookIndexId").asText());
        assertEquals("private, no-store",response.getHeader("Cache-Control"));verify(service).read(1L,9007199254740993L,null);
    }
    @Test void legacyRollbackRetainsPermissionBoundary() throws Exception {
        var service=mock(ReaderChapterService.class);var props=new ReaderProperties();props.setEnabled(false);
        var body=new BookContent();body.setContent("");
        var view=new ReaderChapterVO("1","2","书","章节",0,5,null,null,null,"need_login","anonymous",List.of());
        when(service.read(1L,2L,null)).thenReturn(new ReaderChapterService.ChapterAccess(new Book(),new BookIndex(),body,view));
        var controller=new PageController(mock(BookService.class),service,props,new ObjectMapper(),mock(com.java2nb.novel.seo.SeoMetadataService.class),
            mock(NewsService.class),mock(AuthorService.class),mock(UserService.class),mock(ThreadPoolExecutor.class),Map.of());
        var model=new ExtendedModelMap();
        try(var mocked=mockStatic(ThreadLocalUtil.class)) {
            mocked.when(ThreadLocalUtil::getTemplateDir).thenReturn("mobile/");
            assertEquals("mobile/book/book_content",controller.bookContent(1L,2L,new MockHttpServletRequest(),new MockHttpServletResponse(),model));
        }
        assertEquals(true,model.get("needBuy"));assertEquals("",((BookContent)model.get("bookContent")).getContent());
    }
}
