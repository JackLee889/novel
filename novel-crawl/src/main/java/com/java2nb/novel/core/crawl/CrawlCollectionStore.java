package com.java2nb.novel.core.crawl;

import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.seo.ChapterContentReadiness;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Compact per-book checkpoints: two bitsets, never a cache containing chapter bodies. */
@Repository
public class CrawlCollectionStore {
    private final JdbcTemplate jdbc;
    private final Path contentRoot;
    public static final int VERIFY_BATCH = 100;
    private static final Duration VALIDATION_TTL = Duration.ofHours(24);

    public CrawlCollectionStore(JdbcTemplate jdbc, @Value("${content.save.path}") String contentPath) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(15);
        this.contentRoot = Path.of(contentPath);
    }

    public static final class Audit {
        public final long bookId;
        public final int sourceId;
        public final String sourceBookId, catalogHash;
        public final int expected;
        public final BitSet ready = new BitSet(), checked = new BitSet();
        public Instant validationStarted = Instant.now();
        public int failed, deferred;
        public boolean sourceCompleted;

        public Audit(long bookId, int sourceId, String sourceBookId, String hash, int expected) {
            this.bookId=bookId; this.sourceId=sourceId; this.sourceBookId=sourceBookId;
            this.catalogHash=hash; this.expected=expected;
        }
        public boolean complete() { return expected > 0 && ready.cardinality() == expected; }
        public int missing() { BitSet missing=(BitSet)checked.clone(); missing.andNot(ready); return missing.cardinality(); }
        public int unchecked() { return expected-checked.cardinality(); }
        public void mark(int position, boolean readable) { checked.set(position); ready.set(position, readable); }
    }

    public Audit open(long bookId, int sourceId, String sourceBookId, String hash, int expected) {
        return open(bookId,sourceId,sourceBookId,hash,expected,n->hash);
    }

    public Audit open(long bookId, int sourceId, String sourceBookId, String hash, int expected,
        java.util.function.IntFunction<String> prefixHash) {
        Audit audit=new Audit(bookId,sourceId,sourceBookId,hash,expected);
        jdbc.query("SELECT source_id,source_book_id,catalog_hash,expected_count,ready_bits,checked_bits,validation_started_at "
            + "FROM crawl_collection_state WHERE book_id=?", rs -> {
            Instant started=rs.getTimestamp("validation_started_at").toInstant();
            // An unfinished sweep resumes even when it takes longer than one day.
            // Expiration applies only after the previous sweep actually covered the entire catalog.
            BitSet checked=BitSet.valueOf(rs.getBytes("checked_bits"));
            int previousExpected=rs.getInt("expected_count");
            if(rs.getInt("source_id")==sourceId && sourceBookId.equals(rs.getString("source_book_id"))
                && previousExpected>0 && previousExpected<=expected
                && rs.getString("catalog_hash").equals(previousExpected==expected?hash:prefixHash.apply(previousExpected))
                && (BitSet.valueOf(rs.getBytes("ready_bits")).cardinality()<previousExpected || started.plus(VALIDATION_TTL).isAfter(Instant.now()))) {
                audit.validationStarted=started;
                audit.checked.or(checked);
                audit.ready.or(BitSet.valueOf(rs.getBytes("ready_bits")));
            }
        },bookId);
        return audit;
    }

    /** Key lookups, at most 100 bodies; no book_content table scans or SQL regex over LOBs. */
    @com.java2nb.novel.core.io.BackgroundIo
    public Set<Long> readyChapters(long bookId, List<BookIndex> chapters) {
        if(chapters.size()>VERIFY_BATCH) throw new IllegalArgumentException("Readiness batch exceeds 100");
        List<Long> dbIds=new ArrayList<>(); Set<Long> ready=new HashSet<>();
        for(BookIndex chapter:chapters) {
            String type=chapter.getStorageType();
            if(type==null || type.isBlank() || "db".equals(type)) dbIds.add(chapter.getId());
            else if("txt".equals(type) && ChapterContentReadiness.fileReady(contentRoot,bookId,chapter.getId())) ready.add(chapter.getId());
        }
        if(!dbIds.isEmpty()) {
            for(int from=0;from<dbIds.size();from+=10) {
                List<Long> slice=dbIds.subList(from,Math.min(from+10,dbIds.size()));
                String marks=String.join(",", Collections.nCopies(slice.size(),"?"));
                jdbc.query("SELECT index_id,CASE WHEN OCTET_LENGTH(content)<=2097152 THEN content ELSE NULL END AS content "
                    + "FROM book_content WHERE index_id IN ("+marks+")", rs -> {
                    if(readable(rs.getString("content"))) ready.add(rs.getLong("index_id"));
                },slice.toArray());
            }
        }
        return ready;
    }

    public static boolean readable(String body) {
        return body != null && !body.contains("正在手打中") && ChapterContentReadiness.hasReadableText(body);
    }

    public void catalogUnavailable(long bookId) {
        jdbc.update("UPDATE crawl_collection_state SET collection_status='UNVERIFIED',failed_count=1,updated_at=? WHERE book_id=?",
            Timestamp.from(Instant.now()),bookId);
    }

    /** Called only after the chapter callback has committed. A crash can lose verification, never invent it. */
    public void save(Audit audit) {
        Timestamp now=Timestamp.from(Instant.now());
        Object[] values={audit.sourceId,audit.sourceBookId,audit.catalogHash,audit.expected,audit.ready.cardinality(),
            audit.missing(),audit.unchecked(),audit.failed,audit.deferred,audit.ready.toByteArray(),audit.checked.toByteArray(),
            audit.sourceCompleted?1:0,audit.complete()?"COMPLETE":"INCOMPLETE",Timestamp.from(audit.validationStarted),now,audit.bookId};
        int changed=jdbc.update("UPDATE crawl_collection_state SET source_id=?,source_book_id=?,catalog_hash=?,expected_count=?,"
            + "ready_count=?,missing_count=?,unchecked_count=?,failed_count=?,deferred_count=?,ready_bits=?,checked_bits=?,"
            + "source_completed=?,collection_status=?,validation_started_at=?,updated_at=? WHERE book_id=?",values);
        if(changed==0) try { jdbc.update("INSERT INTO crawl_collection_state(source_id,source_book_id,catalog_hash,expected_count,"
            + "ready_count,missing_count,unchecked_count,failed_count,deferred_count,ready_bits,checked_bits,source_completed,"
            + "collection_status,validation_started_at,updated_at,book_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",values); }
        catch(org.springframework.dao.DuplicateKeyException unchangedWithinSameMillisecond) { /* UPDATE matched an unchanged row. */ }
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
