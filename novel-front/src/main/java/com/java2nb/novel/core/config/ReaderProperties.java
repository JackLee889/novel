package com.java2nb.novel.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "reader")
public class ReaderProperties {
    private boolean enabled = true;
    private Tts tts = new Tts();
    @Data public static class Tts {
        private boolean enabled = true;
        private String resourceDirectory = "./reader-resources";
        private String manifestUrl = "/reader/tts-manifest.json";
    }
}
