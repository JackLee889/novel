package com.java2nb.novel.core.filter;

import com.java2nb.novel.core.config.BusinessFeatureProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/** Reject disabled routes before MVC binding and business side effects. */
public class BusinessFeatureFilter extends OncePerRequestFilter {
    private final BusinessFeatureProperties features;

    public BusinessFeatureFilter(BusinessFeatureProperties features) {
        this.features = features;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
            path = UriUtils.decode(path, StandardCharsets.UTF_8);
        }
        // Servlet routes ignore matrix parameters; capability checks must do the same.
        path = path.replaceAll(";[^/]*", "");
        String feature = disabledFeature(path);
        if (feature == null) {
            chain.doFilter(request, response);
            return;
        }
        String message = feature + "功能暂时关闭";
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/html")) {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<!doctype html><html lang=\"zh-CN\"><head>"
                + "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>功能暂时关闭</title><link rel=\"stylesheet\" href=\"/css/site.css?v=20261006\">"
                + "</head><body><main class=\"container page-main\"><section class=\"error-page\">"
                + "<h1>" + message + "</h1><p>阅读和听书可继续使用。</p>"
                + "<a class=\"button button-primary\" href=\"/\">返回书城</a>"
                + "</section></main></body></html>");
        } else {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"msg\":\"" + message + "\",\"data\":null}");
        }
    }

    private String disabledFeature(String path) {
        if (!features.isPaymentEnabled()
            && (within(path, "/pay") || within(path, "/user/buyBookIndex") || path.equals("/user/pay.html"))) {
            return "支付";
        }
        if (!features.isAiEnabled()
            && (within(path, "/author/ai") || within(path, "/author/queryAiGenPic"))) {
            return "AI";
        }
        if (!features.isSubmissionEnabled()
            && (within(path, "/author") || within(path, "/file/picUpload"))) {
            return "投稿";
        }
        return null;
    }

    private boolean within(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
