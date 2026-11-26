package com.fixcampus.api.v1;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixcampus.config.SecurityConfig;
import com.fixcampus.controller.AuthController;
import com.fixcampus.controller.HomeController;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.exception.OrderConflictException;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.security.CampusUserDetailsService;
import com.fixcampus.security.SessionLifecycle;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.fixcampus.api.v1.ApiRequestIdFilter.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {HomeController.class, AuthController.class, StudentApi.class, OrderOperationsApi.class},
        properties = {"fixcampus.web.origin=https://demo.invalid", "fixcampus.rate-limit.login-requests=1",
                "fixcampus.rate-limit.write-requests=1"})
@ActiveProfiles("prod")
@Import({ApiSecurityConfig.class, SecurityConfig.class, SessionLifecycle.class})
class PublicDemoApiTest {
    private static final String ORIGIN = "https://demo.invalid";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder passwords;
    @MockitoBean CampusUserDetailsService accounts;
    @MockitoBean UserService users;
    @MockitoBean RepairOrderService orders;
    @MockitoBean StudentOrders studentOrders;
    @MockitoBean OperationalOrders operationalOrders;

    @ParameterizedTest
    @ValueSource(strings = {"web-slice", "demo", "prod"})
    void eachWebProfileLoadsSharedHardening(String profile) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getProperty("fixcampus.auth.registration-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("fixcampus.web.root-redirect-to-app", Boolean.class)).isTrue();
        assertThat(environment.getProperty("fixcampus.rate-limit.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.datasource.hikari.connection-init-sql"))
                .isEqualTo("SET SESSION innodb_lock_wait_timeout = 3");
    }

