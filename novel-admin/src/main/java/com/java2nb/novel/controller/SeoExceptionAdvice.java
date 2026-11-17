package com.java2nb.novel.controller;

import com.java2nb.common.utils.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.ConcurrentModificationException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = SeoController.class)
public class SeoExceptionAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R> invalid(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(R.error(400, ex.getMessage())); }
    @ExceptionHandler(ConcurrentModificationException.class)
    public ResponseEntity<R> conflict(ConcurrentModificationException ex) {
        return ResponseEntity.status(409).body(R.error(409, "配置已被修改，请刷新后重新提交"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<R> missing(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(R.error(ex.getStatusCode().value(), ex.getReason()));
    }
    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    public ResponseEntity<R> unavailable(Exception ex) {
        return ResponseEntity.status(503).body(R.error(503, "SEO存储或诊断服务暂不可用，请检查初始化和服务状态"));
    }
}
