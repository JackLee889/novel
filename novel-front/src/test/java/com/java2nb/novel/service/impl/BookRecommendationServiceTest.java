package com.java2nb.novel.service.impl;

import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.mapper.FrontBookMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookRecommendationServiceTest {
    private JdbcTemplate jdbc;
    private FrontBookMapper mapper;
    private CacheService cache;
    private BookRecommendationService service;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY,cat_id INT,status TINYINT,word_count INT,pic_url VARCHAR(200),book_name VARCHAR(50),book_desc VARCHAR(200))");
        for (int i=1;i<=8;i++) insert(i,2,1,100);
        insert(9,2,0,100); insert(10,2,1,0); insert(11,3,1,100);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(FrontBookMapper.class);
        factory.setConfiguration(config);factory.setMapperLocations(new ClassPathResource("mybatis/mapping/BookMapper.xml"));
        mapper=spy(new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(FrontBookMapper.class));
        cache=mock(CacheService.class);service=new BookRecommendationService(mapper,cache);
    }

    @Test void cachesOnlyIdsAndKeepsRandomResultsDistinctAndPublic() {
        check(service.list(2),4);check(service.list(2),4);
        verify(mapper,times(1)).listRecommendationIds(2);
        verify(mapper,times(2)).listRecommendationsByIds(eq(2),anyList());
        verify(cache).setObject(eq(BookRecommendationService.KEY_PREFIX+2),eq(List.of(1L,2L,3L,4L,5L,6L,7L,8L)),eq(300L));
    }

    @Test void staleSharedIdsCannotExposeHiddenEmptyOrDifferentCategoryBooks() {
        when(cache.getList(BookRecommendationService.KEY_PREFIX+2,Long.class)).thenReturn(List.of(1L,2L,3L,4L,9L,10L,11L));
        var result=service.list(2);check(result,4);
        assertEquals(Set.of(1L,2L,3L,4L),new HashSet<>(result.stream().map(Book::getId).toList()));
        verify(mapper,never()).listRecommendationIds(anyInt());
    }

    @Test void unpublicationTakesEffectImmediatelyAndSparsePoolRefreshesOnce() {
        check(service.list(2),4);
        jdbc.update("UPDATE book SET status=0 WHERE id<8");
        var result=service.list(2);check(result,1);assertEquals(8L,result.getFirst().getId());
        verify(mapper,times(2)).listRecommendationIds(2);
    }

    @Test void redisFailureUsesBoundedLocalFallbackWithoutRepeatingPoolQuery() {
        when(cache.getList(anyString(),eq(Long.class))).thenThrow(new IllegalStateException("Fixture Redis unavailable"));
        doThrow(new IllegalStateException("Fixture Redis unavailable")).when(cache).setObject(anyString(),any(),anyLong());
        check(service.list(2),4);check(service.list(2),4);
        verify(mapper,times(1)).listRecommendationIds(2);
    }

    @Test void concurrentColdRequestsShareOnePoolLoad() throws Exception {
        var executor=Executors.newFixedThreadPool(8);
        try {
            List<Callable<List<Book>>> calls=new ArrayList<>();
            for(int i=0;i<24;i++)calls.add(()->service.list(2));
            for(var result:executor.invokeAll(calls))check(result.get(10,TimeUnit.SECONDS),4);
            verify(mapper,times(1)).listRecommendationIds(2);
        } finally {executor.shutdownNow();}
    }

    @Test void malformedDuplicateCacheIdsDoNotLoopAndEmptyCategoriesDoNotQueryDetails() {
        when(cache.getList(BookRecommendationService.KEY_PREFIX+2,Long.class))
            .thenReturn(Arrays.asList(null,1L,1L,2L,2L,3L,4L,4L));
        check(service.list(2),4);
        assertTrue(service.list(404).isEmpty());assertTrue(service.list(404).isEmpty());
        verify(mapper,times(1)).listRecommendationIds(404);
        verify(mapper,never()).listRecommendationsByIds(eq(404),anyList());
    }

    @Test void invalidCategoryDoesNotAccessDatabaseOrRedis() {
        assertTrue(service.list(null).isEmpty());assertTrue(service.list(0).isEmpty());assertTrue(service.list(-1).isEmpty());
        verifyNoInteractions(mapper,cache);
    }

    private void insert(long id,int cat,int status,int words) {
        jdbc.update("INSERT INTO book VALUES(?,?,?,?,?,?,?)",id,cat,status,words,"/cover.png","Fixture "+id,"Fixture description");
    }
    private void check(List<Book> books,int size) {
        assertEquals(size,books.size());assertEquals(size,books.stream().map(Book::getId).distinct().count());
        for(Book b:books){assertEquals(2,b.getCatId());assertEquals((byte)1,b.getStatus());assertTrue(b.getWordCount()>0);}
    }
}
