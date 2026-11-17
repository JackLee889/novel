package com.java2nb.novel.service;

import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.vo.ReaderChapterVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** Content-only cache. Callers must check current book/chapter ownership and entitlement first. */
@Service
public class ReaderChapterCache {
    private final JdbcTemplate jdbc;
    @Value("${reader.chapter-cache-enabled:true}") private boolean enabled=true;
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final Object[] stripes=new Object[32];
    private long bytes;
    private static final long MAX_BYTES=24L*1024*1024;
    private static final int MAX_ENTRIES=1024,MAX_ENTRY_BYTES=512*1024;
    private static final long TTL=Duration.ofMinutes(10).toNanos();
    public ReaderChapterCache(DataSource source){jdbc=new JdbcTemplate(source);Arrays.setAll(stripes,i->new Object());}
    private ReaderChapterCache(){jdbc=null;enabled=false;Arrays.setAll(stripes,i->new Object());}
    static ReaderChapterCache disabled(){return new ReaderChapterCache();}
    private record Key(long bookId,long chapterId,long revision,Integer chapterNumber){}
    private record Entry(Page page,long bytes,long expires){}
    public record Navigation(Long previous,Long next){}
    public record Page(Long contentId,Long indexId,String raw,List<ReaderChapterVO.Paragraph> paragraphs,
                       String version,Long previous,Long next){
        public BookContent content(){var result=new BookContent();result.setId(contentId);result.setIndexId(indexId);result.setContent(raw);return result;}
    }
    private Long revision(long bookId){
        try {
            return jdbc.query("SELECT revision FROM crawl_book_io_state WHERE book_id=?",(r,n)->r.getLong(1),bookId)
                .stream().findFirst().orElse(0L);
        } catch(DataAccessException unavailable) {
            // Without an authoritative mutation token, read storage and never use an old cache entry.
            return null;
        }
    }
    public Navigation navigation(long bookId,int number,Supplier<Navigation> fallback){
        if(!enabled || jdbc==null)return fallback.get();
        // Both seeks use the existing unique (book_id,index_num) index in one database round trip.
        return jdbc.queryForObject("SELECT (SELECT id FROM book_index WHERE book_id=? AND index_num<? ORDER BY index_num DESC LIMIT 1) AS previous_id,"
            +"(SELECT id FROM book_index WHERE book_id=? AND index_num>? ORDER BY index_num LIMIT 1) AS next_id",
            (r,n)->new Navigation((Long)r.getObject("previous_id"),(Long)r.getObject("next_id")),bookId,number,bookId,number);
    }
    public Page read(long bookId,long chapterId,String storage,Supplier<Page> loader){
        return read(bookId,chapterId,storage,null,loader);
    }
    public Page read(long bookId,long chapterId,String storage,Integer chapterNumber,Supplier<Page> loader){
        if(!enabled || jdbc==null || !"db".equals(storage))return loader.get();
        Long revision=revision(bookId);if(revision==null)return loader.get();
        // A caller may have read old directory metadata just before a writer committed
        // the new revision. Such a navigation result must not serve the next caller.
        Key key=new Key(bookId,chapterId,revision,chapterNumber);Page found=get(key);if(found!=null)return found;
        synchronized(stripes[Math.floorMod(Long.hashCode(chapterId),stripes.length)]){
            revision=revision(bookId);if(revision==null)return loader.get();
            key=new Key(bookId,chapterId,revision,chapterNumber);found=get(key);if(found!=null)return found;
            Page value=loader.get();Long after=revision(bookId);
            if(Objects.equals(revision,after))put(key,value);
            return value;
        }
    }
    private synchronized Page get(Key key){
        Entry entry=entries.get(key);if(entry==null)return null;
        if(System.nanoTime()>=entry.expires()){bytes-=entry.bytes();entries.remove(key);return null;}
        return entry.page();
    }
    private synchronized void put(Key key,Page value){
        long weight=512L+2L*value.raw().length()+value.paragraphs().stream().mapToLong(p->80L+2L*p.text().length()).sum();
        if(weight>MAX_ENTRY_BYTES)return;
        Entry old=entries.remove(key);if(old!=null)bytes-=old.bytes();
        long now=System.nanoTime();
        var iterator=entries.entrySet().iterator();
        while(iterator.hasNext()){
            var next=iterator.next();
            if(next.getValue().expires()<=now || (next.getKey().bookId()==key.bookId() && next.getKey().revision()!=key.revision())){
                bytes-=next.getValue().bytes();iterator.remove();
            }
        }
        entries.put(key,new Entry(value,weight,now+TTL));bytes+=weight;
        while(bytes>MAX_BYTES || entries.size()>MAX_ENTRIES){var first=entries.entrySet().iterator();var removed=first.next();bytes-=removed.getValue().bytes();first.remove();}
    }
    synchronized int entryCount(){return entries.size();}
    synchronized long byteCount(){return bytes;}
}
