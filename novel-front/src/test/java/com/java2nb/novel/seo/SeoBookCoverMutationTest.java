package com.java2nb.novel.seo;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.mapper.FrontBookMapper;
import com.java2nb.novel.service.impl.BookServiceImpl;
import org.junit.jupiter.api.Test;
import org.mybatis.dynamic.sql.update.render.UpdateStatementProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SeoBookCoverMutationTest {
    @Test void unchangedCoverDoesNotWriteOrEnqueueAndChangedCoverLocksBeforeMutation() {
        var service=mock(BookServiceImpl.class,CALLS_REAL_METHODS);var seo=mock(SeoService.class);var mapper=mock(FrontBookMapper.class);
        ReflectionTestUtils.setField(service,"seo",seo);ReflectionTestUtils.setField(service,"bookMapper",mapper);
        var book=new Book();book.setId(7L);book.setAuthorId(2L);book.setPicUrl("/images/old.png");
        doReturn(book).when(service).queryBookDetailInternal(7L);
        service.updateBookPic(7L,"/images/old.png",2L);verifyNoInteractions(mapper);verify(seo,never()).markBookDirty(anyLong());
        when(mapper.update(any(UpdateStatementProvider.class))).thenReturn(1);service.updateBookPic(7L,"/images/new.png",2L);
        var sequence=inOrder(seo,mapper);sequence.verify(seo,atLeastOnce()).lockMutation();sequence.verify(mapper).update(any(UpdateStatementProvider.class));sequence.verify(seo).markBookDirty(7L);
    }
}
