package com.java2nb.common.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Enforces the configured session cookie policy even when TLS terminates before Shiro. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminSessionCookieFilter implements Filter {
    private static final String SESSION_COOKIE = "JSESSIONID";
    private final boolean secureCookie;

    public AdminSessionCookieFilter(@Value("${admin.security.secure-cookie:true}") boolean secureCookie) {
        this.secureCookie = secureCookie;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!secureCookie || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(request, new HttpServletResponseWrapper(httpResponse) {
            @Override
            public void addHeader(String name, String value) {
                super.addHeader(name, sessionCookieHeader(name, value));
            }

            @Override
            public void setHeader(String name, String value) {
                super.setHeader(name, sessionCookieHeader(name, value));
            }

            @Override
            public void addCookie(Cookie cookie) {
                if (cookie != null && SESSION_COOKIE.equals(cookie.getName())) {
                    Cookie protectedCookie = (Cookie) cookie.clone();
                    protectedCookie.setSecure(true);
                    protectedCookie.setHttpOnly(true);
                    super.addCookie(protectedCookie);
                } else {
                    super.addCookie(cookie);
                }
            }
        });
    }

    private static String sessionCookieHeader(String name, String value) {
        if (!"Set-Cookie".equalsIgnoreCase(name) || value == null) {
            return value;
        }
        int separator = value.indexOf('=');
        if (separator < 0 || !SESSION_COOKIE.equals(value.substring(0, separator).trim())) {
            return value;
        }
        boolean secure = false;
        boolean httpOnly = false;
        String[] attributes = value.split(";");
        for (int i = 1; i < attributes.length; i++) {
            String attribute = attributes[i].trim();
            secure |= "Secure".equalsIgnoreCase(attribute);
            httpOnly |= "HttpOnly".equalsIgnoreCase(attribute);
        }
        return value + (secure ? "" : "; Secure") + (httpOnly ? "" : "; HttpOnly");
    }
}
