package com.java2nb.common.config;

import com.java2nb.common.security.SeoSameOriginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SeoAdminWebConfiguration implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SeoSameOriginInterceptor()).addPathPatterns("/novel/seo", "/novel/seo/**");
    }
}
