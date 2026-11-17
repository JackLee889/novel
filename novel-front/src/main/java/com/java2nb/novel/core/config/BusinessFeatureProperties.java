package com.java2nb.novel.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Business capabilities stay closed until explicitly enabled in deployment configuration. */
@Data
@Component
@ConfigurationProperties(prefix = "features")
public class BusinessFeatureProperties {
    private boolean paymentEnabled;
    private boolean aiEnabled;
    private boolean submissionEnabled;
}
