package com.java2nb.novel.seo;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeoHttpStatsFilterTest {
    @Test void terminal404And5xxAreCountedOnceAfterErrorDispatchNotAsTheOriginal200() throws Exception {
        var stats = mock(SeoHttpStats.class); var filter = new SeoHttpStatsFilter(stats);
        var request = new MockHttpServletRequest("GET", "/missing.html"); var response = new MockHttpServletResponse();
        request.addHeader("User-Agent", "Googlebot/2.1");
        assertThrows(ServletException.class, () -> filter.doFilter(request, response, (req, res) -> { throw new ServletException("failed"); }));
        verifyNoInteractions(stats); // raw exception leaves a default 200 until the container dispatches /error
        response.setStatus(503); request.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(request, response, (req, res) -> {});
        var event = new ServletRequestEvent(new MockServletContext(), request);
        filter.requestDestroyed(event); filter.requestDestroyed(event);
        verify(stats, times(1)).record(503, "googlebot", false); verifyNoMoreInteractions(stats);
    }

    @Test void unresolvedExceptionIsSeparateAndNeverReportedAsAConfirmedSuccessfulResponse() throws Exception {
        var stats = mock(SeoHttpStats.class); var filter = new SeoHttpStatsFilter(stats);
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        assertThrows(IOException.class, () -> filter.doFilter(request, response, (req, res) -> { throw new IOException("failed"); }));
        filter.requestDestroyed(new ServletRequestEvent(new MockServletContext(), request));
        verify(stats).record(200, "ordinary", true);
    }

    @Test void asyncRedispatchSharesOneObservationAndUsesTheFinalStatus() throws Exception {
        var stats = mock(SeoHttpStats.class); var filter = new SeoHttpStatsFilter(stats);
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {}); verifyNoInteractions(stats);
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(404));
        filter.requestDestroyed(new ServletRequestEvent(new MockServletContext(), request));
        verify(stats).record(404, "ordinary", false); verifyNoMoreInteractions(stats);
    }

    @Test void mapsAndRobotsAreObservedWithoutCookiesSessionsOrRequestSideDatabaseWrites() throws Exception {
        var repository = mock(SeoHttpStatsRepository.class); var stats = new SeoHttpStats(repository); var filter = new SeoHttpStatsFilter(stats);
        for (String path : new String[]{"/robots.txt", "/sitemap.xml", "/sitemaps/core-test.xml.gz"}) {
            var request = new MockHttpServletRequest("HEAD", path); var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> response.setStatus(304));
            filter.requestDestroyed(new ServletRequestEvent(new MockServletContext(), request));
            assertNull(request.getSession(false)); assertEquals(0, response.getCookies().length); assertEquals(304, response.getStatus());
        }
        verifyNoInteractions(repository); assertEquals(3, stats.snapshot().getFirst().totalRequests());
    }
}
