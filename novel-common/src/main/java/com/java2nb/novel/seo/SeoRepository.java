package com.java2nb.novel.seo;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookIndex;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Shared, persistent SEO control plane; every writer locks the site row before book state. */
@Repository
public class SeoRepository {
    private final JdbcTemplate jdbc;
    @Value("${website.name:小说精品屋}") private String fallbackSiteName="小说精品屋";
    @Value("${content.save.path:/data/books}") private String contentDirectory="/data/books";
    public SeoRepository(DataSource dataSource) { this.jdbc = new JdbcTemplate(dataSource); }

    public SeoSiteSettings settings() { return jdbc.query("SELECT * FROM seo_site_config WHERE id=1", this::site).stream().findFirst().orElse(SeoSiteSettings.disabled()); }
    private SeoSiteSettings lockSite() { return jdbc.query("SELECT * FROM seo_site_config WHERE id=1 FOR UPDATE", this::site).stream().findFirst().orElseThrow(() -> new IllegalStateException("Apply the additive SEO migration first")); }
    /** Call inside the enclosing business transaction before modifying book/chapter rows. */
    public void lockMutation() { lockSite(); }
    private SeoSiteSettings site(ResultSet r, int n) throws SQLException {
        return new SeoSiteSettings(r.getLong("epoch"), r.getBoolean("index_enabled"), r.getBoolean("chapters_index_enabled"),
            r.getBoolean("chapter_sitemap_enabled"), r.getString("default_title"), r.getString("default_description"),
            r.getString("keywords"), r.getString("baidu_verification"), r.getString("google_verification"));
    }
    @Transactional
    public SeoSiteSettings updateSettings(SeoSiteSettings value) { return updateSettings(value, 0); }
    @Transactional
    public SeoSiteSettings updateSettings(SeoSiteSettings value, long actorId) {
        SeoSiteSettings before = lockSite();
        if (before.epoch() != value.epoch()) throw new ConcurrentModificationException("SEO settings changed; refresh before saving");
        SeoSiteSettings clean = new SeoSiteSettings(value.epoch() + 1, value.indexEnabled(), value.chaptersIndexEnabled(), value.chapterSitemapEnabled(),
            SeoValidation.text(value.defaultTitle(),200), SeoValidation.text(value.defaultDescription(),500), SeoValidation.text(value.keywords(),300),
            SeoValidation.verification(value.baiduVerification()), SeoValidation.verification(value.googleVerification()));
        SeoSiteSettings unchanged=new SeoSiteSettings(before.epoch(),clean.indexEnabled(),clean.chaptersIndexEnabled(),clean.chapterSitemapEnabled(),clean.defaultTitle(),clean.defaultDescription(),clean.keywords(),clean.baiduVerification(),clean.googleVerification());
        if(before.equals(unchanged))return before;
        jdbc.update("UPDATE seo_site_config SET epoch=?,index_enabled=?,chapters_index_enabled=?,chapter_sitemap_enabled=?,default_title=?,default_description=?,keywords=?,baidu_verification=?,google_verification=?,modified_at=CURRENT_TIMESTAMP(3) WHERE id=1 AND epoch=?",
            clean.epoch(),clean.indexEnabled(),clean.chaptersIndexEnabled(),clean.chapterSitemapEnabled(),clean.defaultTitle(),clean.defaultDescription(),clean.keywords(),clean.baiduVerification(),clean.googleVerification(),before.epoch());
        enqueueAllLocked(UUID.randomUUID().toString());
        bumpCore(false);
        audit(actorId,"site_settings",null,before,clean);
        return clean;
    }
    public SeoBookOverride bookOverride(long bookId) {
        return jdbc.query("SELECT * FROM seo_book_override WHERE book_id=?", (r,n) -> new SeoBookOverride(r.getLong("book_id"),
            r.getObject("index_enabled") == null ? null : r.getBoolean("index_enabled"),r.getString("title"),r.getString("description"),r.getString("keywords")),bookId)
            .stream().findFirst().orElse(SeoBookOverride.inherited(bookId));
    }
    @Transactional
    public SeoBookOverride saveBookOverride(SeoBookOverride value, long expectedRevision, long actorId) {
        lockSite();
        Map<String,Object> current = state(value.bookId());
        if (((Number)current.get("revision")).longValue() != expectedRevision) throw new ConcurrentModificationException("Book SEO changed; refresh before saving");
        if (book(value.bookId()) == null) throw new IllegalArgumentException("Book does not exist");
        SeoBookOverride before = bookOverride(value.bookId());
        SeoBookOverride clean = new SeoBookOverride(value.bookId(),value.indexEnabled(),SeoValidation.text(value.title(),200),SeoValidation.text(value.description(),500),SeoValidation.text(value.keywords(),300));
        if(before.equals(clean))return before;
        jdbc.update("INSERT INTO seo_book_override(book_id,index_enabled,title,description,keywords) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE index_enabled=VALUES(index_enabled),title=VALUES(title),description=VALUES(description),keywords=VALUES(keywords)",
            clean.bookId(),clean.indexEnabled(),clean.title(),clean.description(),clean.keywords());
        dirty(value.bookId(),true,UUID.randomUUID().toString());
        audit(actorId,"book_override",value.bookId(),before,clean);
        return clean;
    }
    @Transactional
    public SeoBookOverride saveBookOverride(SeoBookOverride value) { return saveBookOverride(value,((Number)state(value.bookId()).get("revision")).longValue(),0); }
    @Transactional
    public void markBookDirty(long bookId) { lockSite(); dirty(bookId,true,UUID.randomUUID().toString()); }
    @Transactional
    public void markBookRemoved(long bookId) { markBookDirty(bookId); }
    @Transactional
    public String enqueueBook(long bookId) { lockSite(); String job=UUID.randomUUID().toString(); dirty(bookId,false,job); return job; }
    @Transactional
    public String enqueueAll() { lockSite(); String job=UUID.randomUUID().toString(); enqueueAllLocked(job);syncVisibility();bumpCore(false);return job; }
    private void enqueueAllLocked(String job) {
        jdbc.update("INSERT INTO seo_book_state(book_id,job_id,status) SELECT id,?,'pending' FROM book ON DUPLICATE KEY UPDATE revision=revision+1,job_id=VALUES(job_id),status='pending',attempts=0,next_attempt_at=NULL,lease_until=NULL,lease_token=NULL,last_error=NULL",job);
        // A book removed by another tool must also lose its old published snapshot.
        jdbc.update("UPDATE seo_book_state s LEFT JOIN book b ON b.id=s.book_id SET s.revision=s.revision+1,s.status='pending',s.job_id=?,s.next_attempt_at=NULL,s.lease_until=NULL,s.lease_token=NULL WHERE b.id IS NULL",job);
    }
    private void dirty(long bookId, boolean changed, String job) {
        if (bookId <= 0) throw new IllegalArgumentException("bookId must be positive");
        jdbc.update("INSERT INTO seo_book_state(book_id,job_id,status,modified_at) VALUES(?,?,'pending',CASE WHEN ? THEN CURRENT_TIMESTAMP(3) ELSE NULL END) ON DUPLICATE KEY UPDATE revision=revision+1,modified_at=CASE WHEN ? THEN CURRENT_TIMESTAMP(3) ELSE modified_at END,job_id=VALUES(job_id),status='pending',attempts=0,next_attempt_at=NULL,lease_until=NULL,lease_token=NULL,last_error=NULL",bookId,job,changed,changed);
        List<Boolean> previous=jdbc.query("SELECT published FROM seo_book_visibility WHERE book_id=?",(r,n)->r.getBoolean(1),bookId);
        boolean published=jdbc.queryForObject("SELECT EXISTS(SELECT b.id FROM book b LEFT JOIN seo_book_override o ON o.book_id=b.id WHERE b.id=? AND b.status=1 AND b.word_count>0 AND b.last_index_id IS NOT NULL AND (o.index_enabled IS NULL OR o.index_enabled=1))",Boolean.class,bookId);
        if(previous.isEmpty() || previous.getFirst()!=published)
            jdbc.update("INSERT INTO seo_book_visibility(book_id,published) VALUES(?,?) ON DUPLICATE KEY UPDATE published=VALUES(published)",bookId,published);
        jdbc.update("UPDATE seo_book_state SET observed_hash=? WHERE book_id=?",observation(bookId).hash(),bookId);
        bumpCore(!previous.isEmpty()&&previous.getFirst()!=published);
    }
    private void bumpCore(boolean visibility){jdbc.update("UPDATE seo_sitemap_control SET revision=revision+1,visibility_revision=visibility_revision+?,status='pending',next_attempt_at=NULL,lease_until=NULL,lease_token=NULL WHERE id=1",visibility?1:0);}
    private void syncVisibility(){
        long changed=jdbc.queryForObject("SELECT COUNT(*) FROM seo_book_visibility v LEFT JOIN book b ON b.id=v.book_id LEFT JOIN seo_book_override o ON o.book_id=v.book_id WHERE v.published<>(CASE WHEN b.status=1 AND b.word_count>0 AND b.last_index_id IS NOT NULL AND (o.index_enabled IS NULL OR o.index_enabled=1) THEN 1 ELSE 0 END)",Long.class);
        if(changed>0){
            bumpCore(true);
            jdbc.update("UPDATE seo_book_visibility v LEFT JOIN book b ON b.id=v.book_id LEFT JOIN seo_book_override o ON o.book_id=v.book_id SET v.published=(CASE WHEN b.status=1 AND b.word_count>0 AND b.last_index_id IS NOT NULL AND (o.index_enabled IS NULL OR o.index_enabled=1) THEN 1 ELSE 0 END) WHERE v.published<>(CASE WHEN b.status=1 AND b.word_count>0 AND b.last_index_id IS NOT NULL AND (o.index_enabled IS NULL OR o.index_enabled=1) THEN 1 ELSE 0 END)");
        }
    }
    private void audit(long actorId,String action,Long bookId,Object before,Object after) {
        var mapper=JsonMapper.builder().build();
        jdbc.update("INSERT INTO seo_audit_log(actor_id,action,book_id,before_data,after_data) VALUES(?,?,?,?,?)",actorId==0?null:actorId,action,bookId,mapper.writeValueAsString(before),mapper.writeValueAsString(after));
    }
    public Map<String,Object> state(long bookId) {
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM seo_book_state WHERE book_id=?",this::stateMap,bookId);
        return rows.isEmpty()?new LinkedHashMap<>(Map.of("bookId",Long.toString(bookId),"revision",0L,"builtRevision",0L,"builtEpoch",0L,"status","uninitialized","urlCount",0L)):rows.getFirst();
    }
    private Map<String,Object> stateMap(ResultSet r,int n) throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("bookId",Long.toString(r.getLong("book_id")));m.put("revision",r.getLong("revision"));m.put("builtRevision",r.getLong("built_revision"));m.put("builtEpoch",r.getLong("built_epoch"));
        m.put("jobId",r.getString("job_id"));m.put("status",r.getString("status"));m.put("attempts",r.getInt("attempts"));m.put("urlCount",r.getLong("url_count"));m.put("chapterCount",r.getLong("chapter_count"));m.put("lastError",r.getString("last_error"));
        m.put("modifiedAt",r.getTimestamp("modified_at"));m.put("startedAt",r.getTimestamp("started_at"));m.put("completedAt",r.getTimestamp("completed_at"));m.put("durationMs",r.getObject("duration_ms"));m.put("leaseUntil",r.getTimestamp("lease_until"));m.put("nextAttemptAt",r.getTimestamp("next_attempt_at"));m.put("builtFiles",r.getString("built_files"));m.put("builtOrigin",r.getString("built_origin"));return m;
    }
    public List<Map<String,Object>> jobs(int limit) { return jdbc.query("SELECT * FROM seo_book_state ORDER BY COALESCE(completed_at,modified_at) DESC,book_id LIMIT ?",this::stateMap,Math.max(1,Math.min(200,limit))); }
    public List<SeoBookOverride> configuredOverrides(int limit) {
        return jdbc.query("SELECT * FROM seo_book_override ORDER BY book_id LIMIT ?",(r,n)->new SeoBookOverride(r.getLong("book_id"),r.getObject("index_enabled")==null?null:r.getBoolean("index_enabled"),r.getString("title"),r.getString("description"),r.getString("keywords")),Math.max(1,Math.min(1000,limit)));
    }
    public Map<String,Object> diagnostics() {
        Map<String,Object> m=new LinkedHashMap<>();m.put("settings",settings());
        m.put("books",jdbc.queryForObject("SELECT COUNT(*) FROM book",Long.class));m.put("publishedBooks",jdbc.queryForObject("SELECT COUNT(*) FROM book WHERE status=1",Long.class));
        m.put("states",jdbc.queryForList("SELECT status,COUNT(*) AS count,COALESCE(SUM(url_count),0) AS urls FROM seo_book_state GROUP BY status"));
        m.put("lastCompletedAt",jdbc.queryForObject("SELECT MAX(completed_at) FROM seo_book_state",java.sql.Timestamp.class));m.put("coreSnapshot",coreState());return m;
    }
    public List<Map<String,Object>> listBooks(String query,int offset,int limit) {
        String term=query==null?"":SeoValidation.text(query,100);
        long numericId=-1;if(term.matches("[1-9][0-9]{0,18}")){try{numericId=Long.parseLong(term);}catch(NumberFormatException ignored){}}
        return jdbc.query("SELECT b.id,b.book_name,b.author_name,b.status,b.is_vip,b.word_count,COALESCE(s.revision,0) revision FROM book b LEFT JOIN seo_book_state s ON s.book_id=b.id WHERE b.book_name LIKE ? OR b.author_name LIKE ? OR b.id=? ORDER BY b.id LIMIT ? OFFSET ?",(r,n)->{
            Map<String,Object> m=new LinkedHashMap<>();m.put("bookId",Long.toString(r.getLong("id")));m.put("bookName",r.getString("book_name"));m.put("authorName",r.getString("author_name"));m.put("status",r.getInt("status"));m.put("isVip",r.getInt("is_vip"));m.put("wordCount",r.getInt("word_count"));m.put("revision",r.getLong("revision"));return m;
        },"%"+term+"%","%"+term+"%",numericId,Math.max(1,Math.min(100,limit)),Math.max(0,offset));
    }
    public Map<String,Object> bookPreview(long bookId,Long chapterId) {
        Book b=book(bookId);if(b==null)throw new IllegalArgumentException("Book does not exist");
        SeoSiteSettings site=settings();SeoBookOverride override=bookOverride(bookId);Map<String,Object> m=new LinkedHashMap<>();
        m.put("bookId",Long.toString(bookId));m.put("bookName",b.getBookName());m.put("authorName",b.getAuthorName());m.put("status",b.getStatus());m.put("isVip",b.getIsVip());m.put("wordCount",b.getWordCount());m.put("lastIndexId",b.getLastIndexId()==null?null:b.getLastIndexId().toString());
        m.put("revision",state(bookId).get("revision"));m.put("override",override);m.put("title",override.title());m.put("description",override.description());m.put("keywords",override.keywords());
        String reason=!PublicBookPolicy.isPublic(b)?"unpublished":!site.indexEnabled()?"site_disabled":Boolean.FALSE.equals(override.indexEnabled())?"book_noindex":b.getLastIndexId()==null||b.getWordCount()==null||b.getWordCount()<=0?"missing_content":"";
        m.put("indexable",reason.isEmpty());m.put("excludedReason",reason);
        String name=jdbc.query("SELECT name FROM website_info WHERE id=1",(r,n)->r.getString(1)).stream().filter(Objects::nonNull).filter(v->!v.isBlank()).findFirst().orElse(fallbackSiteName);
        m.put("resolvedTitle",SeoText.bookTitle(b,override,SeoText.plain(name)));m.put("resolvedDescription",SeoText.bookDescription(b,override));m.put("resolvedKeywords",SeoText.bookKeywords(b,override));m.put("robots",reason.isEmpty()?"index,follow":"noindex,follow");
        if(chapterId!=null){List<ChapterRow> chapters=jdbc.query("SELECT id,book_id,index_num,index_name,word_count,is_vip,storage_type,create_time,update_time FROM book_index WHERE book_id=? AND id=?",this::chapter,bookId,chapterId);
            if(chapters.isEmpty())throw new IllegalArgumentException("Chapter does not exist in this book");BookIndex c=chapters.getFirst().chapter();
            m.put("chapterId",chapterId.toString());m.put("indexName",c.getIndexName());m.put("chapterVip",c.getIsVip());m.put("contentChecked",false);
            String chapterReason=!reason.isEmpty()?reason:!site.chaptersIndexEnabled()?"chapter_disabled":Byte.valueOf((byte)1).equals(c.getIsVip())?"paid_chapter":c.getWordCount()==null||c.getWordCount()<=0?"empty_chapter":"";
            m.put("chapterIndexable",chapterReason.isEmpty());m.put("indexable",chapterReason.isEmpty());m.put("chapterExcludedReason",chapterReason);m.put("excludedReason",chapterReason);m.put("robots",chapterReason.isEmpty()?"index,follow":"noindex,follow");}
        return m;
    }
    public Book book(long bookId) { return jdbc.query("SELECT id,book_name,author_name,book_desc,cat_id,cat_name,status,is_vip,word_count,last_index_id,last_index_update_time,create_time FROM book WHERE id=?",(r,n)->book(r,n),bookId).stream().findFirst().orElse(null); }
    private Book book(ResultSet r,int n) throws SQLException {
        Book b=new Book();b.setId(r.getLong("id"));b.setBookName(r.getString("book_name"));b.setAuthorName(r.getString("author_name"));b.setBookDesc(r.getString("book_desc"));b.setCatId(r.getInt("cat_id"));b.setCatName(r.getString("cat_name"));b.setStatus(r.getByte("status"));b.setIsVip(r.getByte("is_vip"));b.setWordCount(r.getInt("word_count"));b.setLastIndexId(r.getObject("last_index_id")==null?null:r.getLong("last_index_id"));b.setLastIndexUpdateTime(r.getTimestamp("last_index_update_time"));b.setCreateTime(r.getTimestamp("create_time"));return b;
    }
    public record ChapterRow(BookIndex chapter) {}
    private ChapterRow chapter(ResultSet r,int n)throws SQLException {BookIndex c=new BookIndex();c.setId(r.getLong("id"));c.setBookId(r.getLong("book_id"));c.setIndexNum(r.getInt("index_num"));c.setIndexName(r.getString("index_name"));c.setWordCount(r.getInt("word_count"));c.setIsVip(r.getByte("is_vip"));c.setStorageType(r.getString("storage_type"));c.setCreateTime(r.getTimestamp("create_time"));c.setUpdateTime(r.getTimestamp("update_time"));return new ChapterRow(c);}
    @org.springframework.beans.factory.annotation.Value("${seo.chapter-index-hint:false}") private boolean chapterIndexHint;
    private JdbcTemplate boundedJdbc(){var bounded=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));bounded.setQueryTimeout(15);return bounded;}
    public List<ChapterRow> chapterPage(long bookId,long afterId,int limit) {
        return boundedJdbc().query("SELECT id,book_id,word_count,is_vip FROM book_index"
            +(chapterIndexHint?" FORCE INDEX(key_bookId)":"")+" WHERE book_id=? AND id>? ORDER BY id LIMIT ?",(r,n)->{
                BookIndex c=new BookIndex();c.setId(r.getLong("id"));c.setBookId(r.getLong("book_id"));c.setWordCount(r.getInt("word_count"));c.setIsVip(r.getByte("is_vip"));return new ChapterRow(c);
            },bookId,afterId,Math.max(1,Math.min(100,limit)));
    }
    /** Renew only a still-owned, unexpired ticket. A superseding mutation always wins. */
    public boolean renewBuild(Ticket ticket){
        return jdbc.update("UPDATE seo_book_state SET lease_until=TIMESTAMPADD(MINUTE,20,CURRENT_TIMESTAMP(3)) WHERE book_id=? AND revision=? AND status='running' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(3)",ticket.bookId(),ticket.revision(),ticket.leaseToken())==1;
    }
    public Set<Long> readyDatabaseIds(List<Long> ids) {
        if(ids.isEmpty())return Set.of();if(ids.size()>500)throw new IllegalArgumentException("Content readiness batch exceeds 500");
        String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
        // ICU and Java both support these patterns. Parameters avoid SQL backslash interpretation.
        String markup="(?is)<(script|style)[^>]*>.*?</\\1\\s*>|<!--.*?-->|</?[A-Za-z][^>]*>";
        String spaces="(?i)&(?:nbsp|#0*(?:9|10|13|32|160)|#x0*(?:9|a|d|20|a0));";
        List<Object> parameters=new ArrayList<>(ids);parameters.add(markup);parameters.add(spaces);parameters.add("[^\\s\\p{Z}]");
        return new HashSet<>(boundedJdbc().query("SELECT index_id FROM book_content WHERE index_id IN ("+marks+") AND content IS NOT NULL AND REGEXP_LIKE(REGEXP_REPLACE(REGEXP_REPLACE(content,?,''),?,''),?)",(r,n)->r.getLong(1),parameters.toArray()));
    }
    private static boolean databaseStorage(String type){return type==null||type.isBlank()||type.equals("db");}
    private boolean fileContentReady(long bookId,long chapterId,String type){return "txt".equals(type)&&ChapterContentReadiness.fileReady(java.nio.file.Path.of(contentDirectory),bookId,chapterId);}
    public long chapterCount(long bookId){return jdbc.queryForObject("SELECT COUNT(*) FROM book_index WHERE book_id=?",Long.class,bookId);}
    public List<Map<String,Object>> publicBooks(){return jdbc.query("SELECT b.id,b.last_index_update_time,s.modified_at,s.chapter_count FROM book b LEFT JOIN seo_book_state s ON s.book_id=b.id LEFT JOIN seo_book_override o ON o.book_id=b.id WHERE b.status=1 AND b.word_count>0 AND b.last_index_id IS NOT NULL AND (o.index_enabled IS NULL OR o.index_enabled=1) ORDER BY b.id",(r,n)->{Map<String,Object> m=new LinkedHashMap<>();m.put("bookId",r.getLong("id"));m.put("modifiedAt",r.getTimestamp("modified_at"));m.put("lastChapterAt",r.getTimestamp("last_index_update_time"));m.put("chapterCount",r.getLong("chapter_count"));return m;});}
    public List<Map<String,Object>> publicCategories(){return jdbc.queryForList("SELECT id,name FROM book_category ORDER BY sort,id");}
    public long publicBookCount(String column,Integer value){
        if(column!=null&&!Set.of("cat_id","work_direction","book_status").contains(column))throw new IllegalArgumentException("Unknown public category dimension");
        return column==null?jdbc.queryForObject("SELECT COUNT(*) FROM book WHERE status=1 AND word_count>0",Long.class):jdbc.queryForObject("SELECT COUNT(*) FROM book WHERE status=1 AND word_count>0 AND "+column+"=?",Long.class,value);
    }
    public record Ticket(long bookId,long revision,long epoch,Instant modifiedAt,String jobId,String leaseToken){
        public Ticket(long bookId,long revision,long epoch,Instant modifiedAt,String jobId){this(bookId,revision,epoch,modifiedAt,jobId,UUID.randomUUID().toString());}
    }
    @Transactional
    public Optional<Ticket> claim() {return claimLocked(null);}
    /** Coalesce ongoing imports; explicit operator claims keep their immediate behavior. */
    @Transactional
    public Optional<Ticket> claim(long quietSeconds) {return claimLocked(null,Math.max(0,Math.min(300,quietSeconds)));}
    @Transactional
    public Optional<Ticket> claimBook(long bookId) {return claimLocked(bookId);}
    private Optional<Ticket> claimLocked(Long bookId) {
        return claimLocked(bookId,0);
    }
    private Optional<Ticket> claimLocked(Long bookId,long quietSeconds) {
        SeoSiteSettings site=lockSite();
        // Initial builds are immediate. Continuous updates still get an attempt at least
        // every five minutes; otherwise wait for a quiet interval before rereading bodies.
        String quiet=quietSeconds==0?"":" AND (status<>'pending' OR modified_at IS NULL OR started_at IS NULL OR TIMESTAMPDIFF(SECOND,modified_at,CURRENT_TIMESTAMP(3))>=? OR TIMESTAMPDIFF(SECOND,started_at,CURRENT_TIMESTAMP(3))>=300)";
        String sql="SELECT book_id,revision,modified_at,job_id FROM seo_book_state WHERE ((status IN ('pending','failed') AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP(3))) OR (status='running' AND lease_until<CURRENT_TIMESTAMP(3)))"+quiet+(bookId==null?"":" AND book_id=?")+" ORDER BY book_id LIMIT 1 FOR UPDATE";
        List<Object> args=new ArrayList<>();if(quietSeconds>0)args.add(quietSeconds);if(bookId!=null)args.add(bookId);
        List<Ticket> available=jdbc.query(sql,(r,n)->new Ticket(r.getLong("book_id"),r.getLong("revision"),site.epoch(),r.getTimestamp("modified_at")==null?null:r.getTimestamp("modified_at").toInstant(),r.getString("job_id")),args.toArray());
        if(available.isEmpty())return Optional.empty();Ticket ticket=available.getFirst();
        jdbc.update("UPDATE seo_book_state SET status='running',attempts=attempts+1,started_at=CURRENT_TIMESTAMP(3),completed_at=NULL,duration_ms=NULL,lease_until=TIMESTAMPADD(MINUTE,20,CURRENT_TIMESTAMP(3)),lease_token=?,last_error=NULL WHERE book_id=? AND revision=?",ticket.leaseToken(),ticket.bookId(),ticket.revision());return Optional.of(ticket);
    }
    @Transactional
    public boolean complete(Ticket ticket,String files,String origin,long urls,boolean excluded) {
        return complete(ticket,files,origin,urls,excluded,chapterCount(ticket.bookId()));
    }
    @Transactional
    public boolean complete(Ticket ticket,String files,String origin,long urls,boolean excluded,long chapterCount) {
        return complete(ticket,files,origin,urls,excluded,chapterCount,elapsedBookMillis(ticket));
    }
    @Transactional
    public boolean complete(Ticket ticket,String files,String origin,long urls,boolean excluded,long chapterCount,long durationMs) {
        SeoSiteSettings site=lockSite();if(site.epoch()!=ticket.epoch())return false;
        boolean success=jdbc.update("UPDATE seo_book_state SET built_revision=?,built_epoch=?,built_files=?,built_origin=?,url_count=?,chapter_count=?,status=?,completed_at=CURRENT_TIMESTAMP(3),duration_ms=?,lease_until=NULL,lease_token=NULL,next_attempt_at=NULL,last_error=NULL WHERE book_id=? AND revision=? AND status='running' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(3)",
            ticket.revision(),ticket.epoch(),files,origin,urls,chapterCount,excluded?"excluded":"complete",Math.max(0,durationMs),ticket.bookId(),ticket.revision(),ticket.leaseToken())==1;
        if(success)bumpCore(false);return success;
    }
    @Transactional
    public void failed(Ticket ticket,String message) {failed(ticket,message,elapsedBookMillis(ticket));}
    @Transactional
    public boolean failed(Ticket ticket,String message,long durationMs) {
        if(lockSite().epoch()!=ticket.epoch())return false;
        return jdbc.update("UPDATE seo_book_state SET status='failed',completed_at=CURRENT_TIMESTAMP(3),duration_ms=?,lease_until=NULL,lease_token=NULL,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 5 MINUTE),last_error=? WHERE book_id=? AND revision=? AND status='running' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(3)",Math.max(0,durationMs),SeoValidation.text(message,300),ticket.bookId(),ticket.revision(),ticket.leaseToken())==1;
    }
    private long elapsedBookMillis(Ticket ticket){return jdbc.query("SELECT started_at FROM seo_book_state WHERE book_id=? AND lease_token=?",(r,n)->elapsedMillis(r.getTimestamp(1)),ticket.bookId(),ticket.leaseToken()).stream().findFirst().orElse(0L);}
    private static long elapsedMillis(java.sql.Timestamp started){return started==null?0:Math.max(0,java.time.Duration.between(started.toInstant(),Instant.now()).toMillis());}
    public List<Map<String,Object>> publishedStates(long epoch,String origin) {
        return jdbc.query("SELECT s.* FROM seo_book_state s JOIN book b ON b.id=s.book_id LEFT JOIN seo_book_override o ON o.book_id=b.id WHERE s.built_revision>0 AND s.built_files IS NOT NULL AND s.built_files<>'' AND s.built_epoch=? AND s.built_origin=? AND b.status=1 AND b.word_count>0 AND b.last_index_id IS NOT NULL AND (o.index_enabled IS NULL OR o.index_enabled=1) ORDER BY s.book_id",this::stateMap,epoch,origin);
    }
    public boolean filePublished(String file,long epoch,String origin){
        var match=java.util.regex.Pattern.compile("chapters-([0-9]{1,19})-r[0-9]{1,19}-e[0-9]{1,19}-p[0-9]{1,8}-[0-9a-f-]{36}\\.xml\\.gz").matcher(file);if(!match.matches())return false;
        long bookId;try{bookId=Long.parseLong(match.group(1));}catch(NumberFormatException e){return false;}
        List<String> rows=jdbc.query("SELECT s.built_files FROM seo_book_state s JOIN book b ON b.id=s.book_id LEFT JOIN seo_book_override o ON o.book_id=b.id WHERE s.book_id=? AND s.built_revision>0 AND s.built_files IS NOT NULL AND s.built_files<>'' AND s.built_epoch=? AND s.built_origin=? AND b.status=1 AND b.word_count>0 AND b.last_index_id IS NOT NULL AND (o.index_enabled IS NULL OR o.index_enabled=1)",(r,n)->r.getString(1),bookId,epoch,origin);
        return rows.stream().filter(Objects::nonNull).anyMatch(s->Arrays.asList(s.split("\n")).contains(file));
    }
    public record BootstrapBatch(long nextCursor,int scanned,int added,boolean complete) { }
    private record BootstrapBook(long id,boolean published) { }
    /** A caller advances its cursor only after this independent, bounded transaction commits. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
        isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,timeout=10)
    public BootstrapBatch bootstrapMissingStates(long afterId,int limit) {
        if(afterId<0)throw new IllegalArgumentException("Invalid bootstrap cursor");
        int size=Math.max(1,Math.min(50,limit));
        List<Long> ids=jdbc.query("SELECT id FROM book WHERE id>? ORDER BY id LIMIT ?",(r,n)->r.getLong(1),afterId,size);
        if(ids.isEmpty())return new BootstrapBatch(afterId,0,0,true);
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        Set<Long> states=new HashSet<>(jdbc.query("SELECT book_id FROM seo_book_state WHERE book_id IN ("+placeholders+")",(r,n)->r.getLong(1),ids.toArray()));
        Set<Long> visibility=new HashSet<>(jdbc.query("SELECT book_id FROM seo_book_visibility WHERE book_id IN ("+placeholders+")",(r,n)->r.getLong(1),ids.toArray()));
        int added=0;
        if(states.size()!=ids.size()||visibility.size()!=ids.size()) {
            lockSite();
            // Ordinary bounded reads, never INSERT ... SELECT (which locks the book range).
            List<BootstrapBook> books=jdbc.query("SELECT b.id,(CASE WHEN b.status=1 AND b.word_count>0 AND b.last_index_id IS NOT NULL AND (o.index_enabled IS NULL OR o.index_enabled=1) THEN 1 ELSE 0 END) AS published FROM book b LEFT JOIN seo_book_override o ON o.book_id=b.id WHERE b.id IN ("+placeholders+") ORDER BY b.id",
                (r,n)->new BootstrapBook(r.getLong(1),r.getBoolean(2)),ids.toArray());
            List<Object> stateArgs=new ArrayList<>(),visibilityArgs=new ArrayList<>();
            for(var book:books) {
                if(!states.contains(book.id()))Collections.addAll(stateArgs,book.id(),UUID.randomUUID().toString());
                if(!visibility.contains(book.id()))Collections.addAll(visibilityArgs,book.id(),book.published());
            }
            if(!stateArgs.isEmpty())added=jdbc.update("INSERT IGNORE INTO seo_book_state(book_id,job_id,status) VALUES "+String.join(",",Collections.nCopies(stateArgs.size()/2,"(?,?,'pending')")),stateArgs.toArray());
            if(!visibilityArgs.isEmpty())jdbc.update("INSERT IGNORE INTO seo_book_visibility(book_id,published) VALUES "+String.join(",",Collections.nCopies(visibilityArgs.size()/2,"(?,?)")),visibilityArgs.toArray());
            if(added>0)bumpCore(false);
        }
        return new BootstrapBatch(ids.getLast(),ids.size(),added,ids.size()<size);
    }
    /** Each source query uses its primary-key cursor and returns at most limit IDs. No global lock. */
    public List<Long> reconciliationIds(long afterId,int limit){
        int size=Math.max(1,Math.min(100,limit));Set<Long> ids=new TreeSet<>();
        ids.addAll(jdbc.query("SELECT id FROM book WHERE id>? ORDER BY id LIMIT ?",(r,n)->r.getLong(1),afterId,size));
        ids.addAll(jdbc.query("SELECT book_id FROM seo_book_state WHERE book_id>? ORDER BY book_id LIMIT ?",(r,n)->r.getLong(1),afterId,size));
        ids.addAll(jdbc.query("SELECT book_id FROM seo_book_visibility WHERE book_id>? ORDER BY book_id LIMIT ?",(r,n)->r.getLong(1),afterId,size));
        return ids.stream().limit(size).toList();
    }
    /** One short transaction per book. An unknown fingerprint establishes a baseline without rebuilding. */
    @Transactional
    public boolean reconcileBook(long bookId){
        lockSite();
        List<Map<String,Object>> states=jdbc.queryForList("SELECT revision,observed_hash FROM seo_book_state WHERE book_id=? FOR UPDATE",bookId);
        Observation current=observation(bookId);
        List<Boolean> previous=jdbc.query("SELECT published FROM seo_book_visibility WHERE book_id=?",(r,n)->r.getBoolean(1),bookId);
        if(states.isEmpty()){
            if(!current.exists()&&previous.isEmpty())return false;
            dirty(bookId,false,UUID.randomUUID().toString());return true;
        }
        Object baseline=states.getFirst().get("observed_hash");
        boolean visibilityChanged=!previous.isEmpty()&&previous.getFirst()!=current.published();
        boolean changed=visibilityChanged||(baseline!=null&&!baseline.equals(current.hash()))||(!current.exists()&&baseline==null);
        if(changed){
            // Discovery time is not a reliable content lastmod. Preserve the business modification clock.
            dirty(bookId,false,UUID.randomUUID().toString());return true;
        }
        long revision=((Number)states.getFirst().get("revision")).longValue();
        if(!Objects.equals(baseline,current.hash()))
            jdbc.update("UPDATE seo_book_state SET observed_hash=? WHERE book_id=? AND revision=?",current.hash(),bookId,revision);
        if(previous.isEmpty())
            jdbc.update("INSERT INTO seo_book_visibility(book_id,published) VALUES(?,?) ON DUPLICATE KEY UPDATE published=VALUES(published)",bookId,current.published());
        return false;
    }
    private record Observation(boolean exists,boolean published,String hash){}
    private Observation observation(long bookId){
        // Book summary and its last chapter are O(1) indexed lookups; recrawl timestamps/counters are omitted.
        List<Observation> rows=jdbc.query("SELECT b.id,b.status,b.book_name,b.author_name,b.book_desc,b.pic_url,b.cat_id,b.cat_name,b.work_direction,b.book_status,b.is_vip,b.word_count,b.last_index_id,o.index_enabled,o.title,o.description,o.keywords,c.id AS chapter_id,c.index_num,c.index_name,c.word_count AS chapter_words,c.is_vip AS chapter_vip,c.storage_type FROM book b LEFT JOIN seo_book_override o ON o.book_id=b.id LEFT JOIN book_index c ON c.id=b.last_index_id AND c.book_id=b.id WHERE b.id=?",(r,n)->{
            List<String> values=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++){Object value=r.getObject(i);values.add(value==null?"-":value.toString().length()+":"+value);}
            boolean published=r.getInt("status")==1&&r.getInt("word_count")>0&&r.getObject("last_index_id")!=null&&(r.getObject("index_enabled")==null||r.getBoolean("index_enabled"));
            return new Observation(true,published,sha256(String.join("\n",values)));
        },bookId);
        return rows.stream().findFirst().orElse(new Observation(false,false,sha256("missing")));
    }
    private static String sha256(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public record CoreTicket(long epoch,long revision,long visibilityRevision,String leaseToken){
        public CoreTicket(long epoch,long revision,long visibilityRevision){this(epoch,revision,visibilityRevision,UUID.randomUUID().toString());}
    }
    @Transactional
    public CoreTicket coreTicket(){
        var site=lockSite();var control=jdbc.queryForMap("SELECT revision,visibility_revision FROM seo_sitemap_control WHERE id=1 FOR UPDATE");
        var ticket=new CoreTicket(site.epoch(),((Number)control.get("revision")).longValue(),((Number)control.get("visibility_revision")).longValue());
        int claimed=jdbc.update("UPDATE seo_sitemap_control SET status='running',started_at=CURRENT_TIMESTAMP(3),completed_at=NULL,duration_ms=NULL,last_error=NULL,lease_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 20 MINUTE) WHERE id=1 AND (status<>'running' OR lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(3)) AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP(3))",ticket.leaseToken());
        return claimed==1?ticket:null;
    }
    public Map<String,Object> coreState(){
        return jdbc.queryForMap("SELECT revision,visibility_revision AS visibilityRevision,built_revision AS builtRevision,built_visibility_revision AS builtVisibilityRevision,built_epoch AS builtEpoch,built_origin AS builtOrigin,generation,index_file AS indexFile,core_files AS coreFiles,modified_at AS modifiedAt,index_hash AS indexHash,url_count AS urlCount,status,started_at AS startedAt,completed_at AS completedAt,duration_ms AS durationMs,last_error AS lastError,lease_until AS leaseUntil,next_attempt_at AS nextAttemptAt FROM seo_sitemap_control WHERE id=1");
    }
    public record SnapshotFiles(long bookId,String files,String activeLeaseToken){}
    /** Keep every last-successful manifest, including pending, hidden and failed books. */
    public List<SnapshotFiles> snapshotFilePage(long afterId,int limit){
        return jdbc.query("SELECT book_id,built_files,CASE WHEN status='running' AND lease_until>CURRENT_TIMESTAMP(3) THEN lease_token ELSE NULL END AS active_token FROM seo_book_state WHERE book_id>? ORDER BY book_id LIMIT ?",
            (r,n)->new SnapshotFiles(r.getLong("book_id"),r.getString("built_files"),r.getString("active_token")),afterId,Math.max(1,Math.min(1000,limit)));
    }
    @Transactional
    public boolean completeCore(CoreTicket ticket,String origin,String generation,String indexFile,String coreFiles,String hash,long urls){
        long duration=jdbc.query("SELECT started_at FROM seo_sitemap_control WHERE id=1 AND lease_token=?",(r,n)->elapsedMillis(r.getTimestamp(1)),ticket.leaseToken()).stream().findFirst().orElse(0L);
        return completeCore(ticket,origin,generation,indexFile,coreFiles,hash,urls,duration);
    }
    @Transactional
    public boolean completeCore(CoreTicket ticket,String origin,String generation,String indexFile,String coreFiles,String hash,long urls,long durationMs){
        var site=lockSite();if(site.epoch()!=ticket.epoch())return false;
        return jdbc.update("UPDATE seo_sitemap_control SET built_revision=?,built_visibility_revision=?,built_epoch=?,built_origin=?,generation=?,index_file=?,core_files=?,modified_at=CASE WHEN index_hash=? THEN modified_at ELSE CURRENT_TIMESTAMP(3) END,index_hash=?,url_count=?,status='complete',completed_at=CURRENT_TIMESTAMP(3),duration_ms=?,last_error=NULL,next_attempt_at=NULL,lease_token=NULL,lease_until=NULL WHERE id=1 AND revision=? AND visibility_revision=? AND status='running' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(3)",ticket.revision(),ticket.visibilityRevision(),ticket.epoch(),origin,generation,indexFile,coreFiles,hash,hash,urls,Math.max(0,durationMs),ticket.revision(),ticket.visibilityRevision(),ticket.leaseToken())==1;
    }
    @Transactional
    public boolean failedCore(CoreTicket ticket,String message,long durationMs){
        if(lockSite().epoch()!=ticket.epoch())return false;
        return jdbc.update("UPDATE seo_sitemap_control SET status='failed',completed_at=CURRENT_TIMESTAMP(3),duration_ms=?,last_error=?,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 5 MINUTE),lease_until=NULL,lease_token=NULL WHERE id=1 AND revision=? AND visibility_revision=? AND status='running' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(3)",Math.max(0,durationMs),SeoValidation.text(message,300),ticket.revision(),ticket.visibilityRevision(),ticket.leaseToken())==1;
    }
}
