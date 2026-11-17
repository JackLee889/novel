package com.java2nb.common.config;

import com.java2nb.common.controller.FileController;
import com.java2nb.common.exception.CommonExceptionHandler;
import com.java2nb.common.security.AdminCsrfViewAdvice;
import com.java2nb.common.security.UploadPathResolver;
import com.java2nb.common.service.FileService;
import com.java2nb.common.service.LogService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs the real Shiro chain and permission advisor, using isolated accounts and temporary files. */
class ShiroSecurityIntegrationTest {
    @TempDir Path upload;
    static Path uploadDirectory;
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    Filter filter;
    static MockHttpServletRequestBuilder get(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).servletPath(path);
    }
    static MockHttpServletRequestBuilder post(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).servletPath(path);
    }
    @BeforeEach void setup() throws Exception {
        uploadDirectory=upload;
        Files.writeString(upload.resolve("safe.txt"), "authorized download");
        context=new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Harness.class);context.refresh();
        filter=new ShiroConfig().shiroFilterFactoryBean(context.getBean(SecurityManager.class)).getObject();
        filter.init(new MockFilterConfig());
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    }
    @AfterEach void cleanup() {
        if(filter!=null)filter.destroy();
        if(context!=null)context.close();
        org.apache.shiro.util.ThreadContext.remove();
    }
    @Test void unauthenticatedFileAccessAndMissingCsrfAreRejected() throws Exception {
        mvc.perform(get("/common/sysFile/download").param("filePath","safe.txt"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/common/sysFile/remove").param("id","1"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(FileService.class));
    }
    @Test void permittedAccountCanSortAndDownloadButCannotTraverse() throws Exception {
        Cookie session=login("allowed");
        mvc.perform(get("/common/sysFile/list").cookie(session).param("offset","0").param("limit","10").param("sort","id").param("order","asc"))
                .andExpect(status().isOk());
        mvc.perform(get("/common/sysFile/download").cookie(session).param("filePath","safe.txt").param("fileName","正常.txt"))
                .andExpect(status().isOk()).andExpect(content().string("authorized download"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get("/common/sysFile/download").cookie(session).param("filePath","../outside.txt"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/common/sysFile/list").cookie(session).param("offset","0").param("limit","10").param("sort","id; DROP TABLE book"))
                .andExpect(status().isBadRequest());
    }
    @Test void authenticatedAccountWithoutPermissionCannotDownload() throws Exception {
        Cookie session=login("denied");
        mvc.perform(get("/common/sysFile/download").cookie(session).header("X-Requested-With","XMLHttpRequest").param("filePath","safe.txt"))
                .andExpect(status().isForbidden());
    }
    @Test void loginNeedsSessionTokenAndLogoutRequiresPost() throws Exception {
        mvc.perform(post("/login").param("username","allowed")).andExpect(status().isForbidden());
        login("allowed");
        mvc.perform(get("/logout")).andExpect(status().isMethodNotAllowed());
    }
    Cookie login(String username) throws Exception {
        MvcResult page=mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        Cookie session=MockCookie.parse(page.getResponse().getHeader("Set-Cookie"));
        Map<?,?> csrf=(Map<?,?>)page.getRequest().getAttribute("_csrf");
        MvcResult authenticated=mvc.perform(post("/login").cookie(session).param("username",username).param("_csrf",csrf.get("token").toString()))
                .andExpect(status().isOk()).andExpect(content().string("authenticated")).andReturn();
        for(String header:authenticated.getResponse().getHeaders("Set-Cookie")) {
            Cookie candidate=MockCookie.parse(header);
            if(candidate.getName().equals("JSESSIONID"))session=candidate;
        }
        assertEquals("JSESSIONID",session.getName());
        var persisted=context.getBean(SecurityManager.class).getSession(new org.apache.shiro.session.mgt.DefaultSessionKey(session.getValue()));
        assertEquals(Boolean.TRUE,persisted.getAttribute(org.apache.shiro.subject.support.DefaultSubjectContext.AUTHENTICATED_SESSION_KEY),
                "The authenticated session must be saved before the next request");
        return session;
    }
    @Configuration @EnableWebMvc
    static class Harness {
        @Bean(destroyMethod="destroy") SecurityManager securityManager() {
            SimpleAccountRealm realm=new PermissionRealm();
            DefaultWebSecurityManager manager=new DefaultWebSecurityManager(realm);
            DefaultWebSessionManager sessions=new DefaultWebSessionManager();
            sessions.setSessionDAO(new org.apache.shiro.session.mgt.eis.MemorySessionDAO());
            sessions.setSessionIdUrlRewritingEnabled(false);
            sessions.getSessionIdCookie().setSecure(false);
            manager.setSessionManager(sessions);manager.setRememberMeManager(null);
            return manager;
        }
        @Bean static DefaultAdvisorAutoProxyCreator proxies() {
            DefaultAdvisorAutoProxyCreator bean=new DefaultAdvisorAutoProxyCreator();
            bean.setProxyTargetClass(true);return bean;
        }
        @Bean AuthorizationAttributeSourceAdvisor permissions(SecurityManager manager) {
            return new ShiroConfig().authorizationAttributeSourceAdvisor(manager);
        }
        @Bean JnConfig configuration() { JnConfig bean=new JnConfig();bean.setUploadPath(uploadDirectory.toString());return bean; }
        @Bean UploadPathResolver paths(JnConfig bean) { return new UploadPathResolver(bean); }
        @Bean FileService files() {
            FileService bean=mock(FileService.class);when(bean.list(anyMap())).thenReturn(List.of());return bean;
        }
        @Bean LogService logs() { return mock(LogService.class); }
        @Bean FileController controller() { return new FileController(); }
        @Bean CommonExceptionHandler exceptions() { return new CommonExceptionHandler(); }
        @Bean AdminCsrfViewAdvice csrfView() { return new AdminCsrfViewAdvice(); }
        @Bean TestLogin login() { return new TestLogin(); }
    }
    static class PermissionRealm extends SimpleAccountRealm {
        PermissionRealm() {
            addAccount("allowed","isolated-test-password");
            addAccount("denied","isolated-test-password");
            getUser("allowed").addStringPermission("common:sysFile:sysFile");
        }
    }
    @Controller static class TestLogin {
        @GetMapping("/login") @ResponseBody String page() { return "isolated login"; }
        @PostMapping("/login") @ResponseBody String login(String username) {
            SecurityUtils.getSubject().login(new UsernamePasswordToken(username,"isolated-test-password"));
            return "authenticated";
        }
    }
}
