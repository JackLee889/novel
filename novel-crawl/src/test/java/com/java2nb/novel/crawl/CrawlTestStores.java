package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.CrawlCollectionStore;
import com.java2nb.novel.entity.BookIndex;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.HashSet;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Adapter unit fixtures assume healthy old bodies; persistence/holes use real H2 stores. */
public final class CrawlTestStores {
    public static CrawlCollectionStore healthy() {
        var store=mock(CrawlCollectionStore.class);
        when(store.open(anyLong(),anyInt(),anyString(),anyString(),anyInt(),any())).thenAnswer(c->
            new CrawlCollectionStore.Audit(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3),c.getArgument(4)));
        when(store.readyChapters(anyLong(),anyList())).thenAnswer(c->{
            List<BookIndex> indexes=c.getArgument(1); var ids=new HashSet<Long>();
            indexes.forEach(i->ids.add(i.getId())); return ids;
        });
        return store;
    }
    public static void schema(JdbcTemplate jdbc) throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/crawler-completeness-20261018.sql"));
        }
    }
}
