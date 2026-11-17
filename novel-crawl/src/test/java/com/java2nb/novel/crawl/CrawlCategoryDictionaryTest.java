package com.java2nb.novel.crawl;

import com.java2nb.novel.controller.CrawlController;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.mapper.BookCategoryMapper;
import com.java2nb.novel.mapper.CrawlBookIndexMapper;
import com.java2nb.novel.mapper.CrawlBookMapper;
import com.java2nb.novel.seo.SeoRepository;
import com.java2nb.novel.service.CrawlService;
import com.java2nb.novel.service.impl.BookServiceImpl;
import com.java2nb.novel.utils.CrawlHttpClient;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 真正的字典查询/方向和 API 返回，专用 H2 内存库。 */
class CrawlCategoryDictionaryTest {
    @Test
    void categoryDictionaryOrdersAndReturnsActualDirectionAndExtraCategory() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
            CREATE TABLE book_category (id INT PRIMARY KEY, name VARCHAR(20), work_direction TINYINT,
              sort TINYINT, create_user_id BIGINT, create_time TIMESTAMP,
              update_user_id BIGINT, update_time TIMESTAMP)
            """);
        jdbc.update("INSERT INTO book_category(id,name,work_direction,sort) VALUES(8,'其他小说',0,18)");
        jdbc.update("INSERT INTO book_category(id,name,work_direction,sort) VALUES(7,'女生频道',1,17)");
        jdbc.update("INSERT INTO book_category(id,name,work_direction,sort) VALUES(1,'玄幻奇幻',0,17)");
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(BookCategoryMapper.class);
        factory.setConfiguration(config);
        var sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var books = new BookServiceImpl(mock(CrawlBookMapper.class), sql.getMapper(BookCategoryMapper.class),
            mock(CrawlBookIndexMapper.class), Map.of(), mock(CacheService.class), mock(SeoRepository.class));
        var categories = books.listBookCategories();
        assertEquals(List.of(1, 7, 8), categories.stream().map(category -> category.getId()).toList());
        assertEquals((byte) 0, books.queryBookCategoryByCatId(8).getWorkDirection());
        assertEquals((byte) 1, books.queryBookCategoryByCatId(7).getWorkDirection());
        assertEquals("其他小说", books.queryCatNameByCatId(8));
        assertNull(books.queryBookCategoryByCatId(999));
        assertThrows(IllegalArgumentException.class, () -> books.queryCatNameByCatId(999));
        var controller = new CrawlController(mock(CrawlService.class), mock(CacheService.class), books,
            mock(CrawlHttpClient.class));
        assertEquals(List.of(1, 7, 8), controller.listBookCategories().getData().stream()
            .map(category -> category.getId()).toList());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM book_category", Integer.class));
    }
}
