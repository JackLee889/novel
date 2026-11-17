package com.java2nb.common.exception;


import com.java2nb.common.utils.R;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.RequestDispatcher;

@RestController
public class MainsiteErrorController implements ErrorController {

    private static final String ERROR_PATH = "/error";

    @RequestMapping(
        value = {ERROR_PATH},
        produces = {"text/html"}
    )
    public ModelAndView errorHtml(HttpServletRequest request, HttpServletResponse response) {
        int code = errorStatus(request, response);
        if (404 == code) {
            return new ModelAndView("error/404");
        } else if (403 == code) {
            return new ModelAndView("error/403");
        } else {
            return new ModelAndView("error/error")
                .addObject("errorCode", code).addObject("errorMessage", errorMessage(code));
        }

    }

    @RequestMapping(value = ERROR_PATH)
    public R handleError(HttpServletRequest request, HttpServletResponse response) {
        int code = errorStatus(request, response);
        return R.error(code, errorMessage(code));
    }

    private int errorStatus(HttpServletRequest request, HttpServletResponse response) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = attribute instanceof Integer status ? status : response.getStatus();
        if (code < 400 || code > 599) { code = 500; }
        response.setStatus(code);
        return code;
    }

    private String errorMessage(int code) {
        return switch (code) {
            case 400 -> "请求无效";
            case 401 -> "登录过期";
            case 403 -> "没有访问权限";
            case 404 -> "未找到资源";
            case 405 -> "请求方法不允许";
            default -> "服务器错误";
        };
    }
}
