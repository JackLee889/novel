package com.java2nb.common.security;

import com.java2nb.common.exception.MainsiteErrorController;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Security rejections must retain their HTTP status through the servlet error dispatch. */
class ErrorStatusTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MainsiteErrorController()).build();

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 500})
    void dispatchedJsonErrorsRetainStatus(int code) throws Exception {
        mvc.perform(get("/error").accept(MediaType.APPLICATION_JSON)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, code))
            .andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code));
    }

    @Test void htmlPermissionErrorRetainsForbiddenStatus() throws Exception {
        mvc.perform(get("/error").accept(MediaType.TEXT_HTML)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403))
            .andExpect(status().isForbidden()).andExpect(view().name("error/403"));
    }

    @Test void directErrorRequestCannotMasqueradeAsSuccess() throws Exception {
        mvc.perform(get("/error").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500));
    }
}
