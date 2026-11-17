package com.java2nb.novel.controller;

import com.java2nb.novel.dto.ChapterFeedbackRequest;
import com.java2nb.novel.service.ChapterFeedbackService;
import io.github.xxyopen.model.resp.RestResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/book/reader")
public class ChapterFeedbackController extends BaseController {
    private final ChapterFeedbackService feedback;

    @PostMapping("/feedback")
    public RestResult<?> submit(@Valid @ModelAttribute ChapterFeedbackRequest report,
        HttpServletRequest request, HttpServletResponse response) {
        ReaderController.noStore(response);
        var user = getUserDetails(request);
        feedback.submit(user == null ? null : user.getId(), report);
        return RestResult.ok();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<RestResult<Void>> failure(ResponseStatusException error, HttpServletResponse response) {
        ReaderController.noStore(response);
        var result = RestResult.ok();
        result.setCode(error.getStatusCode().value() == 401 ? 1001 : error.getStatusCode().value());
        result.setMsg(error.getReason());
        return ResponseEntity.status(error.getStatusCode()).body(result);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<RestResult<Void>> invalid(BindException error, HttpServletResponse response) {
        ReaderController.noStore(response);
        var result = RestResult.ok();
        result.setCode(400);
        result.setMsg("请选择有效的问题类型和章节，补充说明最多 200 字");
        return ResponseEntity.badRequest().body(result);
    }
}
