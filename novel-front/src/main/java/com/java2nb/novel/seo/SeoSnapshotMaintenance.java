package com.java2nb.novel.seo;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Offline CLI using the actual release classes, existing app credentials and the publisher lock. */
public final class SeoSnapshotMaintenance {
    public static void main(String[] args)throws Exception {
        if(args.length<2||!(args[0].equals("plan")||args[0].equals("apply")))throw new IllegalArgumentException("plan MANIFEST or apply MANIFEST RESULT");
        String url=System.getenv("MYSQL_JDBC_URL");
        if(url==null||!url.matches("jdbc:mysql://10\\.78\\.12\\.1:3306/novel_plus(?:\\?.*)?"))throw new IllegalArgumentException("Maintenance requires the new storage database");
        var source=new DriverManagerDataSource(url,System.getenv("MYSQL_USERNAME"),System.getenv("MYSQL_PASSWORD"));
        var properties=new SeoProperties();properties.setSitemapDirectory("/data/seo");properties.setSnapshotRetentionStorageId(System.getenv().getOrDefault("SEO_SNAPSHOT_RETENTION_STORAGE_ID",""));
        var retention=new SeoSnapshotRetention(new SeoRepository(source),properties);var json=JsonMapper.builder().build();
        if(args[0].equals("plan")){
            var plan=retention.plan(Duration.ofHours(1));SeoSnapshotRetention.savePlan(Path.of(args[1]),plan);
            System.out.println("CLEANUP_PLAN_JSON="+json.writeValueAsString(Map.of("candidates",plan.candidates().size(),"candidateBytes",plan.candidates().stream().mapToLong(SeoSnapshotRetention.Entry::size).sum(),"protected",plan.protectedFiles().size(),"missingReferences",plan.missingReferences().size(),"protectionHash",plan.protectionHash())));
        }else{
            if(args.length!=3)throw new IllegalArgumentException("apply MANIFEST RESULT");
            var result=retention.apply(SeoSnapshotRetention.readPlan(Path.of(args[1])));SeoSnapshotRetention.saveResult(Path.of(args[2]),result);
            System.out.println("CLEANUP_RESULT_JSON="+json.writeValueAsString(Map.of("deleted",result.deletedCount(),"deletedBytes",result.deletedBytes(),"skipped",result.skipped().size(),"protected",result.protectedCount(),"missingReferences",result.missingReferences().size())));
        }
    }
}
