package com.java2nb.novel.core.advice;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Objects;

/**
 * 在对 RestController 返回对象 json 序列化时，将所有 Long 类型转为 String 类型返回，避免前端数据精度丢失的问题
 * 取代 spring.jackson.generator.write-numbers-as-strings=true 配置，避免影响全局的 ObjectMapper
 *
 * @author xiongxiaoyang
 * */
@RestControllerAdvice
public class CustomResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper customObjectMapper;

    public CustomResponseBodyAdvice(JsonMapper mapper) {
        SimpleModule simpleModule = new SimpleModule();
        simpleModule.addSerializer(Long.class, ToStringSerializer.instance);
        simpleModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
        customObjectMapper = mapper.rebuild().addModule(simpleModule).build();
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 返回 true 表示对所有 Controller 的响应都生效
        return org.springframework.http.converter.json.JacksonJsonHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
        // 使用自定义的 ObjectMapper 序列化响应体
        if(Objects.nonNull(body)) {
            return customObjectMapper.valueToTree(body);
        }else{
            return null;
        }
    }

}

