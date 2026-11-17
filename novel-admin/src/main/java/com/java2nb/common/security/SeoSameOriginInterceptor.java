package com.java2nb.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Additional browser-origin checks for SEO writes; Shiro's session CSRF token is still required. */
public final class SeoSameOriginInterceptor implements HandlerInterceptor {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (SAFE.contains(request.getMethod())) return true;
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");
        boolean allowed = !"cross-site".equalsIgnoreCase(fetchSite)
            && (origin != null ? matches(request, origin, false) : matches(request, referer, true));
        if (allowed) return true;
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":403,\"msg\":\"SEO写操作需要同源请求，请从后台页面重试\"}");
        return false;
    }

    static boolean matches(HttpServletRequest request, String value, boolean allowPath) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || uri.getHost() == null || uri.getFragment() != null) return false;
            if (!allowPath && (uri.getRawQuery() != null || (uri.getPath() != null && !uri.getPath().isEmpty()))) return false;
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return false;
            int port = uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
            return scheme.equalsIgnoreCase(request.getScheme())
                && uri.getHost().equalsIgnoreCase(request.getServerName()) && port == request.getServerPort();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
