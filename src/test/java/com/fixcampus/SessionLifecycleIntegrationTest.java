package com.fixcampus;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.SessionLifecycle;
import com.fixcampus.security.SsrSessionLifecycleFilter;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("web-slice")
@Import(SessionLifecycleIntegrationTest.TimeConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionLifecycleIntegrationTest extends RealMySqlTestSupport {
    enum Entry { SSR, API }
    static final String ORIGIN = "http://127.0.0.1:5173";
    static final long LIMIT = SessionLifecycle.ABSOLUTE_TIMEOUT_MILLIS;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired FilterChainProxy security;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired jakarta.servlet.ServletContext servlet;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    static final AtomicReference<HttpSession> realSession = new AtomicReference<>();
    private User student, admin;
    private final String password = UUID.randomUUID().toString();

    static class MutableClock extends Clock {
        private final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        void set(long value) { now.set(value); }
        public ZoneId getZone() { return ZoneId.of("Asia/Hong_Kong"); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        public long millis() { return now.get(); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
        @Bean ServletListenerRegistrationBean<HttpSessionListener> sessionObservation() {
            return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
                @Override public void sessionCreated(HttpSessionEvent event) { realSession.set(event.getSession()); }
            });
        }
    }
    @BeforeAll void fixtures() {
        String prefix = "life" + UUID.randomUUID().toString().substring(0, 8);
        student = users.saveAndFlush(new User(prefix + "s", passwords.encode(password), "虚构生命周期学生", null, Role.STUDENT, LocalDateTime.now(clock)));
        admin = users.saveAndFlush(new User(prefix + "a", passwords.encode(password), "虚构生命周期管理员", null, Role.ADMIN, LocalDateTime.now(clock)));
    }
    @AfterAll void cleanup() { users.deleteAllById(List.of(student.getId(), admin.getId())); }
    @BeforeEach void resetTime() { clock.set(System.currentTimeMillis()); }
    record Browser(MockHttpSession session, String token) { }
    Browser csrf(MockHttpSession session, String path) throws Exception {
        var request = get(path); if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var token = (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
        return new Browser((MockHttpSession) result.getRequest().getSession(false), token.getToken());
    }
    MockHttpSession authenticate(Entry entry, Browser before, User actor) throws Exception {
        var request = entry == Entry.SSR ? post("/login").param("username", actor.getUsername()).param("password", password)
                : post("/api/v1/auth/login").header("Origin", ORIGIN).contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("username", actor.getUsername(), "password", password)));
        var result = mvc.perform(request.session(before.session).header("X-CSRF-TOKEN", before.token))
                .andExpect(status().is(entry == Entry.SSR ? 302 : 200)).andReturn();
        if (entry == Entry.SSR) assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(actor.getRole().getHomePath());
        return (MockHttpSession) result.getRequest().getSession(false);
    }
    Browser login(Entry entry) throws Exception {
        var before = csrf(null, entry == Entry.SSR ? "/login" : "/api/v1/auth/csrf");
        return csrf(authenticate(entry, before, student), "/api/v1/auth/csrf");
    }
    String home(Entry entry) { return entry == Entry.SSR ? "/student/orders" : "/api/v1/me"; }
    long stamp(MockHttpSession session) { return (Long) session.getAttribute(SessionLifecycle.AUTHENTICATED_AT); }

    @ParameterizedTest @EnumSource(Entry.class)
    void successfulLoginMarksAfterFixationAndAcceptsOldAnonymousSession(Entry entry) throws Exception {
        var before = csrf(null, entry == Entry.SSR ? "/login" : "/api/v1/auth/csrf");
        String id = before.session.getId();
        clock.set(before.session.getCreationTime() + LIMIT * 2);
        var session = authenticate(entry, before, student);
        assertThat(session.getId()).isNotEqualTo(id);
        assertThat(stamp(session)).isEqualTo(clock.millis());
        mvc.perform(get(home(entry)).session(session)).andExpect(status().isOk());
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void failedLoginDoesNotInitializeAuthenticationTime(Entry entry) throws Exception {
        var before = csrf(null, entry == Entry.SSR ? "/login" : "/api/v1/auth/csrf");
        var request = entry == Entry.SSR
                ? post("/login").param("username", student.getUsername()).param("password", "deliberately-wrong")
                : post("/api/v1/auth/login").contentType("application/json").header("Origin", ORIGIN)
                    .content(mapper.writeValueAsString(Map.of("username", student.getUsername(), "password", "deliberately-wrong")));
        mvc.perform(request.session(before.session).header("X-CSRF-TOKEN", before.token))
                .andExpect(status().is(entry == Entry.SSR ? 302 : 401));
        assertThat(before.session.getAttribute(SessionLifecycle.AUTHENTICATED_AT)).isNull();
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void legacyAuthenticatedSessionFallsBackToCreationWithoutRenewal(Entry entry) throws Exception {
        var browser = login(entry); browser.session.removeAttribute(SessionLifecycle.AUTHENTICATED_AT);
        long creation = browser.session.getCreationTime();
        clock.set(creation + LIMIT - 1);
        mvc.perform(get(home(entry)).session(browser.session)).andExpect(status().isOk());
        assertThat(browser.session.getAttribute(SessionLifecycle.AUTHENTICATED_AT)).isNull();
        clock.set(creation + LIMIT);
        mvc.perform(get(home(entry)).session(browser.session)).andExpect(status().is(entry == Entry.SSR ? 302 : 401));
        assertThat(browser.session.isInvalid()).isTrue();
    }

    @ParameterizedTest @CsvSource({"SSR,-1", "SSR,0", "SSR,1", "API,-1", "API,0", "API,1"})
    void absoluteBoundaryAndStoredContextAreEnforced(Entry entry, long offset) throws Exception {
        var browser = login(entry);
        var stored = (SecurityContext) browser.session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        clock.set(stamp(browser.session) + LIMIT + offset);
        var result = mvc.perform(get(home(entry)).session(browser.session)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(offset < 0 ? 200 : entry == Entry.SSR ? 302 : 401);
        if (offset >= 0) {
            assertThat(browser.session.isInvalid()).isTrue();
            assertThat(stored.getAuthentication()).isNull();
            if (entry == Entry.API) {
                assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
                assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("code").asText()).isEqualTo("AUTH_REQUIRED");
            } else assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
        }
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void activityCsrfAndIdentityDoNotRenewAndBothEntrancesShareTheDeadline(Entry entry) throws Exception {
        var browser = login(entry); long original = stamp(browser.session);
        clock.set(original + LIMIT - 1);
        mvc.perform(get("/student/orders").session(browser.session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").session(browser.session)).andExpect(status().isOk());
        csrf(browser.session, "/api/v1/auth/csrf");
        assertThat(stamp(browser.session)).isEqualTo(original);
        clock.set(original + LIMIT);
        mvc.perform(get(home(entry == Entry.SSR ? Entry.API : Entry.SSR)).session(browser.session))
                .andExpect(status().is(entry == Entry.SSR ? 401 : 302));
        assertThat(browser.session.isInvalid()).isTrue();
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void expiredWritesAreStoppedBeforeCsrfAndBusiness(Entry entry) throws Exception {
        var browser = login(entry); clock.set(stamp(browser.session) + LIMIT);
        // Even with no CSRF and an invalid body, expiry wins before controller/service validation.
        var request = post(entry == Entry.SSR ? "/student/orders" : "/api/v1/orders").session(browser.session);
        mvc.perform(request).andExpect(status().is(entry == Entry.SSR ? 302 : 401));
        assertThat(users.findById(student.getId())).isPresent();
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void reauthenticationRotatesSessionAndCsrfWithoutReusingOldAuthTime(Entry entry) throws Exception {
        var old = login(entry); long original = stamp(old.session); String oldId = old.session.getId();
        clock.set(original + LIMIT);
        var fresh = csrf(old.session, entry == Entry.SSR ? "/login" : "/api/v1/auth/csrf");
        assertThat(old.session.isInvalid()).isTrue();
        assertThat(fresh.session.getId()).isNotEqualTo(oldId);
        String preLoginId = fresh.session.getId();
        var session = authenticate(entry, fresh, student);
        assertThat(session.getId()).isNotEqualTo(preLoginId);
        assertThat(stamp(session)).isEqualTo(clock.millis()).isGreaterThan(original);
        mvc.perform(post(entry == Entry.SSR ? "/student/orders" : "/api/v1/orders").session(session)
                .header("Origin", ORIGIN).header("X-CSRF-TOKEN", fresh.token)).andExpect(status().isForbidden());
        var current = csrf(session, "/api/v1/auth/csrf");
        mvc.perform(post(entry == Entry.SSR ? "/logout" : "/api/v1/auth/logout").session(session)
                .header("Origin", ORIGIN).header("X-CSRF-TOKEN", current.token))
                .andExpect(status().is(entry == Entry.SSR ? 302 : 204));
        assertThat(session.isInvalid()).isTrue();
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void logoutDoesNotInvalidateAnIndependentSession(Entry entry) throws Exception {
        var first = login(entry); var second = login(entry);
        mvc.perform(post(entry == Entry.SSR ? "/logout" : "/api/v1/auth/logout").session(first.session)
                .header("Origin", ORIGIN).header("X-CSRF-TOKEN", first.token))
                .andExpect(status().is(entry == Entry.SSR ? 302 : 204));
        assertThat(first.session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/me").session(second.session)).andExpect(status().isOk());
        mvc.perform(get("/student/orders").session(second.session)).andExpect(status().isOk());
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void concurrentExpiredRequestsAreRejected(Entry entry) throws Exception {
        var browser = login(entry); clock.set(stamp(browser.session) + LIMIT);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var requests = java.util.stream.IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                start.await();
                return mvc.perform(get(home(entry)).session(browser.session)).andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            for (var request : requests)
                assertThat(request.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(entry == Entry.SSR ? 302 : 401);
        }
        assertThat(browser.session.isInvalid()).isTrue();
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void normalCsrfAndRoleDenialsRemain403(Entry entry) throws Exception {
        var browser = login(entry); long original = stamp(browser.session);
        mvc.perform(post(entry == Entry.SSR ? "/student/orders" : "/api/v1/orders").session(browser.session)
                .header("Origin", ORIGIN)).andExpect(status().isForbidden());
        assertThat(stamp(browser.session)).isEqualTo(original);
        var anonymous = csrf(null, entry == Entry.SSR ? "/login" : "/api/v1/auth/csrf");
        var adminSession = authenticate(entry, anonymous, admin);
        mvc.perform(get(entry == Entry.SSR ? "/student/orders" : "/api/v1/orders").session(adminSession))
                .andExpect(status().isForbidden());
        assertThat(adminSession.isInvalid()).isFalse();
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void expiredValidWritesCannotPersistAnOrder(Entry entry) throws Exception {
        var browser = login(entry);
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM repair_orders WHERE student_id=?", Long.class, student.getId());
        clock.set(stamp(browser.session) + LIMIT);
        var request = entry == Entry.SSR
                ? post("/student/orders").param("location", "虚构地点").param("category", "OTHER").param("description", "到期写入必须被拒绝")
                : post("/api/v1/orders").contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                    .content("{\"location\":\"虚构地点\",\"category\":\"OTHER\",\"description\":\"到期写入必须被拒绝\"}");
        mvc.perform(request.session(browser.session).header("Origin", ORIGIN).header("X-CSRF-TOKEN", browser.token))
                .andExpect(status().is(entry == Entry.SSR ? 302 : 401));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_orders WHERE student_id=?", Long.class, student.getId())).isEqualTo(before);
    }

    @Test void securityAdaptersAreAfterContextBeforeCsrfAndNotServletRegistered() {
        for (String path : List.of("/student/orders", "/api/v1/me")) {
            var filters = security.getFilters(path);
            var names = filters.stream().map(f -> f.getClass().getSimpleName()).toList();
            int adapter = path.startsWith("/api") ? names.indexOf("ApiGate") : names.indexOf("SsrSessionLifecycleFilter");
            assertThat(adapter).isGreaterThan(names.indexOf("SecurityContextHolderFilter")).isLessThan(names.indexOf("CsrfFilter"));
            assertThat(names.stream().filter(n -> n.equals("ApiGate") || n.equals("SsrSessionLifecycleFilter")).count()).isEqualTo(1);
        }
        assertThat(context.getBeansOfType(SsrSessionLifecycleFilter.class)).isEmpty();
        assertThat(servlet.getFilterRegistrations().values()).noneMatch(f -> f.getClassName().contains("Lifecycle") || f.getClassName().contains("ApiGate"));
    }

    @Test void realContainerSessionsHave1800SecondIdleTimeout() throws Exception {
        realSession.set(null);
        var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + "/login")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(servlet.getSessionTimeout()).isEqualTo(30);
        assertThat(realSession.get()).isNotNull();
        assertThat(realSession.get().getMaxInactiveInterval()).isEqualTo(1800);
    }
}
