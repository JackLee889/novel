package com.java2nb.novel.features;

import com.java2nb.novel.core.config.BusinessFeatureProperties;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.mapper.FrontBookMapper;
import com.java2nb.novel.service.impl.BookServiceImpl;
import com.java2nb.novel.seo.SeoService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BookAiCoverFeatureTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "https://example.invalid/cover.png")
    void disabledAiNeverSchedulesCoverGenerationWhenSubmissionIsEnabled(String coverUrl) {
        BusinessFeatureProperties features = new BusinessFeatureProperties();
        features.setSubmissionEnabled(true);
        features.setAiEnabled(false);
        FrontBookMapper mapper = mock(FrontBookMapper.class);
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        OpenAiImageModel imageModel = mock(OpenAiImageModel.class);
        BookServiceImpl service = mock(BookServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "businessFeatures", features);
        ReflectionTestUtils.setField(service, "bookMapper", mapper);
        ReflectionTestUtils.setField(service, "threadPoolExecutor", executor);
        ReflectionTestUtils.setField(service, "openAiImageModel", imageModel);
        ReflectionTestUtils.setField(service, "seo", mock(SeoService.class));
        when(mapper.selectMany(any(SelectStatementProvider.class))).thenReturn(Collections.emptyList());
        Book book = new Book();
        book.setBookName("Feature gate test");
        book.setPicUrl(coverUrl);

        service.addBook(book, 1L, "Feature gate author");

        verify(mapper).insertSelective(book);
        verifyNoInteractions(executor, imageModel);
    }
}
