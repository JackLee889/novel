package com.java2nb.novel.seo;

import com.java2nb.novel.entity.BookIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** One builder per instance and a persistent cross-instance claim. No work is started by a GET. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeoSitemapWorker {
    private static final int CHAPTER_BATCH_SIZE=100;
    private final SeoRepository repository;
    private final SeoService seo;
    private final SeoProperties properties;
    private boolean initialized;
    private com.java2nb.novel.core.io.BackgroundIoGate ioGate=com.java2nb.novel.core.io.BackgroundIoGate.disabled();
    @Autowired public void setIoGate(com.java2nb.novel.core.io.BackgroundIoGate value){ioGate=value;}
    @org.springframework.beans.factory.annotation.Value("${seo.budgeted-build-enabled:false}") private boolean budgeted;
    @org.springframework.beans.factory.annotation.Value("${seo.core-min-build-interval-ms:60000}") private long coreMinBuildIntervalMs=60000;
    private Build active;
    private static final class Build {
        final SeoRepository.Ticket ticket; final long started=System.nanoTime();
        long renewed=System.nanoTime(),after,urls,chapters; int part; boolean initialized,done,deferred;
        com.java2nb.novel.entity.Book book; String origin; SitemapXmlWriter writer;
        final List<String> files=new ArrayList<>();
        Build(SeoRepository.Ticket ticket){this.ticket=ticket;}
    }
    private long bootstrapCursor;
    @Autowired private SeoCoreSnapshotBuilder coreBuilder;
    private long lastCoreBuild;
    private boolean reconciling;
    private long reconciliationCursor;
    private int reconciliationChecked,reconciliationChanged;
    @Scheduled(cron="${seo.reconciliation-cron:0 0 3 * * *}",zone="${seo.reconciliation-zone:Asia/Shanghai}")
    public synchronized void reconcileNightly(){
        if(properties.isEnabled()&&properties.isSitemapWorkerEnabled())startReconciliation();
    }
    private void startReconciliation(){reconciling=true;reconciliationCursor=0;reconciliationChecked=0;reconciliationChanged=0;}
    private void reconcileNextBatch(){
        if(!reconciling)return;
        var ids=repository.reconciliationIds(reconciliationCursor,100);
        for(long id:ids){if(repository.reconcileBook(id))reconciliationChanged++;reconciliationChecked++;reconciliationCursor=id;}
        if(ids.size()<100){reconciling=false;log.info("SEO state reconciliation checked {} books and queued {} changed books",reconciliationChecked,reconciliationChanged);}
    }
    @Scheduled(fixedDelayString="${seo.worker-delay-ms:1000}",initialDelayString="${seo.worker-initial-delay-ms:15000}")
    public synchronized void tick() {
        if(!properties.isEnabled()||!properties.isSitemapWorkerEnabled()||seo.publicSiteUrl().isEmpty()||ioGate.paused())return;
        try {
            if(!initialized){
                SeoRepository.BootstrapBatch batch;
                try(var permit=ioGate.acquire(false)){permit.check();batch=repository.bootstrapMissingStates(bootstrapCursor,50);}
                bootstrapCursor=batch.nextCursor();
                log.info("SEO bootstrap batch committed: cursor={}, scanned={}, added={}, complete={}",bootstrapCursor,batch.scanned(),batch.added(),batch.complete());
                if(!batch.complete())return;
                initialized=true;startReconciliation();
            }
            // At most 100 book summaries per tick; each application has its own short transaction.
            if(active==null)try(var permit=ioGate.acquire(false)){permit.check();reconcileNextBatch();}
            long until=System.nanoTime()+5_000_000_000L;
            if(budgeted){
                if(active==null){var claim=repository.claim(properties.getSitemapQuietPeriodSeconds());if(claim.isPresent())active=new Build(claim.get());}
                if(active!=null){step(active);if(active.done)active=null;}
            }else for(int i=0;i<10&&System.nanoTime()<until;i++){var claim=repository.claim(properties.getSitemapQuietPeriodSeconds());if(claim.isEmpty())break;build(claim.get());}
            if(coreBuilder!=null&&seo.siteIndexEnabled()){
                var state=repository.coreState();var settings=repository.settings();
                boolean absent=state.get("indexFile")==null||((Number)state.get("builtEpoch")).longValue()!=settings.epoch()||!Objects.equals(state.get("builtOrigin"),seo.publicSiteUrl())||!Objects.equals(state.get("visibilityRevision"),state.get("builtVisibilityRevision"));
                boolean dirty=!Objects.equals(state.get("revision"),state.get("builtRevision"));
                if(absent||(dirty&&System.currentTimeMillis()-lastCoreBuild>=Math.max(10000,coreMinBuildIntervalMs))){try(var permit=ioGate.acquire(false)){permit.check();coreBuilder.build();}lastCoreBuild=System.currentTimeMillis();}
            }
        } catch(DataAccessException e){log.warn("SEO worker waiting for its database migration or database availability");}
        catch(Exception e){log.warn("SEO core snapshot build failed; the previous eligible snapshot is retained: {}",e.getClass().getSimpleName());}
    }
    /** Direct administrative/test entry point; the scheduled worker yields after every page. */
    public synchronized void build(SeoRepository.Ticket ticket) {
        Build work=new Build(ticket);
        while(!work.done){step(work);if(work.deferred){discard(work);return;}}
    }
    private void discard(Build work){
        work.done=true;
        try{if(work.writer!=null)work.writer.close();}catch(Exception ignored){}work.writer=null;
    }
    /** Keep the cursor and unpublished writer between ticks; publish only a complete, still-owned snapshot. */
    private void step(Build work) {
        var ticket=work.ticket;work.deferred=false;
        try(var permit=ioGate.acquire(false);
            var guard=SeoSnapshotFiles.lock(Path.of(properties.getSitemapDirectory()).toAbsolutePath().normalize())) {
            permit.check();
            if(!work.initialized){
                var settings=repository.settings();work.book=repository.book(ticket.bookId());work.origin=seo.publicSiteUrl();
                if(!(seo.isIndexable(work.book)&&settings.chaptersIndexEnabled()&&settings.chapterSitemapEnabled())){
                    long chapters=repository.chapterCount(ticket.bookId());repository.complete(ticket,"",work.origin,0,true,chapters,elapsed(work.started));discard(work);return;
                }
                work.initialized=true;
            }
            if(!current(ticket)||!Objects.equals(work.origin,seo.publicSiteUrl())){discard(work);return;}
            if(System.nanoTime()-work.renewed>=60_000_000_000L){
                if(!repository.renewBuild(ticket)){discard(work);return;}work.renewed=System.nanoTime();
            }
            List<SeoRepository.ChapterRow> page=repository.chapterPage(ticket.bookId(),work.after,CHAPTER_BATCH_SIZE);
            if(!current(ticket)){discard(work);return;}
            if(page.isEmpty()){
                if(work.writer!=null){work.writer.finish();work.writer.close();work.writer=null;}
                boolean published=repository.complete(ticket,String.join("\n",work.files),work.origin,work.urls,false,work.chapters,elapsed(work.started));
                log.info("SEO chapter snapshot: book={}, revision={}, published={}, chapters={}, urls={}, policy=directory_metadata",ticket.bookId(),ticket.revision(),published,work.chapters,work.urls);
                work.done=true;return;
            }
            // Sitemap eligibility uses directory metadata; never fetch bodies or readiness state.
            permit.check();
            int perPart=Math.max(1,Math.min(50000,properties.getSitemapUrlsPerPart()));
            long maxBytes=Math.max(1024,Math.min(49L*1024*1024,properties.getSitemapMaxBytes()));
            Path root=Path.of(properties.getSitemapDirectory()).toAbsolutePath().normalize();
            for(var row:page){
                BookIndex c=row.chapter();if(c.getId()<=work.after)throw new IllegalStateException("Sitemap cursor did not advance");
                work.after=c.getId();work.chapters++;if(!PublicBookPolicy.publicChapter(work.book,c))continue;
                String url=work.origin+"/book/"+ticket.bookId()+"/"+c.getId()+".html";
                String entry=SitemapXmlWriter.entry(url,null);
                if(work.writer!=null&&(work.writer.count()>=perPart||!work.writer.fits(entry))){work.writer.finish();work.writer.close();work.writer=null;}
                if(work.writer==null){String name="chapters-"+ticket.bookId()+"-r"+ticket.revision()+"-e"+ticket.epoch()+"-p"+(++work.part)+"-"+ticket.leaseToken()+".xml.gz";work.writer=new SitemapXmlWriter(root,name,maxBytes);work.files.add(name);}
                work.writer.add(url,null);work.urls++;
            }
            if(!current(ticket))discard(work);
        }catch(com.java2nb.novel.core.io.BackgroundIoGate.BusyException busy){
            work.deferred=true; // Keep the unpublished cursor; admission pressure is not a failed build.
        }catch(Exception failure){
            discard(work);
            try{repository.failed(ticket,failure.getClass().getSimpleName()+": sitemap build failed",elapsed(work.started));}catch(Exception reportFailure){log.warn("SEO failure awaits ticket lease expiry for book {}",ticket.bookId());}
            log.warn("SEO snapshot failed for book {}: {}",ticket.bookId(),failure.getClass().getSimpleName());
        }
    }
    @jakarta.annotation.PreDestroy public synchronized void stop(){
        if(active!=null){discard(active);try{repository.failed(active.ticket,"Worker stopped; rebuild from durable ticket",elapsed(active.started));}catch(Exception ignored){}active=null;}
    }
    private boolean current(SeoRepository.Ticket ticket){return repository.settings().epoch()==ticket.epoch()&&((Number)repository.state(ticket.bookId()).get("revision")).longValue()==ticket.revision();}
    private static long elapsed(long started){return Math.max(0,(System.nanoTime()-started)/1_000_000);}
}
