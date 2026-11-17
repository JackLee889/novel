package com.java2nb.novel.core.config;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.file.Path;
import java.time.Duration;
/** Only the dedicated resource root is exposed. No global isolation headers. */
@Configuration
@RequiredArgsConstructor
public class ReaderResourceConfig implements WebMvcConfigurer {
    private final ReaderProperties properties;
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(properties.getTts().getResourceDirectory()).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) location += "/";
        registry.addResourceHandler("/reader/tts/**").addResourceLocations(location)
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
    @Override public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.mediaType("wasm", new org.springframework.http.MediaType("application", "wasm"));
    }
}
