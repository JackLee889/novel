package com.java2nb.novel.crawl;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StoppedSourceSelectionTest {
    @Test void stoppedSourcesNeverEnterTheBookClaimAndTimestampUpdateQueue() throws Exception {
        var configuration = new Configuration();
        String path = "mybatis/mapping/BookMapper.xml";
        try (var input = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        var bound = configuration.getMappedStatement("com.java2nb.novel.mapper.CrawlBookMapper.queryNeedUpdateBook")
                .getBoundSql(Map.of("startDate", new Date(), "limit", 10));
        assertFalse(bound.getSql().contains("${"));
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("create table crawl_source(id int primary key, source_status int)");
                statement.execute("create table book(id bigint, crawl_source_id int, crawl_book_id varchar(80), crawl_last_time timestamp, pic_url varchar(80), word_count int, crawl_is_stop int, book_status int default 0)");
                statement.execute("insert into crawl_source values (4,0),(12,0),(99,1)");
                statement.execute("insert into book(id,crawl_source_id,crawl_book_id,crawl_last_time,pic_url,word_count,crawl_is_stop) values (1,4,'a',null,'a',10,0),(2,12,'b',null,'b',20,0),(3,99,'c',null,'c',30,0)");
            }
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                new org.springframework.core.io.ClassPathResource("db/crawler-completeness-20261018.sql"));
            try (var statement = connection.prepareStatement(bound.getSql())) {
                statement.setTimestamp(1, new Timestamp(System.currentTimeMillis())); statement.setInt(2, 10);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next()); assertEquals(3, result.getLong("id")); assertFalse(result.next());
                }
            }
        }
    }
}
