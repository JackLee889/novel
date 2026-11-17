package com.java2nb.novel.user;

import com.java2nb.novel.controller.UserController;
import com.java2nb.novel.core.advice.CommonExceptionHandler;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.entity.User;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserInputValidationTest {
    private MockMvc mvc;
    private UserService users;

    @BeforeEach
    void setup() {
        users = mock(UserService.class);
        UserController controller = new UserController(mock(CacheService.class), users, mock(BookService.class));
        JwtTokenUtil tokens = mock(JwtTokenUtil.class);
        UserDetails details = new UserDetails();
        details.setId(9L);
        when(tokens.getUserDetailsFromToken("test-session")).thenReturn(details);
        when(tokens.generateToken(any())).thenReturn("test-token");
        controller.setJwtTokenUtil(tokens);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator)
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
            .setControllerAdvice(new CommonExceptionHandler()).build();
    }

    @Test
    void missingAndBlankLoginFieldsNeverReachTheService() throws Exception {
        mvc.perform(post("/user/login")).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/user/login").param("username", "19910710411"))
            .andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/user/login").param("username", "19910710411").param("password", "   "))
            .andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/user/login").param("username", " ").param("password", "valid"))
            .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(users);
    }

    @Test
    void validLoginRetainsLegacyPasswordNormalizationAndLength() throws Exception {
        when(users.login(any())).thenReturn(new UserDetails());
        mvc.perform(post("/user/login").param("username", " 19910710411 ").param("password", " a "))
            .andExpect(jsonPath("$.code").value(200));
        ArgumentCaptor<User> input = ArgumentCaptor.forClass(User.class);
        verify(users).login(input.capture());
        assertEquals("19910710411", input.getValue().getUsername());
        assertEquals("a", input.getValue().getPassword());
    }

    @ParameterizedTest
    @CsvSource({"0,2", "-1,2", "1,0", "1,-2", "abc,2", "1,abc", "9223372036854775808,2"})
    void invalidHistoryIdsAreRejectedBeforeWrites(String book, String chapter) throws Exception {
        mvc.perform(post("/user/addReadHistory").header("Authorization", "test-session")
                .param("bookId", book).param("preContentId", chapter))
            .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(users);
    }

    @Test
    void missingHistoryIdIsRejectedAndAuthenticatedLongIdsStayExact() throws Exception {
        mvc.perform(post("/user/addReadHistory").header("Authorization", "test-session").param("bookId", "1"))
            .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(users);
        mvc.perform(post("/user/addReadHistory").header("Authorization", "test-session")
                .param("bookId", "2106695344219004928").param("preContentId", "2106695391920824320")
                .param("userId", "123"))
            .andExpect(jsonPath("$.code").value(200));
        verify(users).addReadHistory(9L, 2106695344219004928L, 2106695391920824320L);
    }

    @ParameterizedTest
    @CsvSource({"0,12", "-1,12", "1,0", "1,-1", "1,101", "abc,12", "1,abc", "2147483648,12"})
    void invalidPaginationIsRejected(String page, String size) throws Exception {
        mvc.perform(get("/user/listReadHistoryByPage").header("Authorization", "test-session")
                .param("curr", page).param("limit", size))
            .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(users);
    }

    @Test
    void defaultAndMaximumPageSizesRemainUsable() throws Exception {
        mvc.perform(get("/user/listReadHistoryByPage").header("Authorization", "test-session"))
            .andExpect(jsonPath("$.code").value(200));
        verify(users).listReadHistoryByPage(9L, 1, 10);
        mvc.perform(get("/user/listReadHistoryByPage").header("Authorization", "test-session")
                .param("curr", "2").param("limit", "100"))
            .andExpect(jsonPath("$.code").value(200));
        verify(users).listReadHistoryByPage(9L, 2, 100);
    }

    @Test
    void anonymousHistoryRequestsCannotReadOrWrite() throws Exception {
        mvc.perform(get("/user/listReadHistoryByPage")).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post("/user/addReadHistory").param("bookId", "1").param("preContentId", "2"))
            .andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(users);
    }
}
