package com.java2nb.novel.user;

import com.java2nb.novel.controller.UserController;
import com.java2nb.novel.core.advice.CommonExceptionHandler;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.UserService;
import io.github.xxyopen.model.resp.SysResultCode;
import io.github.xxyopen.web.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthenticationRefreshContractTest {
    private MockMvc mvc;
    private JwtTokenUtil tokens;

    @BeforeEach void setup() {
        tokens = spy(new JwtTokenUtil());
        ReflectionTestUtils.setField(tokens, "secret", "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo=");
        ReflectionTestUtils.setField(tokens, "expiration", 3600L);
        var controller = new UserController(mock(CacheService.class), mock(UserService.class), mock(BookService.class));
        controller.setJwtTokenUtil(tokens);
        mvc = MockMvcBuilders.standaloneSetup(controller, new FailingApi(), new MixedPage())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new CommonExceptionHandler()).build();
    }

    @Test void actualRefreshReturnsVerifiedIdentityWithoutReparsingTheNewToken() throws Exception {
        var user = new UserDetails(); user.setId(777L); user.setUsername("real-refresh"); user.setNickName("测试读者");
        String token = tokens.generateToken(user);
        mvc.perform(post("/user/refreshToken").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.username").value("real-refresh"))
                .andExpect(jsonPath("$.data.nickName").value("测试读者"));
        verify(tokens).refreshTokenWithDetails(token);
        verify(tokens, never()).canRefresh(any());
        verify(tokens, never()).getUserDetailsFromToken(any());
    }

    @Test void expiredMalformedAndAbsentTokensKeepTheExistingNoLoginResult() throws Exception {
        mvc.perform(post("/user/refreshToken")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post("/user/refreshToken").header("Authorization", "broken"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1001));
        ReflectionTestUtils.setField(tokens, "expiration", -1L);
        var user = new UserDetails(); user.setId(777L); user.setUsername("expired");
        mvc.perform(post("/user/refreshToken").header("Authorization", tokens.generateToken(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1001));
    }

    @ParameterizedTest @ValueSource(strings={"application/json", "*/*", "text/html"})
    void apiUnexpectedErrorsReturnJson500ForEveryAcceptHeader(String accept) throws Exception {
        mvc.perform(get("/failure/api").header("Accept", accept)).andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andExpect(jsonPath("$.code").value(500));
    }

    @Test void apiUnexpectedErrorWithNoAcceptAndMethodResponseBodyAlsoReturnJson500() throws Exception {
        for (String path : new String[]{"/failure/api", "/failure/mixed-api"}) {
            mvc.perform(get(path)).andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andExpect(jsonPath("$.code").value(500));
        }
    }

    @Test void pageFailureUsesTheInternalErrorPageWith500Status() throws Exception {
        mvc.perform(get("/failure/page").accept(MediaType.TEXT_HTML))
                .andExpect(status().isInternalServerError()).andExpect(view().name("error/500"));
    }

    @Test void expectedBusinessErrorsKeepTheirExistingCodeAndHttpContract() throws Exception {
        mvc.perform(get("/failure/business")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
    }

    @Test void missingResourceHas404StatusInsteadOfAnInternalErrorOrSuccess() throws Exception {
        mvc.perform(get("/failure/missing-page").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound()).andExpect(view().name("error/404"));
        mvc.perform(get("/failure/missing-api")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @RestController static class FailingApi {
        @GetMapping("/failure/api") public String failed() { throw new IllegalStateException("fixture internal failure"); }
        @GetMapping("/failure/business") public String business() { throw new BusinessException(SysResultCode.PARAM_ERROR); }
        @GetMapping("/failure/missing-api") public String missing() throws NoResourceFoundException { throw mock(NoResourceFoundException.class); }
    }

    @Controller static class MixedPage {
        @GetMapping("/failure/page") public String page() { throw new IllegalStateException("fixture page failure"); }
        @GetMapping("/failure/mixed-api") @ResponseBody public String failed() { throw new IllegalStateException("fixture method API failure"); }
        @GetMapping("/failure/missing-page") public String missing() throws NoResourceFoundException { throw mock(NoResourceFoundException.class); }
    }
}
