package com.java2nb.novel.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 页面请求配置；关闭指纹模拟可退回原 HTTP 客户端。 */
@Data
@Component
@ConfigurationProperties(prefix = "crawl.http")
public class CrawlHttpProperties {
    private boolean impersonatorEnabled = true;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 30000;
    private int callTimeoutMs = 45000;
    private int challengeCooldownSeconds = 300;
    /** 同一源站的所有分类线程共享请求间隔；其他书源沿用原采集间隔。 */
    private Map<String, Integer> requestIntervalMsByHost =
        new LinkedHashMap<>(Map.of("www.piaotia.com", 1500, "hetushu.com", 2000, "uukanshu.cc", 2000));
}
