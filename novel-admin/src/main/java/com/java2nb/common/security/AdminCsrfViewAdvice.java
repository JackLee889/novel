package com.java2nb.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class AdminCsrfViewAdvice {
    @ModelAttribute("_csrf")
    public Object csrfToken(HttpServletRequest request) {
        return request.getAttribute("_csrf");
    }
}
