package com.java2nb.novel.seo;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeoSnapshotRetentionTest {
    @TempDir Path root;
    static final String STORE="e0421bc0-0fa8-49b6-a876-1c3fc87b90dd",TOKEN="11111111-1111-1111-1111-111111111111";
    static final String INDEX="index-22222222-2222-2222-2222-222222222222.xml";
    static final String OLD="index-33333333-3333-3333-3333-333333333333.xml";
    static final String CORE="core-"+"a".repeat(64)+"-e1-v1-p1.xml.gz";
    static final String CHAPTER="chapters-7-r1-e1-p1-"+TOKEN+".xml.gz";
    SeoRepository repository;SeoProperties properties;MutableClock clock;SeoSnapshotRetention retention;
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-17T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
        void advance(long seconds){now=now.plusSeconds(seconds);}
    }
    @BeforeEach void setup()throws Exception {
        repository=mock(SeoRepository.class);properties=new SeoProperties();properties.setSitemapDirectory(root.toString());properties.setSnapshotRetentionStorageId(STORE);clock=new MutableClock();retention=new SeoSnapshotRetention(repository,properties,clock);
        Files.writeString(root.resolve(SeoSnapshotRetention.STORE_MARKER),STORE+"\n");
        old(INDEX,index(CORE));old(CORE,"current core");
        when(repository.coreState()).thenReturn(Map.of("indexFile",INDEX,"coreFiles",CORE,"status","complete"));
        when(repository.snapshotFilePage(anyLong(),anyInt())).thenReturn(List.of());
    }
    Path old(String name,String body)throws Exception {Path file=root.resolve(name);Files.writeString(file,body);Files.setLastModifiedTime(file,FileTime.from(clock.instant().minusSeconds(7200)));return file;}
    String index(String... names){StringBuilder value=new StringBuilder("<?xml version=\"1.0\"?><sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");for(String name:names)value.append("<sitemap><loc>https://xs001.xyz/sitemap-").append(name).append("</loc></sitemap>");return value.append("</sitemapindex>").toString();}
    void stableIdentity()throws IOException{Assumptions.assumeTrue(Files.readAttributes(root.resolve(INDEX),java.nio.file.attribute.BasicFileAttributes.class).fileKey()!=null,"The Windows provider has no inode identity; deletion is verified in the Linux image");}
    @Test void manualPlanProtectsEveryDbManifestAndPublishedIndexGraphAndRoundTrips()throws Exception {
        stableIdentity();
        String hidden="chapters-8-r1-e1-p1-"+TOKEN+".xml.gz",missing="chapters-9-r1-e1-p1-"+TOKEN+".xml.gz";
        old(INDEX,index(CORE,CHAPTER));old(CHAPTER,"previous chapter still in current index");old(hidden,"hidden or pending previous success");old(OLD,"obsolete");old("keep-me.xml","unknown");
        when(repository.snapshotFilePage(0,1000)).thenReturn(List.of(new SeoRepository.SnapshotFiles(8,hidden,null),new SeoRepository.SnapshotFiles(9,missing,null)));
        var plan=retention.plan(Duration.ofHours(1));assertEquals(List.of(OLD),plan.candidates().stream().map(SeoSnapshotRetention.Entry::name).toList());
        assertTrue(plan.protectedFiles().containsAll(List.of(INDEX,CORE,CHAPTER,hidden,missing)));assertEquals(List.of(missing),plan.missingReferences());
        Path manifest=root.resolve("manifest.json");SeoSnapshotRetention.savePlan(manifest,plan);assertEquals(plan,SeoSnapshotRetention.readPlan(manifest));
        var result=retention.apply(SeoSnapshotRetention.readPlan(manifest));assertEquals(List.of(OLD),result.deleted());assertTrue(Files.exists(root.resolve(hidden)));assertTrue(Files.exists(root.resolve(CHAPTER)));assertTrue(Files.exists(root.resolve("keep-me.xml")));
    }
    @Test void automaticCollectionRequiresContinuousUnreferenceAndNeverDeletesOnFirstObservation()throws Exception {
        stableIdentity();
        old(OLD,"obsolete");assertEquals(0,retention.collect().deletedCount());clock.advance(1799);assertEquals(0,retention.collect().deletedCount());clock.advance(1);
        assertEquals(List.of(OLD),retention.collect().deleted());assertTrue(Files.exists(root.resolve(INDEX)));assertTrue(Files.exists(root.resolve(CORE)));
    }
    @Test void renewedReferenceRestartsGraceEvenWhenTheSameFileBecomesUnusedAgain()throws Exception {
        stableIdentity();
        old(CHAPTER,"previous chapter");retention.collect();clock.advance(1800);
        when(repository.snapshotFilePage(0,1000)).thenReturn(List.of(new SeoRepository.SnapshotFiles(7,CHAPTER,null)));assertEquals(0,retention.collect().deletedCount());
        when(repository.snapshotFilePage(0,1000)).thenReturn(List.of());assertEquals(0,retention.collect().deletedCount());clock.advance(1800);assertEquals(List.of(CHAPTER),retention.collect().deleted());
    }
    @Test void fileReusedBetweenScansRestartsGraceByIdentityTimestamp()throws Exception {
        stableIdentity();
        Path file=old(OLD,"reused");retention.collect();clock.advance(1800);Files.setLastModifiedTime(file,FileTime.from(clock.instant()));assertEquals(0,retention.collect().deletedCount());clock.advance(1800);assertEquals(List.of(OLD),retention.collect().deleted());
    }
    @Test void applyRevalidatesNewReferencesAndDoesNotExpandTheApprovedManifest()throws Exception {
        stableIdentity();
        old(CHAPTER,"re-referenced");old(OLD,"obsolete");var plan=retention.plan(Duration.ofHours(1));
        String later="index-44444444-4444-4444-4444-444444444444.xml";old(later,"appeared after plan");when(repository.snapshotFilePage(0,1000)).thenReturn(List.of(new SeoRepository.SnapshotFiles(7,CHAPTER,null)));
        var result=retention.apply(plan);assertEquals(List.of(OLD),result.deleted());assertEquals(1,result.skipped().size());assertTrue(Files.exists(root.resolve(CHAPTER)));assertTrue(Files.exists(root.resolve(later)));
    }
    @Test void changedIdentityIsSkippedAndUnsafeManifestFailsBeforeAnyDeletion()throws Exception {
        Path file=old(OLD,"old");var plan=retention.plan(Duration.ofHours(1));Files.writeString(file,"changed payload");assertEquals(0,retention.apply(plan).deletedCount());
        var entries=new ArrayList<>(plan.candidates());entries.add(new SeoSnapshotRetention.Entry("../outside.xml",1,clock.instant().minusSeconds(7200).toString(),"test"));
        var unsafe=new SeoSnapshotRetention.Plan(1,STORE,plan.directory(),clock.millis(),3600,entries,plan.protectedFiles(),List.of(),plan.protectionHash());assertThrows(IOException.class,()->retention.apply(unsafe));assertTrue(Files.exists(file));
    }
    @Test void markerMismatchOrMissingPublishedGraphAlwaysFailsClosed()throws Exception {
        old(OLD,"obsolete");Files.writeString(root.resolve(SeoSnapshotRetention.STORE_MARKER),"wrong");assertThrows(IOException.class,()->retention.plan(Duration.ofHours(1)));assertTrue(Files.exists(root.resolve(OLD)));
        Files.writeString(root.resolve(SeoSnapshotRetention.STORE_MARKER),STORE);Files.delete(root.resolve(CORE));assertThrows(IOException.class,()->retention.collect());assertTrue(Files.exists(root.resolve(OLD)));
    }
    @Test void xmlExternalEntitiesAndTraversalAreRejectedBeforeDeletion()throws Exception {
        old(OLD,"obsolete");Files.writeString(root.resolve(INDEX),"<!DOCTYPE sitemapindex [<!ENTITY x SYSTEM 'file:///never-read'>]><sitemapindex><sitemap><loc>&x;</loc></sitemap></sitemapindex>");assertThrows(Exception.class,()->retention.collect());assertTrue(Files.exists(root.resolve(OLD)));
        Files.writeString(root.resolve(INDEX),index("../../outside.xml"));assertThrows(Exception.class,()->retention.collect());assertTrue(Files.exists(root.resolve(OLD)));
    }
    @Test void activeBuilderTokensAndCoreLeaseProtectUnpublishedWork()throws Exception {
        old(OLD,"obsolete");old(CHAPTER,"active partial");old(".sitemap-123.tmp","active temp");
        when(repository.coreState()).thenReturn(Map.of("indexFile",INDEX,"coreFiles",CORE,"status","running","leaseUntil",Date.from(clock.instant().plusSeconds(600))));when(repository.snapshotFilePage(0,1000)).thenReturn(List.of(new SeoRepository.SnapshotFiles(7,null,TOKEN)));
        assertTrue(retention.plan(Duration.ofHours(1)).candidates().isEmpty());
    }
    @Test void publisherLockExcludesTheCollector()throws Exception {
        old(OLD,"obsolete");try(var executor=Executors.newSingleThreadExecutor()){
            Future<SeoSnapshotRetention.Result> waiting;
            try(var guard=SeoSnapshotFiles.lock(root)){waiting=executor.submit(()->retention.collect());assertThrows(TimeoutException.class,()->waiting.get(100,TimeUnit.MILLISECONDS));}
            assertEquals(0,waiting.get(5,TimeUnit.SECONDS).deletedCount());
        }assertTrue(Files.exists(root.resolve(OLD)));
    }
    @Test void directoriesAndHardLinkedFilesAreOutsideTheDeletionSet()throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("unix"));
        Files.createDirectory(root.resolve(OLD));Path outside=old("original-data.txt","preserve");Files.createLink(root.resolve(CHAPTER),outside);
        assertTrue(retention.plan(Duration.ofHours(1)).candidates().isEmpty());assertTrue(Files.exists(outside));
    }
}
