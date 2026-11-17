package com.java2nb.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AdminCsrfFilterTest {
    private final AdminCsrfFilter filter = new AdminCsrfFilter();

    private MockHttpServletRequest request(String method, String path) {
        var req = new MockHttpServletRequest(method, path);
        req.setServletPath(path);
        return req;
    }

    private String issue(MockHttpSession session) throws Exception {
        var req = request("GET", "/login"); req.setSession(session);
        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});
        return ((Map<String, String>) req.getAttribute("_csrf")).get("token");
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void missingTokensAreRejectedBeforeHandlers(String method) throws Exception {
        var res = new MockHttpServletResponse(); var called = new AtomicBoolean();
        filter.doFilter(request(method, "/novel/book/update"), res, (r, s) -> called.set(true));
        assertEquals(403, res.getStatus()); assertFalse(called.get());
    }

    @Test void aTokenFromAnotherSessionIsRejected() throws Exception {
        var own = new MockHttpSession(); issue(own);
        var other = new MockHttpSession(); var foreignToken = issue(other);
        var req = request("POST", "/login"); req.setSession(own); req.addHeader("X-CSRF-TOKEN", foreignToken);
        var res = new MockHttpServletResponse(); var called = new AtomicBoolean();
        filter.doFilter(req, res, (r, s) -> called.set(true));
        assertEquals(403, res.getStatus()); assertFalse(called.get());
    }

    @Test void ajaxAndFormTokensAllowTheOriginalHandlers() throws Exception {
        var session = new MockHttpSession(); String token = issue(session);
        for (boolean header : new boolean[]{true, false}) {
            var req = request("POST", "/login"); req.setSession(session);
            if (header) req.addHeader("X-CSRF-TOKEN", token); else req.addParameter("_csrf", token);
            var called = new AtomicBoolean(); var res = new MockHttpServletResponse();
            filter.doFilter(req, res, (r, s) -> called.set(true));
            assertEquals(200, res.getStatus()); assertTrue(called.get());
        }
    }

    @Test void getCannotLogOutAnAdministrator() throws Exception {
        var res = new MockHttpServletResponse(); var called = new AtomicBoolean();
        filter.doFilter(request("GET", "/logout"), res, (r, s) -> called.set(true));
        assertEquals(405, res.getStatus()); assertFalse(called.get());
    }
}
