package com.java2nb.novel.seo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import javax.xml.stream.*;
import java.io.*;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Reference-based collection. Unknown files and all last-successful DB manifests are retained. */
@Service
@Slf4j
public class SeoSnapshotRetention {
    public static final String STORE_MARKER=".seo-retention-store";
    private static final String META=".seo-retention";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final SeoRepository repository;private final SeoProperties properties;private final Clock clock;
    @Autowired public SeoSnapshotRetention(SeoRepository repository,SeoProperties properties){this(repository,properties,Clock.systemUTC());}
    SeoSnapshotRetention(SeoRepository repository,SeoProperties properties,Clock clock){this.repository=repository;this.properties=properties;this.clock=clock;}
    public record Entry(String name,long size,String modifiedAt,String fileKey){}
    public record Plan(int version,String storeId,String directory,long plannedAt,long minimumAgeSeconds,
                       List<Entry> candidates,List<String> protectedFiles,List<String> missingReferences,String protectionHash){}
    public record Result(int deletedCount,long deletedBytes,List<String> deleted,List<String> skipped,
                         int protectedCount,List<String> missingReferences){}
    public record Pending(long firstUnreferencedAt,Entry identity){}
    public record Ledger(int version,String storeId,Map<String,Pending> pending){}
    public record Audit(int version,String storeId,Plan plan,Result result,String error){}
    private record Protection(Set<String> keep,Set<String> activeTokens,boolean coreActive,List<String> missing){}

    @Scheduled(fixedDelayString="${seo.snapshot-retention-delay-ms:600000}",initialDelayString="${seo.snapshot-retention-initial-delay-ms:60000}")
    public void scheduled(){
        if(!properties.isEnabled()||!properties.isSitemapWorkerEnabled()||!properties.isSnapshotRetentionEnabled()||properties.getSnapshotRetentionStorageId().isBlank())return;
        try{Result result=collect();if(result!=null&&result.deletedCount()>0)log.info("SEO snapshot retention removed {} obsolete files ({} bytes); {} referenced files retained",result.deletedCount(),result.deletedBytes(),result.protectedCount());}
        catch(Exception e){log.warn("SEO snapshot retention stopped safely: {}",e.getClass().getSimpleName());}
    }

    /** First observation never deletes; continuous unreference and unchanged identity are required. */
    public Result collect()throws Exception {
        Path root=validatedRoot();
        // Queue behind the current publisher; continuous crawling must not starve collection.
        try(var guard=SeoSnapshotFiles.lock(root)){
            Protection protection=protection(root);Path meta=metadata(root);Path ledgerFile=meta.resolve("pending.json");
            Ledger before=readLedger(ledgerFile);long now=clock.millis();long grace=Math.max(300,Math.min(7*86400,properties.getSnapshotRetentionSeconds()));
            Map<String,Pending> pending=new TreeMap<>();List<Entry> eligible=new ArrayList<>();
            for(Entry entry:candidates(root,protection,Instant.ofEpochMilli(now))){
                Pending old=before.pending().get(entry.name());
                Pending value=old!=null&&old.identity().equals(entry)?old:new Pending(now,entry);pending.put(entry.name(),value);
                if(now>=value.firstUnreferencedAt()&&now-value.firstUnreferencedAt()>=grace*1000&&!Instant.parse(entry.modifiedAt()).isAfter(clock.instant().minusSeconds(grace)))eligible.add(entry);
            }
            Ledger after=new Ledger(1,storeId(),pending);if(!after.equals(before))atomic(ledgerFile,JSON.writeValueAsString(after));
            Result result=applyLocked(root,plan(root,protection,eligible,grace),protection);
            if(!result.deleted().isEmpty()){result.deleted().forEach(pending::remove);atomic(ledgerFile,JSON.writeValueAsString(new Ledger(1,storeId(),pending)));}
            return result;
        }
    }

