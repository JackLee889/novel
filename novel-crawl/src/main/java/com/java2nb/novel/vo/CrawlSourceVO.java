package com.java2nb.novel.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.core.crawl.CrawlRuntimeMonitor;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * @author Administrator
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class CrawlSourceVO extends CrawlSource{

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm")
    private Date createTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm")
    private Date updateTime;

    private Long chapterCount;

    private CrawlRuntimeMonitor.Snapshot runtime;
    private com.java2nb.novel.core.crawl.CrawlRefreshStore.Stats queue;

    @Override
    public String toString() {
        return super.toString();
    }
}
