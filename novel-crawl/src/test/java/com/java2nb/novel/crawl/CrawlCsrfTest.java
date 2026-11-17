package com.java2nb.novel.crawl;

import com.java2nb.novel.core.config.SecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ContextConfiguration(classes = CrawlCsrfTest.Config.class)
@TestPropertySource(properties = {"admin.username=csrf-fixture", "admin.password=csrf-fixture-test-only"})
class CrawlCsrfTest {
    @Configuration @EnableWebMvc @Import({SecurityConfiguration.class, Probe.class}) static class Config {}
    @RestController static class Probe {
        @PostMapping("/crawl/csrfProbe") String post() { return "ok"; }
    }
    @Autowired WebApplicationContext context;
    private MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void authenticatedRequestWithoutTokenIsRejected() throws Exception {
        mvc.perform(post("/crawl/csrfProbe").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    }
    @Test void authenticatedRequestWithInvalidTokenIsRejected() throws Exception {
        mvc.perform(post("/crawl/csrfProbe").with(user("admin").roles("ADMIN")).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
    }
    @Test void authenticatedRequestWithTokenStillWorks() throws Exception {
        mvc.perform(post("/crawl/csrfProbe").with(user("admin").roles("ADMIN")).with(csrf().asHeader()))
                .andExpect(status().isOk()).andExpect(content().string("ok"));
    }
    @Test void tokenDoesNotReplaceAdministratorAuthentication() throws Exception {
        mvc.perform(post("/crawl/csrfProbe").with(csrf())).andExpect(status().isUnauthorized());
    }
    @Test void csrfFreeLoginSubmissionIsRejected() throws Exception {
        mvc.perform(post("/login").param("username", "csrf-fixture").param("password", "csrf-fixture-test-only"))
                .andExpect(status().isForbidden());
    }
}
