package com.java2nb.novel.visitor;

import io.github.xxyopen.model.resp.RestResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

@RestController
@RequestMapping("/site-stats")
public class VisitorStatsController {
    private final VisitorStatsService stats;
    private final VisitorStatsProperties properties;
    private final String seoSiteUrl;

    public VisitorStatsController(VisitorStatsService stats, VisitorStatsProperties properties,
                                  @Value("${seo.site-url:}") String seoSiteUrl) {
        this.stats = stats; this.properties = properties; this.seoSiteUrl = seoSiteUrl;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public RestResult<VisitorStatsService.Snapshot> snapshot(HttpServletResponse response) {
        noStore(response);
        return RestResult.ok(stats.snapshot());
    }

    @PostMapping(value = "/visit", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RestResult<VisitorStatsService.Snapshot> visit(@RequestParam String visitorId, @RequestParam String pageViewId,
                                                         HttpServletRequest request, HttpServletResponse response) {
        noStore(response); requireSameOrigin(request);
        if (VisitorRequestClassifier.isAutomated(request)) return excluded(response);
        return RestResult.ok(stats.visit(visitorId, pageViewId));
    }

    @PostMapping(value = "/heartbeat", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RestResult<VisitorStatsService.Snapshot> heartbeat(@RequestParam String visitorId,
                                                             HttpServletRequest request, HttpServletResponse response) {
        noStore(response); requireSameOrigin(request);
        if (VisitorRequestClassifier.isAutomated(request)) return excluded(response);
        return RestResult.ok(stats.heartbeat(visitorId));
    }

    private RestResult<VisitorStatsService.Snapshot> excluded(HttpServletResponse response) {
        response.setHeader("X-Visitor-Stats-Excluded", "automated-client");
        return RestResult.ok(stats.snapshot());
    }

    // Explicit public origins handle HTTPS termination and aliases without trusting arbitrary forwarded headers or enabling CORS.
    private void requireSameOrigin(HttpServletRequest request) {
        String configured = properties.getSiteUrl().isBlank() ? seoSiteUrl : properties.getSiteUrl();
        URI expected = configured == null || configured.isBlank()
            ? origin(request.getScheme(), request.getServerName(), request.getServerPort()) : parse(configured, false);
        String suppliedOrigin = request.getHeader("Origin");
        URI supplied = suppliedOrigin != null ? parse(suppliedOrigin, false) : parse(request.getHeader("Referer"), true);
        boolean permitted = sameOrigin(expected, supplied) || properties.getAllowedOrigins().stream()
            .map(VisitorStatsController::parseAllowedOrigin).anyMatch(alias -> sameOrigin(alias, supplied));
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site")) || !permitted)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "访客统计需要同源请求");
    }

    private static URI origin(String scheme, String host, int port) {
        try { return new URI(scheme, null, host, port, null, null, null); }
        catch (java.net.URISyntaxException ex) { return null; }
    }
    private static URI parse(String value, boolean allowPath) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || uri.getHost() == null || uri.getFragment() != null || uri.getScheme() == null) return null;
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            if (!allowPath && (uri.getRawQuery() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")))) return null;
            return uri;
        } catch (IllegalArgumentException ex) { return null; }
    }
    private static URI parseAllowedOrigin(String value) {
        URI uri = parse(value, false);
        if (uri == null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
            || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) return null;
        return uri;
    }
    private static boolean sameOrigin(URI a, URI b) {
        return a != null && b != null && a.getScheme().equalsIgnoreCase(b.getScheme())
            && Objects.equals(a.getHost().toLowerCase(Locale.ROOT), b.getHost().toLowerCase(Locale.ROOT)) && port(a) == port(b);
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80; }
    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Robots-Tag", "noindex,follow");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<RestResult<Void>> invalid(Exception error, HttpServletResponse response) {
        noStore(response);
        var result = RestResult.ok(); result.setCode(400); result.setMsg("访客统计标识无效");
        return ResponseEntity.badRequest().body(result);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<RestResult<Void>> forbidden(ResponseStatusException error, HttpServletResponse response) {
        noStore(response);
        var result = RestResult.ok(); result.setCode(error.getStatusCode().value()); result.setMsg(error.getReason());
        return ResponseEntity.status(error.getStatusCode()).body(result);
    }
}
