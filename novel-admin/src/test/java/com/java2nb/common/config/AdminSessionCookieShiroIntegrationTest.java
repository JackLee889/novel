package com.java2nb.common.config;

import com.java2nb.common.security.AdminSessionCookieFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.startup.Tomcat;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.session.mgt.eis.MemorySessionDAO;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real production Shiro configuration and servlet chain; only persistence and account data are isolated. */
class AdminSessionCookieShiroIntegrationTest {
    @TempDir Path temporary;

    @Configuration @EnableAspectJAutoProxy(proxyTargetClass = true) @EnableTransactionManagement
    static class ProductionAdvisors {}

    @ParameterizedTest @NullSource @ValueSource(strings = {"true", "false"})
    void rawHttpLoginAndLogoutHonorConfiguredPolicy(String configured) throws Exception {
        boolean expectedSecure = !"false".equals(configured);
        try (var spring = new AnnotationConfigApplicationContext()) {
            // The default case must be absent even when the build runs with the HTTP QA profile.
            spring.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            spring.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            spring.getEnvironment().setActiveProfiles("prod");
            Map<String, Object> properties = new HashMap<>(Map.of(
                    "spring.data.redis.host", "127.0.0.1", "spring.data.redis.port", "1",
                    "spring.data.redis.password", "isolated-no-network", "server.session-timeout", "18000"));
            if (configured != null) properties.put("admin.security.secure-cookie", configured);
            spring.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolatedCookieTest", properties));
            spring.register(ShiroConfig.class, AdminSessionCookieFilter.class, ProductionAdvisors.class);
            spring.refresh();
            var sessions = spring.getBean(DefaultWebSessionManager.class);
            assertEquals(expectedSecure, sessions.getSessionIdCookie().isSecure(), "Production configuration injects its cookie flag");
            sessions.setSessionDAO(new MemorySessionDAO());
            var manager = (DefaultWebSecurityManager) spring.getBean(org.apache.shiro.mgt.SecurityManager.class);
            var realm = new SimpleAccountRealm(); realm.setCachingEnabled(false);
            realm.addAccount("isolated", "isolated-password");
            manager.setCacheManager(null); manager.setRealm(realm);

            var tomcat = new Tomcat(); tomcat.setBaseDir(temporary.resolve("tomcat").toString());
            tomcat.setPort(0); tomcat.getConnector().setProperty("address", "127.0.0.1");
            Path webRoot = Files.createDirectories(temporary.resolve("web"));
            var context = tomcat.addContext("", webRoot.toString());
            addFilter(context, "cookiePolicy", spring.getBean(AdminSessionCookieFilter.class));
            addFilter(context, "shiro", (Filter) spring.getBean("shiroFilterFactoryBean"));
            Tomcat.addServlet(context, "isolatedLogin", new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    assertFalse(request.isSecure(), "This regression test reaches Shiro over raw HTTP");
                    response.getWriter().write(request.getServletPath().equals("/login")
                            ? ((Map<?, ?>) request.getAttribute("_csrf")).get("token").toString() : "authenticated");
                }
                @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    SecurityUtils.getSubject().login(new UsernamePasswordToken("isolated", "isolated-password"));
                    response.getWriter().write("authenticated");
                }
            });
            context.addServletMappingDecoded("/", "isolatedLogin");
            try {
                tomcat.start();
                URI base = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort());
                try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                    var page = client.send(request(base, "/login").GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, page.statusCode());
                    String cookie = sessionHeader(page, expectedSecure).split(";", 2)[0];
                    String token = page.body();
                    var login = client.send(request(base, "/login").header("Cookie", cookie)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + token)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, login.statusCode()); assertEquals("authenticated", login.body());
                    for (String header : login.headers().allValues("Set-Cookie")) {
                        if (header.startsWith("JSESSIONID=")) cookie = checkedHeader(header, expectedSecure).split(";", 2)[0];
                    }
                    var authenticated = client.send(request(base, "/index").header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, authenticated.statusCode()); assertEquals("authenticated", authenticated.body());
                    var logout = client.send(request(base, "/logout").header("Cookie", cookie).header("X-CSRF-TOKEN", token)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(302, logout.statusCode());
                    assertTrue(sessionHeader(logout, expectedSecure).contains("Max-Age=0"), "Logout still expires the session cookie");
                }
            } finally {
                sessions.destroy();
                tomcat.stop(); tomcat.destroy(); org.apache.shiro.util.ThreadContext.remove();
            }
        }
    }

    private static HttpRequest.Builder request(URI base, String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5));
    }

    private static String sessionHeader(HttpResponse<?> response, boolean secure) {
        var headers = response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("JSESSIONID=")).toList();
        assertEquals(1, headers.size(), "One session cookie is issued");
        return checkedHeader(headers.getFirst(), secure);
    }

    private static String checkedHeader(String value, boolean secure) {
        var attributes = java.util.Arrays.stream(value.split(";")).skip(1).map(String::trim).toList();
        assertEquals(secure, attributes.stream().anyMatch("Secure"::equalsIgnoreCase));
        boolean deletion = attributes.stream().anyMatch("Max-Age=0"::equalsIgnoreCase);
        assertEquals(secure || !deletion, attributes.stream().anyMatch("HttpOnly"::equalsIgnoreCase),
                "Disabled policy preserves Shiro's deletion cookie; enabled policy protects it too");
        assertTrue(attributes.stream().anyMatch("Path=/"::equalsIgnoreCase));
        assertTrue(attributes.stream().anyMatch("SameSite=Lax"::equalsIgnoreCase));
        return value;
    }

    private static void addFilter(org.apache.catalina.Context context, String name, Filter filter) {
        var definition = new FilterDef(); definition.setFilterName(name); definition.setFilter(filter); context.addFilterDef(definition);
        var mapping = new FilterMap(); mapping.setFilterName(name); mapping.addURLPattern("/*"); context.addFilterMap(mapping);
    }
}
