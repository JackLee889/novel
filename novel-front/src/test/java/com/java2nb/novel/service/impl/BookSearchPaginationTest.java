package com.java2nb.novel.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import com.java2nb.novel.mapper.FrontBookMapper;
import com.java2nb.novel.vo.BookSpVO;
import com.java2nb.novel.vo.BookVO;
import io.github.xxyopen.model.page.builder.pagehelper.PageBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real XML, mapper default method and PageHelper count/pagination together. */
class BookSearchPaginationTest {
    JdbcTemplate jdbc;
    FrontBookMapper mapper;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY,cat_id INT,cat_name VARCHAR(50),book_name VARCHAR(100),author_id BIGINT,author_name VARCHAR(100),word_count INT,last_index_id BIGINT,last_index_name VARCHAR(100),score DECIMAL(4,1),pic_url VARCHAR(100),book_status TINYINT,last_index_update_time TIMESTAMP,book_desc VARCHAR(1000),status TINYINT,work_direction TINYINT,is_vip TINYINT,visit_count BIGINT)");
        for(int i=1;i<=245;i++) jdbc.update("INSERT INTO book VALUES(?,?,'分类',?,1,?,?,1,'章节',9,'/pic',?,?,?,1,?,?,?)",
            i,i%3,"book"+i,"author"+(i%4),i*100, i%2,Timestamp.valueOf("2026-10-01 00:00:00"),"description"+i,i%2,i%2,245-i);
        jdbc.update("UPDATE book SET status=0 WHERE id=245");
        jdbc.update("UPDATE book SET word_count=0 WHERE id=244");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(FrontBookMapper.class);
        factory.setConfiguration(config);factory.setMapperLocations(new ClassPathResource("mybatis/mapping/BookMapper.xml"));
        var paging=new PageInterceptor();var props=new Properties();props.setProperty("helperDialect","mysql");paging.setProperties(props);
        factory.setPlugins(paging);
        mapper=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(FrontBookMapper.class);
    }
    List<BookVO> page(BookSpVO query,int number,int size) {
        PageHelper.startPage(number,size);
        try {return mapper.searchByPage(query);} finally {PageHelper.clearPage();}
    }
    @Test void deepPageRetainsExactCountStableTieOrderAndOnlyCurrentPageDetails() {
        var query=new BookSpVO();query.setSort("last_index_update_time");
        var rows=page(query,20,12);
        assertInstanceOf(Page.class,rows);assertEquals(243,((Page<?>)rows).getTotal());
        assertEquals(List.of(15L,14L,13L,12L,11L,10L,9L,8L,7L,6L,5L,4L),rows.stream().map(BookVO::getId).toList());
        assertEquals("description15",rows.getFirst().getBookDesc());
        var result=PageBuilder.build(rows);assertEquals(243,result.getTotal());assertEquals(12,result.getList().size());
        assertEquals(20,((Page<?>)rows).getPageNum());assertEquals(12,((Page<?>)rows).getPageSize());
        assertNull(PageHelper.getLocalPage(),"Detail fetch must not inherit pagination");
    }
    @Test void everyFilterAndSortRetainsItsExistingMeaning() {
        var query=new BookSpVO();query.setKeyword("author1");query.setWorkDirection((byte)1);query.setIsVip((byte)1);
        query.setBookStatus((byte)1);query.setCatId(1);query.setWordCountMin(1000);query.setWordCountMax(20000);
        query.setUpdateTimeMin(Timestamp.valueOf("2026-09-30 00:00:00"));
        var expected=jdbc.queryForList("SELECT id FROM book WHERE status=1 AND word_count>=1000 AND word_count<20000 AND author_name='author1' AND work_direction=1 AND is_vip=1 AND book_status=1 AND cat_id=1 ORDER BY id DESC",Long.class);
        for(String sort:Arrays.asList(null,"word_count","last_index_update_time","visit_count")) {
            query.setSort(sort);var rows=page(query,2,4);var sorted=new ArrayList<>(expected);if("visit_count".equals(sort))Collections.reverse(sorted);
            assertEquals(sorted.subList(4,8),rows.stream().map(BookVO::getId).toList());
            assertEquals(expected.size(),((Page<?>)rows).getTotal());
        }
    }
    @Test void emptyAndOutOfRangePagesRetainTotalsWithoutInvalidEmptyInQuery() {
        var query=new BookSpVO();var rows=page(query,99,12);assertTrue(rows.isEmpty());assertEquals(243,((Page<?>)rows).getTotal());
        query.setKeyword("not present");rows=page(query,1,12);assertTrue(rows.isEmpty());assertEquals(0,((Page<?>)rows).getTotal());
    }
}
