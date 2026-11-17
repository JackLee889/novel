package com.java2nb.novel.visitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Public counters can be disabled independently from the reader. Schema changes are explicit. */
@Component
@ConfigurationProperties(prefix = "visitor-stats")
public class VisitorStatsProperties {
    private boolean enabled = true;
    private String siteUrl = "";
    private List<String> allowedOrigins = List.of();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSiteUrl() { return siteUrl; }
    public void setSiteUrl(String siteUrl) { this.siteUrl = siteUrl; }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
