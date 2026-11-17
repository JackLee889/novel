package com.java2nb.novel.seo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Core URL enumeration happens in the worker, never in an HTTP request. */
@Service
@RequiredArgsConstructor
public class SeoCoreSnapshotBuilder {
    private final SeoRepository repository;
    private final SeoService seo;
    private final SeoProperties properties;
    public boolean build()throws Exception {
        if(!seo.siteIndexEnabled())return false;
        Path root=SeoSnapshotFiles.root(Path.of(properties.getSitemapDirectory()).toAbsolutePath().normalize());
        try(var guard=SeoSnapshotFiles.lock(root)){return buildLocked(root);}
    }
    private boolean buildLocked(Path root)throws Exception {
        var ticket=repository.coreTicket();if(ticket==null)return false;long started=System.nanoTime();
        List<String> parts=new ArrayList<>();SitemapXmlWriter writer=null;String staged=null;long urls=0;int part=0;
        try{
            var settings=repository.settings();String origin=seo.publicSiteUrl();
            String generation=UUID.randomUUID().toString();int limit=Math.max(1,Math.min(50000,properties.getSitemapUrlsPerPart()));long max=Math.max(1024,Math.min(49L*1024*1024,properties.getSitemapMaxBytes()));
            for(var entry:entries(origin)){
                if(writer!=null&&(writer.count()>=limit||!writer.fits(SitemapXmlWriter.entry(entry.url(),entry.modified())))){writer.finish();writer.close();parts.add(finalizePart(root,staged,ticket,part));writer=null;}
                if(writer==null){staged="core-staged-"+generation+"-p"+(++part)+".xml.gz";writer=new SitemapXmlWriter(root,staged,max);}
                writer.add(entry.url(),entry.modified());urls++;
            }
            if(writer!=null){writer.finish();writer.close();parts.add(finalizePart(root,staged,ticket,part));writer=null;}
            StringBuilder index=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
            for(String file:parts)map(index,origin+"/sitemap-"+file,null);
            int maps=parts.size();if(settings.chaptersIndexEnabled()&&settings.chapterSitemapEnabled())for(var state:repository.publishedStates(ticket.epoch(),origin)){
                String files=(String)state.get("builtFiles");if(files==null||files.isBlank())continue;
                for(String file:files.split("\n")){map(index,origin+"/sitemap-"+file,instant(state.get("modifiedAt")));if(++maps>50000)throw new IllegalStateException("Sitemap index needs another index tier");}
            }
            index.append("</sitemapindex>\n");byte[] body=index.toString().getBytes(StandardCharsets.UTF_8);if(body.length>max)throw new IllegalStateException("Sitemap index exceeds its byte budget");
            String hash=hex(body);String file="index-"+hash+".xml";
            SeoSnapshotFiles.immutableIndex(root,file,body,hash);
            return repository.completeCore(ticket,origin,generation,file,String.join("\n",parts),hash,urls,elapsed(started));
        }catch(Exception e){repository.failedCore(ticket,e.getClass().getSimpleName()+": core sitemap build failed",elapsed(started));throw e;}
        finally{if(writer!=null)writer.close();}
    }
    private String finalizePart(Path root,String staged,SeoRepository.CoreTicket ticket,int part)throws Exception {
        Path input=root.resolve(staged);String hash;try(var stream=Files.newInputStream(input)){var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[16384];for(int read;(read=stream.read(buffer))!=-1;)digest.update(buffer,0,read);hash=HexFormat.of().formatHex(digest.digest());}
        String file="core-"+hash+"-e"+ticket.epoch()+"-v"+ticket.visibilityRevision()+"-p"+part+".xml.gz";Path target=root.resolve(file);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){SeoSnapshotFiles.reuse(target,hash);Files.delete(input);}else Files.move(input,target,StandardCopyOption.ATOMIC_MOVE);return file;
    }
    private List<Entry> entries(String origin){
        List<Entry> entries=new ArrayList<>();entries.add(new Entry(origin+"/",null));listing(entries,origin+"/book/bookclass.html",repository.publicBookCount(null,null));
        for(var category:repository.publicCategories()){int id=((Number)category.get("id")).intValue();listing(entries,origin+"/book/bookclass.html?c="+id,repository.publicBookCount("cat_id",id));}
        for(int direction=0;direction<2;direction++)listing(entries,origin+"/book/bookclass.html?workDirection="+direction,repository.publicBookCount("work_direction",direction));
        listing(entries,origin+"/book/bookclass.html?bookStatus=1",repository.publicBookCount("book_status",1));if(repository.publicBookCount(null,null)>0){entries.add(new Entry(origin+"/book/book_ranking.html",null));for(int type=1;type<4;type++)entries.add(new Entry(origin+"/book/book_ranking.html?type="+type,null));}
        for(var book:repository.publicBooks()){long id=((Number)book.get("bookId")).longValue();Instant modified=instant(book.get("modifiedAt"));if(modified==null)modified=instant(book.get("lastChapterAt"));entries.add(new Entry(origin+"/book/"+id+".html",modified));
            Object value=book.get("chapterCount");long count=value instanceof Number n?n.longValue():0;long pages=Math.max(1,(count+seo.catalogPageSize()-1)/seo.catalogPageSize());for(long p=1;p<=pages;p++)entries.add(new Entry(origin+"/book/indexList-"+id+".html"+(p==1?"":"?page="+p),modified));}
        return entries;
    }
    private static void listing(List<Entry> entries,String base,long count){if(count<=0)return;for(long p=1;p<=(count+11)/12;p++)entries.add(new Entry(base+(p==1?"":(base.contains("?")?"&":"?")+"page="+p),null));}
    private record Entry(String url,Instant modified){}
    private static Instant instant(Object value){return value instanceof Date d?d.toInstant():value instanceof Instant i?i:null;}
    private static void map(StringBuilder xml,String url,Instant modified){xml.append("<sitemap><loc>").append(SitemapXmlWriter.escape(url)).append("</loc>").append(modified==null?"":"<lastmod>"+modified+"</lastmod>").append("</sitemap>\n");}
    private static String hex(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static long elapsed(long started){return Math.max(0,(System.nanoTime()-started)/1_000_000);}
}
