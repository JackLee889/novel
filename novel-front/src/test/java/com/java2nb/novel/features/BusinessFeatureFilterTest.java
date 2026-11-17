package com.java2nb.novel.features;

import tools.jackson.databind.ObjectMapper;
import com.java2nb.novel.core.config.BusinessFeatureProperties;
import com.java2nb.novel.core.filter.BusinessFeatureFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessFeatureFilterTest {
    @ParameterizedTest
    @CsvSource({
        "GET,/pay", "GET,/pay/index.html", "POST,/pay/aliPay", "POST,/pay/aliPay/notify",
        "GET,/user/pay.html", "POST,/user/buyBookIndex", "POST,/user/buyBookIndex/",
        "GET,/author", "GET,/author/register.html", "POST,/author/addBook",
        "POST,/author/addBookContent", "POST,/author/updateBookContent", "POST,/author/updateBookPic",
        "POST,/author/ai/expand", "GET,/author/ai/stream/expand", "GET,/author/queryAiGenPic",
        "POST,/file/picUpload"
    })
    void disabledRequestsNeverReachBusinessHandlers(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new BusinessFeatureFilter(new BusinessFeatureProperties()).doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals(403, new ObjectMapper().readTree(response.getContentAsString()).get("code").asInt());
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/user/login", "/user/register", "/file/getVerify", "/user/userInfo",
        "/book/reader/chapter", "/book/reader/catalog", "/reader/tts-manifest.json", "/reader-resources/model.onnx"})
    void readingTtsAndAccountRoutesRemainAvailable(String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new BusinessFeatureFilter(new BusinessFeatureProperties()).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test void encodedContextAndMatrixParametersCannotBypassTheGate() throws Exception {
        var request = new MockHttpServletRequest("POST", "/novel/%61uthor;session=abc/ai/expand");
        request.setContextPath("/novel");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new BusinessFeatureFilter(new BusinessFeatureProperties()).doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test void htmlLinksReturnAnExplanationInsteadOfExecutingTheirHandler() throws Exception {
        var request = new MockHttpServletRequest("GET", "/author/index.html");
        request.addHeader("Accept", "text/html,application/xhtml+xml");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new BusinessFeatureFilter(new BusinessFeatureProperties()).doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("投稿功能暂时关闭"));
        assertTrue(response.getContentAsString().contains("返回书城"));
        verifyNoInteractions(chain);
    }

    @Test void independentFlagsKeepAiClosedWhenSubmissionIsEnabled() throws Exception {
        var features = new BusinessFeatureProperties();
        features.setSubmissionEnabled(true);
        var chain = mock(FilterChain.class);
        var filter = new BusinessFeatureFilter(features);
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/author/ai/stream/polish"), response, chain);
        assertEquals(403, response.getStatus());
        verifyNoInteractions(chain);
        var normalRequest = new MockHttpServletRequest("GET", "/author/index.html");
        var normalResponse = new MockHttpServletResponse();
        filter.doFilter(normalRequest, normalResponse, chain);
        verify(chain).doFilter(normalRequest, normalResponse);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/pay/aliPay", "/user/buyBookIndex", "/author/ai/stream/polish", "/author/addBook", "/file/picUpload"})
    void capabilitiesCanBeRestoredWithExplicitConfiguration(String path) throws Exception {
        var features = new BusinessFeatureProperties();
        features.setPaymentEnabled(true);
        features.setAiEnabled(true);
        features.setSubmissionEnabled(true);
        var request = new MockHttpServletRequest("POST", path);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new BusinessFeatureFilter(features).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }
}
