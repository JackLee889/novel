package com.java2nb.novel.core.advice;

import io.github.xxyopen.model.resp.RestResult;
import io.github.xxyopen.model.resp.SysResultCode;
import io.github.xxyopen.web.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatusCode;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionException;

/**
 * 统一异常处理器
 *
 * @author xiongxiaoyang
 */
@Slf4j
@RestControllerAdvice
public class CommonExceptionHandler {

    public CommonExceptionHandler() {
    }

    @ExceptionHandler({BindException.class})
    public RestResult<Void> handlerBindException(BindException e) {
        // Binding errors may contain rejected passwords; log only field names and rule codes.
        log.warn("Invalid request fields: {}", e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ":" + error.getCode()).toList());
        return RestResult.fail(SysResultCode.PARAM_ERROR);
    }

    @ExceptionHandler({BusinessException.class})
    public RestResult<Void> handlerBusinessException(BusinessException e) {
        log.error(e.getMessage(), e);
        return RestResult.fail(e.getResultCode());
    }

    @ExceptionHandler(Exception.class)
    public Object handleException(HttpServletRequest request, HttpServletResponse response, Exception e) {
        log.error(e.getMessage(), e);
        Throwable cause=e;
        while((cause instanceof ExecutionException||cause instanceof CompletionException)&&cause.getCause()!=null)cause=cause.getCause();
        HttpStatusCode status=cause instanceof ResponseStatusException error?error.getStatusCode():
            cause instanceof NoResourceFoundException?HttpStatus.NOT_FOUND:
            cause instanceof org.springframework.beans.TypeMismatchException||cause instanceof org.springframework.web.bind.ServletRequestBindingException?HttpStatus.BAD_REQUEST:HttpStatus.INTERNAL_SERVER_ERROR;
        response.setHeader("X-Robots-Tag","noindex,follow");
        response.setHeader("Cache-Control","private, no-store");
        if(status.value()==503)response.setHeader("Retry-After","60");
        if (isJsonRequest(request)) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON).body(RestResult.error());
        } else {
            ModelAndView view = new ModelAndView(status.value()==404?"error/404":status.value()==400?"error/400":status.value()==503?"error/503":"error/500");
            view.setStatus(status);
            return view;
        }
    }

    private boolean isJsonRequest(HttpServletRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod method) {
            if (AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RestController.class)
                    || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), ResponseBody.class)
                    || method.hasMethodAnnotation(ResponseBody.class)) { return true; }
        }
        String acceptHeader = request.getHeader("Accept");
        return acceptHeader != null && acceptHeader.contains(MediaType.APPLICATION_JSON_VALUE);
    }

}
