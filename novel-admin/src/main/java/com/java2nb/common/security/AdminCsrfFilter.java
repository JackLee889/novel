package com.java2nb.common.security;

import org.apache.shiro.web.servlet.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/** Synchronizer tokens are bound to the authenticated Shiro HTTP session. */
public class AdminCsrfFilter extends OncePerRequestFilter {
    private static final String SESSION_KEY = AdminCsrfFilter.class.getName() + ".token";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(ServletRequest request, ServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getServletPath();
        if (path.matches("^/(css|js|fonts|img|layuimini|upload|files)/.*") || path.equals("/favicon.ico")) {
            chain.doFilter(request, response); return;
        }
        if (path.equals("/logout") && !req.getMethod().equals("POST")) {
            res.setStatus(405); res.setHeader("Allow", "POST"); return;
        }
        HttpSession session = req.getSession(false);
        String token = session == null ? null : (String) session.getAttribute(SESSION_KEY);
        if (!SAFE_METHODS.contains(req.getMethod())) {
            String supplied = req.getHeader("X-CSRF-TOKEN");
            if (supplied == null) { supplied = req.getParameter("_csrf"); }
            if (token == null || supplied == null || supplied.length() != token.length()
                    || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                res.setStatus(403);
                res.setContentType("application/json;charset=UTF-8");
                res.setHeader("Cache-Control", "no-store");
                res.getWriter().write("{\"code\":403,\"msg\":\"页面校验已失效，请刷新页面后重试\"}");
                return;
            }
        }
        if (token == null) {
            byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            req.getSession().setAttribute(SESSION_KEY, token);
        }
        req.setAttribute("_csrf", Map.of("token", token, "parameterName", "_csrf", "headerName", "X-CSRF-TOKEN"));
        res.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
