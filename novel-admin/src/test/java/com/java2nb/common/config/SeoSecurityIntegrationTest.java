package com.java2nb.common.config;

import com.java2nb.common.exception.CommonExceptionHandler;
import com.java2nb.common.security.AdminCsrfViewAdvice;
import com.java2nb.common.service.LogService;
import com.java2nb.novel.controller.SeoController;
import com.java2nb.novel.controller.SeoExceptionAdvice;
import com.java2nb.novel.service.SeoAdminService;
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
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import java.util.Map;
import java.util.ConcurrentModificationException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Shiro annotations/filter/session CSRF + MVC same-origin interception, without Redis/MySQL/HTTP. */
class SeoSecurityIntegrationTest {
    AnnotationConfigWebApplicationContext context;MockMvc mvc;Filter filter;SeoAdminService service;
    @BeforeEach void setup() throws Exception {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.register(Harness.class,SeoAdminWebConfiguration.class);context.refresh();service=context.getBean(SeoAdminService.class);
        filter=new ShiroConfig().shiroFilterFactoryBean(context.getBean(SecurityManager.class)).getObject();filter.init(new MockFilterConfig());
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    }
    @AfterEach void cleanup(){if(filter!=null)filter.destroy();if(context!=null)context.close();org.apache.shiro.util.ThreadContext.remove();}
    @Test void anonymousAndReadOnlyUsersCannotRunRebuilds() throws Exception {
        mvc.perform(get("/novel/seo/books")).andExpect(status().is3xxRedirection());
        Login reader=login("reader");
        mvc.perform(get("/novel/seo/books").cookie(reader.cookie)).andExpect(status().isOk());
        mvc.perform(get("/novel/seo/config").cookie(reader.cookie)).andExpect(status().isForbidden());
        mvc.perform(write("/novel/seo/rebuild",reader,"{\"scope\":\"all\"}").header("Origin","http://localhost")).andExpect(status().isForbidden());
        verify(service,never()).enqueue(any());
    }
    @Test void permissionDoesNotBypassCsrfOrOriginChecks() throws Exception {
        Login editor=login("editor");String path="/novel/seo/rebuild",body="{\"scope\":\"all\"}";
        mvc.perform(post(path).cookie(editor.cookie).header("Origin","http://localhost").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(write(path,editor,body)).andExpect(status().isForbidden());
        for(String origin:List.of("https://evil.example","null","http://localhost.evil.example","http://localhost:81","http://u@localhost"))
            mvc.perform(write(path,editor,body).header("Origin",origin)).andExpect(status().isForbidden());
        mvc.perform(write(path,editor,body).header("Origin","http://localhost").header("Sec-Fetch-Site","cross-site")).andExpect(status().isForbidden());
        verify(service,never()).enqueue(any());
        mvc.perform(write(path,editor,body).header("Origin","http://localhost")).andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value("fixture-job"));
        verify(service).enqueue(new SeoAdminService.Rebuild("all",null));
    }
    @Test void sameOriginRefererFallbackAndVersionConflictAreHandled() throws Exception {
        Login editor=login("editor");
        mvc.perform(write("/novel/seo/rebuild",editor,"{\"scope\":\"all\"}").header("Referer","http://localhost/novel/seo")).andExpect(status().isOk());
        when(service.updateConfig(any(),eq(77L))).thenThrow(new ConcurrentModificationException("fixture conflict"));
        mvc.perform(write("/novel/seo/config",editor,"{\"expectedEpoch\":\"1\",\"indexEnabled\":false,\"chaptersIndexEnabled\":false,\"chapterSitemapEnabled\":false}")
            .header("Origin","http://localhost")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    }
    @Test void snowflakeValuesRemainExactStringsInJson() throws Exception {
        Login editor=login("editor");when(service.config()).thenReturn(Map.of("epoch",9007199254740993L,"indexEnabled",false));
        mvc.perform(get("/novel/seo/config").cookie(editor.cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.epoch").value("9007199254740993")).andExpect(jsonPath("$.data.indexEnabled").value(false));
    }
    private Login login(String user) throws Exception {
        var page=mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        Cookie cookie=MockCookie.parse(page.getResponse().getHeader("Set-Cookie"));String token=((Map<?,?>)page.getRequest().getAttribute("_csrf")).get("token").toString();
        var authenticated=mvc.perform(post("/login").cookie(cookie).param("username",user).param("_csrf",token)).andExpect(status().isOk()).andReturn();
        for(String value:authenticated.getResponse().getHeaders("Set-Cookie")){Cookie c=MockCookie.parse(value);if(c.getName().equals("JSESSIONID"))cookie=c;}
        var current=mvc.perform(get("/login").cookie(cookie)).andExpect(status().isOk()).andReturn();
        token=((Map<?,?>)current.getRequest().getAttribute("_csrf")).get("token").toString();return new Login(cookie,token);
    }
    private MockHttpServletRequestBuilder write(String path,Login login,String body){return post(path).cookie(login.cookie).header("X-CSRF-TOKEN",login.token).contentType(MediaType.APPLICATION_JSON).content(body);}
    static MockHttpServletRequestBuilder get(String path){return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).servletPath(path).header("X-Requested-With","XMLHttpRequest");}
    static MockHttpServletRequestBuilder post(String path){return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).servletPath(path).header("X-Requested-With","XMLHttpRequest");}
    record Login(Cookie cookie,String token){}
    @Configuration @EnableWebMvc static class Harness {
        @Bean(destroyMethod="destroy") SecurityManager manager(){
            SimpleAccountRealm realm=new PermissionRealm();
            DefaultWebSecurityManager manager=new DefaultWebSecurityManager(realm);DefaultWebSessionManager sessions=new DefaultWebSessionManager();
            sessions.setSessionDAO(new org.apache.shiro.session.mgt.eis.MemorySessionDAO());sessions.setSessionIdUrlRewritingEnabled(false);sessions.getSessionIdCookie().setSecure(false);
            manager.setSessionManager(sessions);manager.setRememberMeManager(null);return manager;
        }
        @Bean static DefaultAdvisorAutoProxyCreator proxies(){var bean=new DefaultAdvisorAutoProxyCreator();bean.setProxyTargetClass(true);return bean;}
        @Bean AuthorizationAttributeSourceAdvisor permissions(SecurityManager manager){return new ShiroConfig().authorizationAttributeSourceAdvisor(manager);}
        @Bean SeoAdminService service(){var bean=mock(SeoAdminService.class);when(bean.books(anyString(),anyInt(),anyInt())).thenReturn(List.of());when(bean.enqueue(any())).thenReturn("fixture-job");return bean;}
        @Bean SeoController controller(SeoAdminService service){var bean=spy(new SeoController(service));doReturn(77L).when(bean).getUserId();return bean;}
        @Bean SeoExceptionAdvice errors(){return new SeoExceptionAdvice();}
        @Bean CommonExceptionHandler commonErrors(){return new CommonExceptionHandler();}
        @Bean LogService logs(){return mock(LogService.class);}
        @Bean AdminCsrfViewAdvice csrfView(){return new AdminCsrfViewAdvice();}
        @Bean TestLogin login(){return new TestLogin();}
    }
    static class PermissionRealm extends SimpleAccountRealm {
        PermissionRealm(){
            addAccount("reader","isolated-fixture");addAccount("editor","isolated-fixture");
            getUser("reader").addStringPermission("seo:book:view");
            for(String permission:List.of("seo:config:view","seo:config:edit","seo:sitemap:rebuild"))getUser("editor").addStringPermission(permission);
        }
    }
    @Controller static class TestLogin {
        @GetMapping("/login") @ResponseBody String page(){return "isolated login";}
        @PostMapping("/login") @ResponseBody String login(String username){SecurityUtils.getSubject().login(new UsernamePasswordToken(username,"isolated-fixture"));return "authenticated";}
    }
}
