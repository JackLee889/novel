package com.java2nb.novel.core.crawl;

import com.java2nb.novel.crawl.CrawlTestStores;
import com.java2nb.novel.mapper.CrawlSingleTaskMapper;
import com.java2nb.novel.mapper.CrawlSourceMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import com.java2nb.novel.utils.CrawlHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrawlRecentScanTest {
    @Test void recentPagesHaveTheirOwnCursorAndDoNotResetOrRushHistoricalProgress() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);CrawlTestStores.schema(jdbc);
        var queue=new CrawlDiscoveryStore(jdbc,new DataSourceTransactionManager(ds));
        var rule=new RuleBean();rule.setCharset("UTF-8");rule.setBookListUrl("https://fixture/{catId}/{page}");
        rule.setCatIdRule(Map.of("catId1","all"));rule.setBookIdPatten("book=(\\w+)");rule.setTotalPagePatten("pages=(\\d+)");
        String policy=CrawlCollectionStore.hash(new ObjectMapper().writeValueAsString(rule));String fullHash=CrawlCollectionStore.hash(policy+"\nall");
        for(int i=0;i<4;i++)queue.commitPage(queue.cursor(1,1,fullHash),10,List.of(),policy,Duration.ZERO);
        var governor=mock(CrawlRefreshStore.class);when(governor.scanCycleDelay()).thenReturn(Duration.ofHours(6));
        when(governor.scanPageDelay(1)).thenReturn(Duration.ofSeconds(30));
        var http=mock(CrawlHttpClient.class);var clock=new AtomicLong();List<String> urls=new ArrayList<>();
        when(http.get(anyString(),anyString())).thenAnswer(call->{
            urls.add(call.getArgument(0));if(urls.size()==7)throw new InterruptedException("End read-only fixture");return "pages=10 book=known";
        });
        var service=new CrawlServiceImpl(mock(CrawlParser.class),mock(CrawlSourceMapper.class),mock(CrawlSingleTaskMapper.class),mock(BookService.class),http) {
            @Override protected long categoryTimeNanos(){return clock.get();}
            @Override protected void waitForCategory(Duration delay){clock.addAndGet(delay.toNanos());}
        };
        service.setDiscoveryStore(queue);service.setRefreshStore(governor);
        try {service.parseBookList(1,rule,1);} finally {Thread.interrupted();}
        assertEquals(List.of("https://fixture/all/1","https://fixture/all/5","https://fixture/all/2","https://fixture/all/3",
            "https://fixture/all/6","https://fixture/all/7","https://fixture/all/8"),urls);
        assertEquals(8,queue.cursor(1,1,fullHash).page());
        assertEquals(1,queue.cursor(1,1,CrawlCollectionStore.hash(policy+"\nall\nrecent")).page());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM crawl_discovery_job",Integer.class));
    }
}
