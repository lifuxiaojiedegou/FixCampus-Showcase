package com.fixcampus.api.v1;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fixcampus.security.SessionLifecycle;
import com.fixcampus.service.RepairOrderService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.fixcampus.api.v1.ApiRequestIdFilter.HEADER;
import static com.fixcampus.api.v1.ApiRequestIdFilter.MDC_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = StudentApi.class, properties = "fixcampus.web.origin=http://127.0.0.1:5173")
@ActiveProfiles("web-slice")
@Import(ApiSecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ApiRequestIdTest {
    @Autowired MockMvc mvc;
    @Autowired ApiRequestIdFilter filter;
    @MockitoBean(name = "apiAuthenticationManager") AuthenticationManager authentication;
    @MockitoBean SessionLifecycle lifecycle;
    @MockitoBean RepairOrderService orders;
    @MockitoBean StudentOrders studentOrders;

    @Test
    void failedLoginUsesTheSameIdInHeadersBodyAndRedactedLog(CapturedOutput output) throws Exception {
        String id = "login-check_42";
        when(authentication.authenticate(any())).thenThrow(new BadCredentialsException("internal-detail-sentinel"));

        mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .header("Origin", "http://127.0.0.1:5173").header(HEADER, id)
                        .contentType("application/json")
                        .content("{\"username\":\"synthetic-login-subject\",\"password\":\"synthetic-login-value\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HEADER, id))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.requestId").value(id));

        assertThat(output).contains("api_request requestId=" + id + " status=401 route=/api/v1/auth/login")
                .doesNotContain("synthetic-login-subject", "synthetic-login-value", "internal-detail-sentinel");
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void securityDenialsReplaceInvalidIdsAndDoNotLogQueryValues(CapturedOutput output) throws Exception {
        var denied = mvc.perform(get("/api/v1/admin/workers").with(user("fictional").roles("STUDENT"))
                        .header(HEADER, "invalid supplied id").queryParam("q", "private-query-sentinel"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"))
                .andReturn();
        String id = denied.getResponse().getHeader(HEADER);
        assertThat(id).isNotNull().isEqualTo(UUID.fromString(id).toString());
        assertThat(denied.getResponse().getContentAsString()).contains("\"requestId\":\"" + id + "\"");
        assertThat(output).contains("api_request requestId=" + id + " status=403")
                .doesNotContain("invalid supplied id", "private-query-sentinel");

        var anonymous = mvc.perform(get("/api/v1/me").header(HEADER, "duplicate-one", "duplicate-two"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andReturn();
        String generated = anonymous.getResponse().getHeader(HEADER);
        assertThat(generated).isNotNull().isEqualTo(UUID.fromString(generated).toString()).isNotEqualTo(id);
        assertThat(anonymous.getResponse().getContentAsString()).contains("\"requestId\":\"" + generated + "\"");
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void concurrentRequestsKeepTheirOwnContextAndClearItOnExit() throws Exception {
        var bothEntered = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> runFilter("parallel-a", bothEntered));
            var second = executor.submit(() -> runFilter("parallel-b", bothEntered));
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("parallel-a");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("parallel-b");
        }
    }

    private String runFilter(String id, CountDownLatch bothEntered) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader(HEADER, id);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            bothEntered.countDown();
            try {
                assertThat(bothEntered.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ServletException(interrupted);
            }
            assertThat(MDC.get(MDC_KEY)).isEqualTo(id);
        });
        assertThat(MDC.get(MDC_KEY)).isNull();
        return response.getHeader(HEADER);
    }
}
