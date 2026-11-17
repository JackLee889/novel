package com.java2nb.novel.seo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix="seo")
public class SeoProperties {
    private boolean enabled = false;
    private String siteUrl = "";
    private String sitemapDirectory = "/data/seo";
    private String contentDirectory = "/data/books";
    private boolean sitemapWorkerEnabled = true;
    private long sitemapQuietPeriodSeconds = 60;
    private boolean snapshotRetentionEnabled = true;
    private long snapshotRetentionSeconds = 1800;
    /** Deletion remains disabled until this matches the marker on the new storage. */
    private String snapshotRetentionStorageId = "";
    private int catalogPageSize = 200;
    private int sitemapUrlsPerPart = 20000;
    private long sitemapMaxBytes = 10L * 1024 * 1024;
}
