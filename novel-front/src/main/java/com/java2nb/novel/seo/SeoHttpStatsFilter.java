package com.java2nb.novel.seo;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Register on REQUEST/ERROR/ASYNC. Completion is observed after the container's error dispatch. */
public final class SeoHttpStatsFilter implements Filter, ServletRequestListener {
    private static final String OBSERVATION = SeoHttpStatsFilter.class.getName() + ".observation";
    private final SeoHttpStats stats;
    public SeoHttpStatsFilter(SeoHttpStats stats) { this.stats = stats; }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response); return;
        }
        Observation observation = (Observation) request.getAttribute(OBSERVATION);
        if (observation == null) {
            observation = new Observation(SeoHttpStats.declaredClient(httpRequest.getHeader("User-Agent")), httpResponse);
            request.setAttribute(OBSERVATION, observation);
        }
        observation.response = httpResponse;
        if (httpRequest.getDispatcherType() == DispatcherType.ERROR) observation.errorDispatched = true;
        try { chain.doFilter(request, response); }
        catch (IOException | ServletException | RuntimeException | Error ex) { observation.unhandledException = true; throw ex; }
    }

    @Override
    public void requestDestroyed(ServletRequestEvent event) {
        Observation observation = (Observation) event.getServletRequest().getAttribute(OBSERVATION);
        if (observation == null || !observation.completed.compareAndSet(false, true)) return;
        int status = observation.response.getStatus();
        boolean unresolved = observation.unhandledException && !observation.errorDispatched && status < 400;
        stats.record(status, observation.clientKind, unresolved);
    }

    private static final class Observation {
        final String clientKind;
        final AtomicBoolean completed = new AtomicBoolean();
        volatile HttpServletResponse response;
        volatile boolean unhandledException, errorDispatched;
        Observation(String clientKind, HttpServletResponse response) { this.clientKind = clientKind; this.response = response; }
    }
}
