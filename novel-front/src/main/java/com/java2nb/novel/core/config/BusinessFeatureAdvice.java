package com.java2nb.novel.core.config;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
@RequiredArgsConstructor
public class BusinessFeatureAdvice {
    private final BusinessFeatureProperties features;

    @ModelAttribute("businessFeatures")
    public BusinessFeatureProperties businessFeatures() {
        return features;
    }
}
