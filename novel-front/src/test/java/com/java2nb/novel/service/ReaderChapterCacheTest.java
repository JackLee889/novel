package com.java2nb.novel.service;

import com.java2nb.novel.entity.*;
import com.java2nb.novel.vo.ReaderChapterVO;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReaderChapterCacheTest {
    JdbcTemplate db;ReaderChapterCache cache;
    @BeforeEach void setup(){
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:reader_cache_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(source);cache=new ReaderChapterCache(source);
        db.execute("CREATE TABLE crawl_book_io_state(book_id BIGINT PRIMARY KEY,revision BIGINT)");
        db.execute("CREATE TABLE book_index(id BIGINT PRIMARY KEY,book_id BIGINT,index_num INT,UNIQUE(book_id,index_num))");
        db.update("INSERT INTO book_index VALUES(9,1,0),(10,1,2),(20,1,7),(100,2,2)");
    }
    ReaderChapterCache.Page page(String text){return new ReaderChapterCache.Page(8L,10L,text,List.of(new ReaderChapterVO.Paragraph("p0",text)),"version",9L,20L);}
    @Test void repeatedReadsAvoidBodyLoaderAndEveryMutationRevisionInvalidates(){
        var calls=new AtomicInteger();var one=cache.read(1,10,"db",()->{calls.incrementAndGet();return page("旧正文");});
        assertSame(one,cache.read(1,10,"db",()->{throw new AssertionError("body reread");}));
        db.update("INSERT INTO crawl_book_io_state VALUES(1,1)");
        assertEquals("新正文",cache.read(1,10,"db",()->{calls.incrementAndGet();return page("新正文");}).raw());
        assertEquals(2,calls.get());assertEquals(1,cache.entryCount());
    }
    @Test void combinedNavigationPreservesGapsBoundariesAndBookOwnership(){
        var navigation=cache.navigation(1,2,()->{throw new AssertionError("legacy two-round-trip lookup");});
        assertEquals(9L,navigation.previous());assertEquals(20L,navigation.next());
        assertNull(cache.navigation(1,0,()->null).previous());assertNull(cache.navigation(1,7,()->null).next());
        assertEquals(new ReaderChapterCache.Navigation(null,null),cache.navigation(2,2,()->null));
    }
    @Test void concurrentRequestsLoadOneBody() throws Exception {
        var calls=new AtomicInteger();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(12)){
            var jobs=new ArrayList<Future<String>>();
            for(int i=0;i<30;i++)jobs.add(pool.submit(()->{start.await();return cache.read(1,10,"db",()->{calls.incrementAndGet();return page("并发正文");}).raw();}));
            start.countDown();for(var job:jobs)assertEquals("并发正文",job.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,calls.get());
    }
    @Test void metadataReadBeforeRevisionCommitCannotPoisonNavigationForTheNextCaller(){
        int previousNumber=2;
        db.update("INSERT INTO book_index VALUES(15,1,3)");db.update("UPDATE book_index SET index_num=5 WHERE id=10");
        db.update("INSERT INTO crawl_book_io_state VALUES(1,1)");var loads=new AtomicInteger();
        java.util.function.Function<Integer,ReaderChapterCache.Page> load=number->{
            loads.incrementAndGet();var nav=cache.navigation(1,number,()->null);var p=page("正文");
            return new ReaderChapterCache.Page(p.contentId(),p.indexId(),p.raw(),p.paragraphs(),p.version(),nav.previous(),nav.next());
        };
        var stale=cache.read(1,10,"db",previousNumber,()->load.apply(previousNumber));assertEquals(15L,stale.next());
        var fresh=cache.read(1,10,"db",5,()->load.apply(5));assertEquals(15L,fresh.previous());assertEquals(20L,fresh.next());
        assertNotSame(stale,fresh);assertEquals(2,loads.get());assertSame(fresh,cache.read(1,10,"db",5,()->{throw new AssertionError("fresh metadata did not hit cache");}));
    }
    @Test void mutationDuringLoadNeverCachesUnderTheWrongRevision(){
        cache.read(1,10,"db",()->{db.update("INSERT INTO crawl_book_io_state VALUES(1,1)");return page("并发旧版本");});
        assertEquals(0,cache.entryCount());
        assertEquals("当前版本",cache.read(1,10,"db",()->page("当前版本")).raw());
    }
    @Test void missingRevisionTableReadsStorageInsteadOfServingAnOldCachedBody(){
        cache.read(1,10,"db",()->page("旧"));db.execute("DROP TABLE crawl_book_io_state");
        assertEquals("新",cache.read(1,10,"db",()->page("新")).raw());
    }
    @Test void fileStorageIsAlwaysReadFreshAndCachedContentObjectsCannotBeMutated(){
        assertEquals("一",cache.read(1,10,"txt",()->page("一")).raw());
        assertEquals("二",cache.read(1,10,"txt",()->page("二")).raw());assertEquals(0,cache.entryCount());
        var cached=cache.read(1,10,"db",()->page("正文"));cached.content().setContent("外部修改");
        assertEquals("正文",cache.read(1,10,"db",()->null).content().getContent());
    }
    @Test void cacheHasEntryAndMemoryBoundsAndSkipsOversizeBodies(){
        cache.read(1,10,"db",()->page("x".repeat(300_000)));assertEquals(0,cache.entryCount());
        for(int i=0;i<1100;i++)cache.read(i+100L,i+100L,"db",()->page("small"));
        assertTrue(cache.entryCount()<=1024);assertTrue(cache.byteCount()<=24L*1024*1024);
        for(int i=0;i<100;i++)cache.read(i+2000L,i+2000L,"db",()->page("x".repeat(100_000)));
        assertTrue(cache.byteCount()<=24L*1024*1024);
    }
    @Test void warmVipCacheNeverBypassesFreshEntitlementOrPublication(){
        BookService books=mock(BookService.class);UserService users=mock(UserService.class);BookContentService storage=mock(BookContentService.class);
        var book=new Book();book.setId(1L);book.setStatus((byte)1);book.setBookName("测试");
        var index=new BookIndex();index.setId(10L);index.setBookId(1L);index.setIndexNum(2);index.setStorageType("db");index.setIsVip((byte)1);
        var content=new BookContent();content.setId(8L);content.setIndexId(10L);content.setContent("<p>受保护正文</p>");
        when(books.queryBookDetail(1L)).thenReturn(book);when(books.queryBookIndex(10L)).thenReturn(index);when(storage.queryBookContent(1L,10L)).thenReturn(content);
        when(users.queryIsBuyBookIndex(7L,10L)).thenReturn(true);when(users.queryIsBuyBookIndex(8L,10L)).thenReturn(true);
        var reader=new ReaderChapterService(books,users,Map.of("db",storage));reader.setChapterCache(cache);
        assertEquals("7",reader.read(1L,10L,7L).view().accountScope());assertEquals("8",reader.read(1L,10L,8L).view().accountScope());
        assertEquals("need_purchase",reader.read(1L,10L,9L).view().access());assertEquals("need_login",reader.read(1L,10L,null).view().access());
        assertTrue(reader.read(1L,10L,9L).view().paragraphs().isEmpty());verify(storage,times(1)).queryBookContent(1L,10L);
        book.setStatus((byte)0);assertThrows(ResponseStatusException.class,()->reader.read(1L,10L,7L));
        book.setStatus((byte)1);index.setBookId(2L);assertThrows(ResponseStatusException.class,()->reader.read(1L,10L,7L));
    }
}
