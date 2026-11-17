package com.java2nb.novel.seo;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class SeoHttpStatsConfiguration {
    @Bean public SeoHttpStatsFilter seoHttpStatsFilter(SeoHttpStats stats) { return new SeoHttpStatsFilter(stats); }
    @Bean public FilterRegistrationBean<SeoHttpStatsFilter> seoHttpStatsFilterRegistration(SeoHttpStatsFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setName("seoAnonymousHttpStats"); registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
        registration.setAsyncSupported(true); registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10); return registration;
    }
    @Bean public ServletListenerRegistrationBean<SeoHttpStatsFilter> seoHttpStatsCompletionRegistration(SeoHttpStatsFilter filter) {
        return new ServletListenerRegistrationBean<>(filter);
    }
}
