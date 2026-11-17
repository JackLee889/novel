package com.java2nb.novel.seo;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.core.StandardContext;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real servlet lifecycle: the listener observes final status after error and async handling. */
class SeoHttpStatsServletIntegrationTest {
    @TempDir Path temporary;

    @Test void containerErrorAndAsyncCompletionHaveCorrectStatusesAndExactlyOneObservation() throws Exception {
        var stats = mock(SeoHttpStats.class); var filter = new SeoHttpStatsFilter(stats);
        var tomcat = new Tomcat(); tomcat.setBaseDir(temporary.toString()); tomcat.setPort(0); tomcat.getConnector();
        var context = tomcat.addContext("", temporary.toString());
        ((StandardContext) context).addApplicationEventListener(filter);
        var definition = new FilterDef(); definition.setFilterName("stats"); definition.setFilter(filter); definition.setAsyncSupported("true"); context.addFilterDef(definition);
        var mapping = new FilterMap(); mapping.setFilterName("stats"); mapping.addURLPattern("/*");
        mapping.setDispatcher("REQUEST"); mapping.setDispatcher("ERROR"); mapping.setDispatcher("ASYNC"); context.addFilterMap(mapping);
        var servlet = Tomcat.addServlet(context, "probe", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                switch (request.getRequestURI()) {
                    case "/exception" -> throw new ServletException("intentional isolated lifecycle probe");
                    case "/missing" -> response.sendError(404);
                    case "/async" -> {
                        var async = request.startAsync();
                        async.start(() -> { ((HttpServletResponse) async.getResponse()).setStatus(503); async.complete(); });
                    }
                    default -> response.setStatus(200);
                }
            }
        });
        servlet.setAsyncSupported(true); context.addServletMappingDecoded("/*", "probe");
        try {
            tomcat.start();
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                for (var probe : new Object[][]{{"/exception", 500}, {"/missing", 404}, {"/async", 503}}) {
                    var uri = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + probe[0]);
                    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("User-Agent", "Googlebot/2.1").GET().build();
                    assertEquals(probe[1], client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                    verify(stats, timeout(5000).times(1)).record((Integer) probe[1], "googlebot", false);
                }
                verifyNoMoreInteractions(stats);
            }
        } finally { tomcat.stop(); tomcat.destroy(); }
    }
}
