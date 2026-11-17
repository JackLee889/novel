package com.java2nb.common.config;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;

@EnableAutoConfiguration(excludeName = "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration")
@Configuration
public class SecuityConfig {


}
