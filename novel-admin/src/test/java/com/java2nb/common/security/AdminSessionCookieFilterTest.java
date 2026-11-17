package com.java2nb.common.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminSessionCookieFilterTest {
    @Test void protectsOnlyExactSessionCookieAndPreservesOtherHeadersAndAttributes() throws Exception {
        var response = mock(HttpServletResponse.class);
        new AdminSessionCookieFilter(true).doFilter(mock(ServletRequest.class), response, (request, wrapped) -> {
            var http = (HttpServletResponse) wrapped;
            http.addHeader("set-cookie", "JSESSIONID=abc; Path=/admin; SameSite=Lax; Max-Age=0");
            http.setHeader("Set-Cookie", "JSESSIONID=; Domain=admin.example; secure; httponly; Max-Age=0");
            http.addHeader("Set-Cookie", "JSESSIONID_OTHER=untouched; Path=/");
            http.addHeader("Set-Cookie", "jsessionid=untouched");
            http.addHeader("X-Probe", "JSESSIONID=untouched");
            http.setHeader("Set-Cookie", null);
        });
        verify(response).addHeader("set-cookie", "JSESSIONID=abc; Path=/admin; SameSite=Lax; Max-Age=0; Secure; HttpOnly");
        verify(response).setHeader("Set-Cookie", "JSESSIONID=; Domain=admin.example; secure; httponly; Max-Age=0");
        verify(response).addHeader("Set-Cookie", "JSESSIONID_OTHER=untouched; Path=/");
        verify(response).addHeader("Set-Cookie", "jsessionid=untouched");
        verify(response).addHeader("X-Probe", "JSESSIONID=untouched");
        verify(response).setHeader("Set-Cookie", null);
        verifyNoMoreInteractions(response);
    }

    @Test void servletCookiesAreProtectedWithoutMutatingCallerCookie() throws Exception {
        var response = mock(HttpServletResponse.class);
        var session = new Cookie("JSESSIONID", "abc");
        session.setPath("/admin"); session.setMaxAge(0); session.setAttribute("SameSite", "Lax");
        var other = new Cookie("preference", "untouched");
        new AdminSessionCookieFilter(true).doFilter(mock(ServletRequest.class), response, (request, wrapped) -> {
            ((HttpServletResponse) wrapped).addCookie(session);
            ((HttpServletResponse) wrapped).addCookie(other);
        });
        var cookies = ArgumentCaptor.forClass(Cookie.class);
        verify(response, times(2)).addCookie(cookies.capture());
        var protectedSession = cookies.getAllValues().getFirst();
        assertTrue(protectedSession.getSecure()); assertTrue(protectedSession.isHttpOnly());
        assertEquals("/admin", protectedSession.getPath()); assertEquals(0, protectedSession.getMaxAge());
        assertEquals("Lax", protectedSession.getAttribute("SameSite"));
        assertFalse(session.getSecure()); assertFalse(session.isHttpOnly());
        assertSame(other, cookies.getAllValues().get(1));
    }

    @Test void explicitFalseLeavesHttpTestPolicyUnchanged() throws Exception {
        var response = mock(HttpServletResponse.class);
        new AdminSessionCookieFilter(false).doFilter(mock(ServletRequest.class), response, (request, wrapped) -> {
            assertSame(response, wrapped);
            ((HttpServletResponse) wrapped).addHeader("Set-Cookie", "JSESSIONID=abc; Path=/; HttpOnly");
        });
        verify(response).addHeader("Set-Cookie", "JSESSIONID=abc; Path=/; HttpOnly");
    }
}