    @Test
    void hardenedRootAndDisabledRegistrationPreserveTheApiSessionFlow() throws Exception {
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(redirectedUrl("/app/"));
        mvc.perform(get("/login").param("registered", "")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/register"))))
                .andExpect(content().string(not(containsString("注册成功"))));
        mvc.perform(get("/register")).andExpect(status().isForbidden());
        String password = UUID.randomUUID().toString();
        mvc.perform(post("/register").with(csrf()).param("username", "disabled_signup")
                        .param("realName", "虚构测试").param("password", password).param("confirmPassword", password))
                .andExpect(status().isForbidden());
        String username = "fixture_student";
        when(accounts.loadUserByUsername(username))
                .thenReturn(principal(100L, username, Role.STUDENT, passwords.encode(password)));
        var initial = csrfSession(null);
        String oldId = initial.session().getId();
        mvc.perform(post("/api/v1/auth/login").session(initial.session()).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(initial.headerName(), initial.token()).with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        }).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("100"));
        assertThat(initial.session().getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/v1/me").session(initial.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STUDENT"));
        var authenticated = csrfSession(initial.session());
        mvc.perform(get("/register").session(authenticated.session())).andExpect(status().isForbidden());
        mvc.perform(post("/register").session(authenticated.session())
                        .header(authenticated.headerName(), authenticated.token())
                        .param("username", "disabled_signup").param("realName", "虚构测试")
                        .param("password", password).param("confirmPassword", password))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users);

        var cancel = post("/api/v1/orders/42/cancel").session(authenticated.session())
                .header(HttpHeaders.ORIGIN, ORIGIN).header(authenticated.headerName(), authenticated.token())
                .header(HEADER, "limit-session");
        mvc.perform(cancel).andExpect(status().isNoContent());
        rateLimited(mvc.perform(cancel), "limit-session");
        mvc.perform(get("/api/v1/orders").session(authenticated.session())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").session(authenticated.session()).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(authenticated.headerName(), authenticated.token()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andExpect(cookie().secure("JSESSIONID", true));
        assertThat(authenticated.session().isInvalid()).isTrue();
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginQuotaFollowsRemoteAddressAcrossSessionsAndIgnoresForwardedHeader() throws Exception {
        when(accounts.loadUserByUsername("missing_user")).thenThrow(new UsernameNotFoundException("fixture"));
        rejectedLogin("192.0.2.20", "198.51.100.1").andExpect(status().isUnauthorized());
        rateLimited(rejectedLogin("192.0.2.20", "198.51.100.2"), "limit-login");
        rejectedLogin("192.0.2.21", "198.51.100.1").andExpect(status().isUnauthorized());
        verify(accounts, times(2)).loadUserByUsername("missing_user");
    }

    @ParameterizedTest
    @CsvSource({"200,,STUDENT,201", "210,assign,ADMIN,204", "220,start,WORKER,204",
            "230,complete,WORKER,204", "240,confirm,STUDENT,204", "250,cancel,STUDENT,204"})
    void allOrderWriteRoutesLimitEachSessionIndependently(long id, String action, Role role, int successStatus) throws Exception {
        var actor = principal(id, "fixture_" + id, role, null);
        String path = action == null ? "/api/v1/orders" : "/api/v1/orders/42/" + action;
        String body = switch (action) {
            case null -> "{\"location\":\"虚构地点\",\"category\":\"OTHER\",\"description\":\"虚构报修内容\"}";
            case "assign" -> "{\"workerId\":\"99\"}";
            default -> "{}";
        };
        if (action == null) when(studentOrders.create(eq(actor), any(), any())).thenReturn("42");
        var session = new MockHttpSession();
        orderWrite(actor, session, path, body).andExpect(status().is(successStatus));
        rateLimited(orderWrite(actor, session, path, body), "limit-write");
        orderWrite(actor, new MockHttpSession(), path, body).andExpect(status().is(successStatus));
    }

    @ParameterizedTest
    @MethodSource("lockFailures")
    void lockTimeoutsRemainDistinctFromBusinessConflicts(RuntimeException failure) throws Exception {
        long id = 900L + switch (failure) {
            case PessimisticLockingFailureException ignored -> 1;
            case QueryTimeoutException ignored -> 2;
            default -> 3;
        };
        var actor = principal(id, "fixture_" + id, Role.STUDENT, null);
        doThrow(failure).when(orders).cancel(actor, 42L);
        boolean conflict = failure instanceof OrderConflictException;
        orderWrite(actor, new MockHttpSession(), "/api/v1/orders/42/cancel", "{}")
                .andExpect(status().is(conflict ? 409 : 503))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HEADER, "limit-write"))
                .andExpect(jsonPath("$.requestId").value("limit-write"))
                .andExpect(jsonPath("$.code").value(conflict ? "ORDER_CONFLICT" : "TEMPORARILY_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("internal-db-detail"))));
    }

    static RuntimeException[] lockFailures() {
        return new RuntimeException[]{new PessimisticLockingFailureException("internal-db-detail"),
                new QueryTimeoutException("internal-db-detail"), new OrderConflictException()};
    }

    private ResultActions rejectedLogin(String remote, String forwarded) throws Exception {
        var session = csrfSession(null);
        return mvc.perform(post("/campus/api/v1/auth/login").contextPath("/campus")
                .session(session.session()).header(HEADER, "limit-login")
                .header(HttpHeaders.ORIGIN, ORIGIN).header(session.headerName(), session.token())
                .header("X-Forwarded-For", forwarded).with(request -> {
                    request.setRemoteAddr(remote);
                    return request;
                }).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "missing_user", "password", UUID.randomUUID().toString()))));
    }

    private ResultActions orderWrite(CampusPrincipal actor, MockHttpSession session, String path, String body) throws Exception {
        return mvc.perform(post("/campus" + path).contextPath("/campus")
                .session(session).with(user(actor)).with(csrf())
                .header(HttpHeaders.ORIGIN, ORIGIN).header(HEADER, "limit-write")
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void rateLimited(ResultActions result, String id) throws Exception {
        var response = result.andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HEADER, id))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后再试"))
                .andExpect(jsonPath("$.requestId").value(id)).andReturn().getResponse();
        assertThat(Long.parseLong(response.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
    }

    private CsrfSession csrfSession(MockHttpSession session) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        return new CsrfSession((MockHttpSession) result.getRequest().getSession(false),
                body.get("headerName").asText(), body.get("token").asText());
    }

    private CampusPrincipal principal(long id, String username, Role role, String hash) {
        var account = new User(username, hash, "虚构账号", null, role, LocalDateTime.now());
        ReflectionTestUtils.setField(account, "id", id);
        return new CampusPrincipal(account);
    }

    private record CsrfSession(MockHttpSession session, String headerName, String token) { }
}
