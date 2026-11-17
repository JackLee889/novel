package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.core.listener.StarterListener;
import com.java2nb.novel.entity.BookCategory;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.mapper.CrawlSingleTaskMapper;
import com.java2nb.novel.mapper.CrawlSourceMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import com.java2nb.novel.utils.CrawlHttpClient;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真实 MyBatis 状态写入和查询，独立 H2 数据库；采集线程不访问网站。 */
@Timeout(10)
class CrawlSourceLifecycleTest {
    private JdbcTemplate jdbc;
    private CrawlSourceMapper mapper;
    private BookService books;
    private final List<BlockingService> instances = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE crawl_source(id INT PRIMARY KEY, source_name VARCHAR(80),
              source_status TINYINT, crawl_rule TEXT, create_time TIMESTAMP, update_time TIMESTAMP)
            """);
        jdbc.update("INSERT INTO crawl_source VALUES(1,'fixture',0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            "{\"catIdRules\":{\"catId1\":[\"1\"]}}");
        jdbc.update("INSERT INTO crawl_source SELECT 2,'stopped',0,crawl_rule,create_time,update_time FROM crawl_source WHERE id=1");
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(CrawlSourceMapper.class);
        factory.setConfiguration(config);
        mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(CrawlSourceMapper.class);
        books = mock(BookService.class);
        var category = new BookCategory();
        category.setId(1);
        when(books.listBookCategories()).thenReturn(List.of(category));
    }

    @AfterEach
    void stopThreads() throws Exception {
        for (BlockingService service : instances) {
            service.shutdown();
            for (Thread thread : service.startedThreads) {
                thread.join(1000);
                assertFalse(thread.isAlive());
            }
        }
    }

    @Test
    void switchPersistsAndRestartRestoresOnlyEnabledSources() throws Exception {
        BlockingService first = service(mapper);
        first.openOrCloseCrawl(1, (byte) 1);
        assertTrue(first.ready.await(2, TimeUnit.SECONDS));
        assertEquals(1, status(1));
        assertEquals((byte) 1, first.listCrawlByPage(1, 20).getList().stream()
            .filter(source -> source.getId() == 1).findFirst().orElseThrow().getSourceStatus());
        first.shutdown();
        assertEquals(1, status(1), "退出应用不能清除保存的开启状态");

        BlockingService restarted = service(mapper);
        var listener = new StarterListener(books, restarted, mock(CrawlParser.class));
        try {
            listener.onStartup();
            listener.onStartup();
            assertTrue(restarted.ready.await(2, TimeUnit.SECONDS));
            assertEquals(1, restarted.started.get());
            assertEquals(0, status(2));
            assertEquals(1, restarted.queryCrawlSourceByStatus((byte) 1).size());
            restarted.openOrCloseCrawl(1, (byte) 0);
            assertEquals(0, status(1));
            assertTrue(restarted.queryCrawlSourceByStatus((byte) 1).isEmpty());
            assertEquals((byte) 0, restarted.listCrawlByPage(1, 20).getList().stream()
                .filter(source -> source.getId() == 1).findFirst().orElseThrow().getSourceStatus());
        } finally {
            listener.shutdown();
        }
    }

    @Test
    void concurrentAndRepeatedStartCreateOneWorker() throws Exception {
        BlockingService service = service(mapper);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> service.openOrCloseCrawl(1, (byte) 1));
            var two = executor.submit(() -> service.openOrCloseCrawl(1, (byte) 1));
            one.get(2, TimeUnit.SECONDS);
            two.get(2, TimeUnit.SECONDS);
        }
        assertTrue(service.ready.await(2, TimeUnit.SECONDS));
        assertEquals(1, service.started.get());
        assertEquals(1, status(1));
        service.openOrCloseCrawl(1, (byte) 0);
        service.openOrCloseCrawl(1, (byte) 0);
        assertEquals(0, status(1));
    }

    @Test
    void ruleEditStopsAndDoesNotOverwriteStatusWithStaleEnabledValue() throws Exception {
        BlockingService service = service(mapper);
        service.openOrCloseCrawl(1, (byte) 1);
        assertTrue(service.ready.await(2, TimeUnit.SECONDS));
        CrawlSource changes = new CrawlSource();
        changes.setId(1);
        changes.setSourceName("edited");
        changes.setCrawlRule("{\"catIdRules\":{\"catId1\":[\"2\"]}}");
        service.updateCrawlSource(changes);
        assertEquals(0, status(1));
        assertEquals(changes.getCrawlRule(), mapper.selectByPrimaryKey(1).orElseThrow().getCrawlRule());
        assertEquals("edited", mapper.selectByPrimaryKey(1).orElseThrow().getSourceName());
    }

    @Test
    void failedSaveCannotStartWorkerAndFailedStopPreservesRunningState() throws Exception {
        CrawlSourceMapper failing = spy(mapper);
        doThrow(new IllegalStateException("fixture database unavailable"))
            .when(failing).updateByPrimaryKeySelective(any(CrawlSource.class));
        BlockingService service = service(failing);
        assertThrows(IllegalStateException.class, () -> service.openOrCloseCrawl(1, (byte) 1));
        assertEquals(0, status(1));
        assertEquals(0, service.started.get());
        doCallRealMethod().when(failing).updateByPrimaryKeySelective(any(CrawlSource.class));
        service.openOrCloseCrawl(1, (byte) 1);
        assertTrue(service.ready.await(2, TimeUnit.SECONDS));
        doThrow(new IllegalStateException("fixture database unavailable"))
            .when(failing).updateByPrimaryKeySelective(any(CrawlSource.class));
        assertThrows(IllegalStateException.class, () -> service.openOrCloseCrawl(1, (byte) 0));
        assertEquals(1, status(1));
        assertEquals((byte) 1, service.listCrawlByPage(1, 20).getList().stream()
            .filter(source -> source.getId() == 1).findFirst().orElseThrow().getSourceStatus());
        assertTrue(service.startedThreads.getFirst().isAlive());
    }

    @Test
    void invalidRulesIdsAndStatusesNeverEnableCollection() {
        BlockingService service = service(mapper);
        assertThrows(IllegalArgumentException.class, () -> service.openOrCloseCrawl(1, (byte) 2));
        assertThrows(IllegalArgumentException.class, () -> service.openOrCloseCrawl(1, null));
        assertThrows(IllegalArgumentException.class, () -> service.openOrCloseCrawl(null, (byte) 1));
        assertThrows(IllegalArgumentException.class, () -> service.openOrCloseCrawl(999, (byte) 1));
        assertThrows(IllegalStateException.class, () -> service.openOrCloseCrawl(999, (byte) 0));
        jdbc.update("UPDATE crawl_source SET crawl_rule='{}' WHERE id=1");
        assertThrows(IllegalArgumentException.class, () -> service.openOrCloseCrawl(1, (byte) 1));
        jdbc.update("UPDATE crawl_source SET crawl_rule='invalid json' WHERE id=1");
        assertThrows(RuntimeException.class, () -> service.openOrCloseCrawl(1, (byte) 1));
        assertEquals(0, status(1));
        assertEquals(0, service.started.get());
    }

    @Test
    void disabledJobsKeepSavedSourcesStoppedAtStartup() {
        jdbc.update("UPDATE crawl_source SET source_status=1 WHERE id=1");
        BlockingService service = service(mapper);
        var listener = new StarterListener(books, service, mock(CrawlParser.class));
        ReflectionTestUtils.setField(listener, "jobsEnabled", false);
        listener.onStartup();
        assertEquals(0, service.started.get());
        assertEquals(1, status(1));
        listener.shutdown();
    }

    private int status(int id) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT source_status FROM crawl_source WHERE id=?", Integer.class, id));
    }

    private BlockingService service(CrawlSourceMapper mapper) {
        var instance = new BlockingService(mapper, books);
        instances.add(instance);
        return instance;
    }

    private static class BlockingService extends CrawlServiceImpl {
        final AtomicInteger started = new AtomicInteger();
        final CountDownLatch ready = new CountDownLatch(1);
        final List<Thread> startedThreads = new CopyOnWriteArrayList<>();

        BlockingService(CrawlSourceMapper mapper, BookService books) {
            super(mock(CrawlParser.class), mapper, mock(CrawlSingleTaskMapper.class), books, mock(CrawlHttpClient.class));
        }

        @Override
        public void parseBookList(int category, RuleBean rule, Integer sourceId) {
            startedThreads.add(Thread.currentThread());
            started.incrementAndGet();
            ready.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