    /** Explicit maintenance plan: old, currently unreferenced files only; no deletion. */
    public Plan plan(Duration minimumAge)throws Exception {
        if(minimumAge.getSeconds()<300)throw new IllegalArgumentException("Maintenance minimum age must be at least five minutes");
        Path root=validatedRoot();
        try(var guard=SeoSnapshotFiles.lock(root)){
            Protection protection=protection(root);
            return plan(root,protection,candidates(root,protection,clock.instant().minus(minimumAge)),minimumAge.getSeconds());
        }
    }
    public Result apply(Plan approved)throws Exception {
        Path root=validatedRoot();validatePlan(approved,root);
        try(var guard=SeoSnapshotFiles.lock(root)){return applyLocked(root,approved,protection(root));}
    }
    public static void savePlan(Path target,Plan plan)throws IOException{atomic(target,JSON.writeValueAsString(plan));}
    public static Plan readPlan(Path file)throws IOException {
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid manifest file");
        return JSON.readValue(file.toFile(),Plan.class);
    }
    public static void saveResult(Path target,Result result)throws IOException{atomic(target,JSON.writeValueAsString(result));}

    private Path validatedRoot()throws IOException {
        if(!storeId().matches(UUID))throw new IOException("A verified storage identifier is required");
        Path root=SeoSnapshotFiles.root(Path.of(properties.getSitemapDirectory()).toAbsolutePath().normalize());Path marker=root.resolve(STORE_MARKER);
        if(Files.isSymbolicLink(marker)||!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)>100||!Files.readString(marker).strip().equals(storeId()))throw new IOException("Snapshot storage marker mismatch");
        return root;
    }
    private String storeId(){return properties.getSnapshotRetentionStorageId();}
    private Path metadata(Path root)throws IOException {
        Path meta=root.resolve(META);if(Files.isSymbolicLink(meta))throw new IOException("Retention metadata must not be a symlink");Files.createDirectories(meta);return meta;
    }
    private Ledger readLedger(Path file)throws IOException {
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new Ledger(1,storeId(),Map.of());
        if(Files.isSymbolicLink(file)||Files.size(file)>32L*1024*1024)throw new IOException("Invalid retention ledger");
        Ledger ledger=JSON.readValue(file.toFile(),Ledger.class);
        if(ledger.version()!=1||!storeId().equals(ledger.storeId())||ledger.pending()==null)throw new IOException("Retention ledger identity mismatch");
        for(var entry:ledger.pending().entrySet())if(!known(entry.getKey())||entry.getValue()==null||entry.getValue().identity()==null||entry.getValue().firstUnreferencedAt()<0||!entry.getKey().equals(entry.getValue().identity().name()))throw new IOException("Invalid ledger entry");
        return ledger;
    }
    private Protection protection(Path root)throws Exception {
        Set<String> keep=new HashSet<>(),tokens=new HashSet<>();Map<String,Object> core=repository.coreState();
        if(!(core.get("indexFile") instanceof String index)||index.isBlank())throw new IOException("No published sitemap index manifest");
        refs(keep,index);if(core.get("coreFiles") instanceof String files)refs(keep,files);
        Set<String> coreRequired=new HashSet<>(keep);
        long cursor=0;
        while(true){var page=repository.snapshotFilePage(cursor,1000);if(page.isEmpty())break;
            for(var row:page){if(row.bookId()<=cursor)throw new IOException("Snapshot cursor did not advance");cursor=row.bookId();refs(keep,row.files());String token=row.activeLeaseToken();if(token!=null){if(!token.matches(UUID))throw new IOException("Invalid active lease token");tokens.add(token);}}
            if(page.size()<1000)break;
        }
        Set<String> graph=new HashSet<>();Deque<String> indexes=new ArrayDeque<>();indexes.add(index);
        while(!indexes.isEmpty()){
            String file=indexes.removeFirst();if(!graph.add(file))continue;
            for(String child:indexChildren(root.resolve(file))){if(keep.size()>1000000)throw new IOException("Snapshot reference limit exceeded");keep.add(child);coreRequired.add(child);if(child.startsWith("index-"))indexes.add(child);}
        }
        Set<String> present=new HashSet<>();try(var stream=Files.newDirectoryStream(root)){for(Path file:stream)present.add(file.getFileName().toString());}
        for(String file:coreRequired)if(!present.contains(file))throw new IOException("Published sitemap graph has a missing file: "+file);
        List<String> missing=keep.stream().filter(name->!present.contains(name)).sorted().toList();
        boolean active="running".equals(core.get("status"))&&instant(core.get("leaseUntil")).isAfter(clock.instant());
        return new Protection(keep,tokens,active,missing);
    }
    private static Instant instant(Object value){return value instanceof Date date?date.toInstant():value instanceof Instant time?time:Instant.EPOCH;}
    private static void refs(Set<String> keep,String files)throws IOException {
        if(files==null||files.isBlank())return;for(String name:files.split("\\R")){if(name.isBlank())continue;if(!safe(name))throw new IOException("Invalid snapshot manifest filename");keep.add(name);}
    }
    private static Set<String> indexChildren(Path file)throws Exception {
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>49L*1024*1024)throw new IOException("Invalid sitemap index file");
        XMLInputFactory factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        Set<String> names=new HashSet<>();
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            XMLStreamReader reader=factory.createXMLStreamReader(input,StandardCharsets.UTF_8.name());
            try{while(reader.hasNext()){int event=reader.next();if(event==XMLStreamConstants.DTD)throw new IOException("DTD is forbidden in sitemap manifests");
                if(event==XMLStreamConstants.START_ELEMENT&&reader.getLocalName().equals("loc")){
                    URI uri=URI.create(reader.getElementText());String path=uri.getPath();String name;
                    if(path!=null&&path.startsWith("/sitemap-"))name=path.substring(9);else if(path!=null&&path.startsWith("/sitemaps/"))name=path.substring(10);else throw new IOException("Unexpected sitemap reference URL");
                    if(!safe(name)||(!name.startsWith("core-")&&!name.startsWith("chapters-")&&!name.startsWith("index-")))throw new IOException("Unsafe sitemap graph filename");
                    names.add(name);if(names.size()>50000)throw new IOException("Sitemap reference limit exceeded");
                }
            }}finally{reader.close();}
        }
        return names;
    }
    private List<Entry> candidates(Path root,Protection protection,Instant cutoff)throws IOException {
        List<Entry> values=new ArrayList<>();
        try(var stream=Files.newDirectoryStream(root)){for(Path path:stream){String name=path.getFileName().toString();
            if(!known(name)||protection.keep().contains(name))continue;
            if(protection.coreActive()&&(name.startsWith("core-")||name.startsWith("index-")||name.startsWith(".")))continue;
            if(name.startsWith(".")&&!protection.activeTokens().isEmpty())continue;
            if(protection.activeTokens().stream().anyMatch(token->name.endsWith("-"+token+".xml.gz")))continue;
            Entry value=identity(path);if(value!=null&&!Instant.parse(value.modifiedAt()).isAfter(cutoff))values.add(value);
        }}
        values.sort(Comparator.comparing(Entry::name));return values;
    }
    private static Entry identity(Path path)throws IOException {
        BasicFileAttributes attr;
        try{attr=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException missing){return null;}
        if(!attr.isRegularFile()||attr.isSymbolicLink()||attr.fileKey()==null)return null;
        try{if(((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)return null;}catch(UnsupportedOperationException|IllegalArgumentException ignored){}
        return new Entry(path.getFileName().toString(),attr.size(),attr.lastModifiedTime().toInstant().toString(),attr.fileKey().toString());
    }
    private Plan plan(Path root,Protection protection,List<Entry> values,long minimumAge)throws Exception {
        List<String> protectedFiles=protection.keep().stream().sorted().toList();
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",protectedFiles).getBytes(StandardCharsets.UTF_8)));
        return new Plan(1,storeId(),root.toString(),clock.millis(),minimumAge,List.copyOf(values),protectedFiles,protection.missing(),hash);
    }
    private void validatePlan(Plan plan,Path root)throws IOException {
        if(plan==null||plan.version()!=1||!storeId().equals(plan.storeId())||!root.toString().equals(plan.directory())||plan.minimumAgeSeconds()<300||plan.plannedAt()>clock.millis()+300000||plan.plannedAt()<clock.millis()-86400000||plan.candidates()==null||plan.protectedFiles()==null)throw new IOException("Invalid or expired cleanup manifest");
        Set<String> seen=new HashSet<>(),protectedFiles=new HashSet<>(plan.protectedFiles());
        for(Entry entry:plan.candidates())if(entry==null||!known(entry.name())||!seen.add(entry.name())||protectedFiles.contains(entry.name())||entry.size()<0||entry.fileKey()==null||entry.fileKey().isBlank())throw new IOException("Unsafe cleanup manifest entry");
        for(Entry entry:plan.candidates())try{Instant.parse(entry.modifiedAt());}catch(RuntimeException e){throw new IOException("Invalid manifest time",e);}
    }
    private Result applyLocked(Path root,Plan plan,Protection protection)throws Exception {
        validatePlan(plan,root);List<String> deleted=new ArrayList<>(),skipped=new ArrayList<>();long bytes=0;
        if(plan.candidates().isEmpty())return new Result(0,0,List.of(),List.of(),protection.keep().size(),protection.missing());
        Path audit=metadata(root).resolve("cleanup-"+clock.millis()+"-"+java.util.UUID.randomUUID()+".json");
        atomic(audit,JSON.writeValueAsString(new Audit(1,storeId(),plan,null,null)));
        String error=null;
        try {
            for(Entry expected:plan.candidates()){
                String name=expected.name();Path file=root.resolve(name);
                if(protection.keep().contains(name)||protection.activeTokens().stream().anyMatch(token->name.endsWith("-"+token+".xml.gz"))||(protection.coreActive()&&(name.startsWith("core-")||name.startsWith("index-")||name.startsWith(".")))||(name.startsWith(".")&&!protection.activeTokens().isEmpty())){skipped.add(name+":referenced-or-leased");continue;}
                Entry actual=identity(file);
                if(!expected.equals(actual)||Instant.parse(expected.modifiedAt()).isAfter(clock.instant().minusSeconds(plan.minimumAgeSeconds()))){skipped.add(name+":identity-or-age-changed");continue;}
                Files.delete(file);deleted.add(name);bytes+=expected.size();
            }
        }catch(Exception failure){error=failure.getClass().getSimpleName();throw failure;}
        finally{atomic(audit,JSON.writeValueAsString(new Audit(1,storeId(),plan,new Result(deleted.size(),bytes,List.copyOf(deleted),List.copyOf(skipped),protection.keep().size(),protection.missing()),error)));}
        trimAudits(metadata(root));
        return new Result(deleted.size(),bytes,List.copyOf(deleted),List.copyOf(skipped),protection.keep().size(),protection.missing());
    }
    private void trimAudits(Path meta)throws IOException {
        List<Path> audits=new ArrayList<>();try(var stream=Files.newDirectoryStream(meta)){for(Path path:stream)if(path.getFileName().toString().matches("cleanup-[0-9]+-"+UUID+"\\.json")&&!Files.isSymbolicLink(path)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))audits.add(path);}
        audits.sort(Comparator.comparing(path->path.getFileName().toString()));
        for(int i=0;i<audits.size()-32;i++){Path path=audits.get(i);Audit record;try{record=JSON.readValue(path.toFile(),Audit.class);}catch(RuntimeException unreadable){continue;}if(record.version()==1&&storeId().equals(record.storeId()))Files.delete(path);}
    }
    private static boolean safe(String name){return name!=null&&name.matches("[a-z0-9-]+\\.xml(?:\\.gz)?");}
    private static boolean known(String name){
        return name!=null&&(name.matches("index-(?:"+UUID+"|[0-9a-f]{64})\\.xml")||name.matches("core-[0-9a-f]{64}-e[0-9]{1,19}-v[0-9]{1,19}-p[0-9]{1,8}\\.xml\\.gz")||name.matches("core-staged-"+UUID+"-p[0-9]{1,8}\\.xml\\.gz")||name.matches("chapters-[0-9]{1,19}-r[0-9]{1,19}-e[0-9]{1,19}-p[0-9]{1,8}-"+UUID+"\\.xml\\.gz")||name.matches("\\.(?:index|sitemap)-[0-9]+\\.tmp"));
    }
    private static void atomic(Path target,String value)throws IOException {
        Path parent=target.toAbsolutePath().getParent();Files.createDirectories(parent);
        if(Files.isSymbolicLink(target))throw new IOException("Metadata target must not be a symlink");
        Path temporary=Files.createTempFile(parent,".retention-",".tmp");
        try{Files.writeString(temporary,value,StandardCharsets.UTF_8);try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(temporary);}
    }
}
