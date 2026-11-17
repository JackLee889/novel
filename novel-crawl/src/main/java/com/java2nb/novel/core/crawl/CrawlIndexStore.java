package com.java2nb.novel.core.crawl;

import com.java2nb.novel.core.io.BackgroundIoGate;
import com.java2nb.novel.entity.BookIndex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** Optimistic per-book snapshots. MySQL triggers invalidate them for every writer. */
@Repository
@lombok.extern.slf4j.Slf4j
public class CrawlIndexStore {
    private static final String COLUMNS="id,book_id,index_num,index_name,source_index_id,word_count,storage_type,is_vip,book_price";
    private final JdbcTemplate jdbc;
    private final BackgroundIoGate gate;
    private final TransactionTemplate transaction;
    @Value("${crawl.io.incremental-enabled:false}") private boolean enabled;
    @Value("${crawl.io.book-index-hint:false}") private boolean indexHint;
    public CrawlIndexStore(JdbcTemplate jdbc,BackgroundIoGate gate,PlatformTransactionManager manager){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(15);
        this.gate=gate;this.transaction=new TransactionTemplate(manager);this.transaction.setTimeout(30);
    }
    public boolean enabled(){return enabled;}
    public Snapshot snapshot(long bookId){return new Snapshot(bookId);}
    private BookIndex row(java.sql.ResultSet r,int n)throws java.sql.SQLException{
        var b=new BookIndex();b.setId(r.getLong("id"));b.setBookId(r.getLong("book_id"));b.setIndexNum(r.getInt("index_num"));
        b.setIndexName(r.getString("index_name"));b.setSourceIndexId(r.getString("source_index_id"));b.setWordCount(r.getObject("word_count",Integer.class));
        b.setStorageType(r.getString("storage_type"));b.setIsVip(r.getObject("is_vip",Byte.class));b.setBookPrice(r.getObject("book_price",Integer.class));return b;
    }
    private long revision(long bookId,boolean lock){
        if(lock)jdbc.update("INSERT INTO crawl_book_io_state(book_id,revision) VALUES(?,0) ON DUPLICATE KEY UPDATE book_id=VALUES(book_id)",bookId);
        var rows=jdbc.query("SELECT revision FROM crawl_book_io_state WHERE book_id=?"+(lock?" FOR UPDATE":""),(r,n)->r.getLong(1),bookId);
        return rows.isEmpty()?0:rows.getFirst();
    }
    /** Use only inside the existing book mutation transaction, after taking its book lock. */
    public void beforeWrite(long bookId,Map<Integer,BookIndex> indexes){
        if(!enabled)return;
        long actual=revision(bookId,true);
        if(indexes instanceof Snapshot s){if(s.rows==null)throw new IllegalStateException("Load the chapter snapshot before starting its transaction");s.requireRevision(actual);}
    }
    public void afterWrite(Map<Integer,BookIndex> indexes){
        if(enabled&&indexes instanceof Snapshot s)s.expectedRevision=revision(s.bookId,false);
    }
    /** The triggers maintain this total for all writers; initialize existing books lazily. */
    public int totalWords(long bookId){
        revision(bookId,true);
        Long value=jdbc.queryForObject("SELECT word_count FROM crawl_book_io_state WHERE book_id=?",Long.class,bookId);
        if(value==null){value=jdbc.queryForObject("SELECT COALESCE(SUM(word_count),0) FROM book_index WHERE book_id=?",Long.class,bookId);
            jdbc.update("UPDATE crawl_book_io_state SET word_count=? WHERE book_id=?",value,bookId);}
        return Math.toIntExact(value);
    }
    public final class Snapshot extends AbstractMap<Integer,BookIndex> {
        public final long bookId;
        private Map<Integer,BookIndex> rows;
        private long expectedRevision=-1;
        private boolean sparse,aligned;
        private int baselineCount;
        private boolean invalidAudit;
        private Snapshot(long bookId){this.bookId=bookId;}
        public int baselineCount(){return baselineCount;}
        public boolean sparse(){return sparse;}
        public boolean invalidAudit(){return invalidAudit;}
        private void requireRevision(long actual){if(expectedRevision!=actual)throw new ConcurrentModificationException("Chapter snapshot changed; resume from committed checkpoint");}
        private void ensureLoaded(){if(rows==null)loadFull();}
        private void loadFull(){
            try(var permit=gate.acquire(true)){
                expectedRevision=revision(bookId,false);Map<Integer,BookIndex> all=new HashMap<>();long after=0;
                while(true){permit.check();List<BookIndex> page=jdbc.query("SELECT "+COLUMNS+" FROM book_index"+(indexHint?" FORCE INDEX(key_bookId)":"")
                    +" WHERE book_id=? AND id>? ORDER BY id LIMIT 200",CrawlIndexStore.this::row,bookId,after);
                    if(page.isEmpty())break;for(var b:page){if(all.put(b.getIndexNum(),b)!=null)throw new IllegalStateException("Duplicate chapter position");after=b.getId();}}
                requireRevision(revision(bookId,false));rows=all;sparse=false;log.info("Crawl index read: bookId={}, mode=full, rows={}",bookId,rows.size());
            }
        }
        /** A sparse path is allowed only after a complete identity check at this exact revision. */
        public boolean canUseSparse(int source,String sourceBook,String hash,int count,IntFunction<String> prefix){
            if(rows!=null)return false;
            try(var permit=gate.acquire(false)){
                permit.check();var states=jdbc.queryForList("SELECT revision,checked_revision,source_id,source_book_id,catalog_hash,catalog_count,aligned FROM crawl_book_io_state WHERE book_id=?",bookId);
                if(states.isEmpty())return false;var s=states.getFirst();Number rev=(Number)s.get("revision"),checked=(Number)s.get("checked_revision"),oldCount=(Number)s.get("catalog_count");
                invalidAudit=checked!=null&&rev.longValue()!=checked.longValue();
                Object alignedValue=s.get("aligned");
                if(checked==null||invalidAudit||oldCount==null||oldCount.intValue()<=0||oldCount.intValue()>count
                    ||!(Boolean.TRUE.equals(alignedValue)||alignedValue instanceof Number n&&n.intValue()==1)
                    ||s.get("source_id")==null||((Number)s.get("source_id")).intValue()!=source||!sourceBook.equals(s.get("source_book_id")))return false;
                String expected=oldCount.intValue()==count?hash:prefix.apply(oldCount.intValue());
                if(!Objects.equals(s.get("catalog_hash"),expected))return false;
                expectedRevision=rev.longValue();baselineCount=oldCount.intValue();return true;
            }
        }
        public boolean loadSlice(List<Integer> positions,List<String> sourceIds){
            if(positions.isEmpty()){rows=new HashMap<>();sparse=true;aligned=true;return true;}
            if(positions.size()>125||positions.size()!=sourceIds.size())throw new IllegalArgumentException("Invalid sparse slice");
            try(var permit=gate.acquire(false)){
                permit.check();requireRevision(revision(bookId,false));Map<Long,BookIndex> byId=new HashMap<>();
                String marks=String.join(",",Collections.nCopies(positions.size(),"?"));
                List<Object> args=new ArrayList<>();args.add(bookId);args.addAll(positions);
                for(var b:jdbc.query("SELECT "+COLUMNS+" FROM book_index WHERE book_id=? AND index_num IN ("+marks+")",CrawlIndexStore.this::row,args.toArray()))byId.put(b.getId(),b);
                args=new ArrayList<>();args.add(bookId);args.addAll(sourceIds);
                for(var b:jdbc.query("SELECT "+COLUMNS+" FROM book_index WHERE book_id=? AND source_index_id IN ("+marks+")",CrawlIndexStore.this::row,args.toArray()))byId.put(b.getId(),b);
                requireRevision(revision(bookId,false));Map<Integer,BookIndex> selected=new HashMap<>();
                Map<Integer,String> identities=new HashMap<>();for(int i=0;i<positions.size();i++)identities.put(positions.get(i),sourceIds.get(i));
                for(var b:byId.values())if(!Objects.equals(identities.get(b.getIndexNum()),b.getSourceIndexId())||selected.put(b.getIndexNum(),b)!=null)return false;
                rows=selected;sparse=true;aligned=true;log.info("Crawl index read: bookId={}, mode=sparse, rows={}, positions={}",bookId,rows.size(),positions.size());return true;
            }
        }
        public void certify(List<String> sourceIds){
            ensureLoaded();Set<String> ids=new HashSet<>(sourceIds);aligned=ids.size()==sourceIds.size();
            Set<String> seen=new HashSet<>();
            for(var b:rows.values()){
                int p=b.getIndexNum();String id=b.getSourceIndexId();
                if(id==null||id.isBlank()||!seen.add(id)||p<0||(p<sourceIds.size()?!id.equals(sourceIds.get(p)):ids.contains(id))){aligned=false;break;}
            }
        }
        public void invalidateAlignment(){aligned=false;}
        public void prepareWrite(){ensureLoaded();}
        public void checkpoint(CrawlCollectionStore.Audit audit,Runnable save){
            try(var permit=gate.acquire(false)){
                permit.check();transaction.executeWithoutResult(tx->{requireRevision(revision(bookId,true));save.run();
                    jdbc.update("UPDATE crawl_book_io_state SET checked_revision=?,source_id=?,source_book_id=?,catalog_hash=?,catalog_count=?,aligned=? WHERE book_id=? AND revision=?",
                        expectedRevision,audit.sourceId,audit.sourceBookId,audit.catalogHash,audit.expected,aligned?1:0,bookId,expectedRevision);});
            }
        }
        public <T>T read(Supplier<T> action){try(var permit=gate.acquire(false)){permit.check();requireRevision(revision(bookId,false));T value=action.get();requireRevision(revision(bookId,false));return value;}}
        @Override public Set<Entry<Integer,BookIndex>> entrySet(){ensureLoaded();return Collections.unmodifiableMap(rows).entrySet();}
    }
}
