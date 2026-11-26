package com.fixcampus;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixcampus.api.v1.ApiProblem;
import com.fixcampus.api.v1.StudentOrders;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.context.ActiveProfiles("web-slice")
@TestPropertySource(properties = "server.servlet.session.cookie.secure=false")
class WebStudentSliceIntegrationTest extends RealMySqlTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired StudentOrders orders;
    @Autowired PlatformTransactionManager transactions;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    private CampusPrincipal student, other, admin;
    private final String password = UUID.randomUUID().toString();
    private static final String ORIGIN = "http://127.0.0.1:5173";
    @BeforeAll void fixtures() {
        String prefix = "api" + UUID.randomUUID().toString().substring(0, 8);
        student = fixture(prefix + "s", Role.STUDENT); other = fixture(prefix + "o", Role.STUDENT); admin = fixture(prefix + "a", Role.ADMIN);
    }
    CampusPrincipal fixture(String name, Role role) {
        var user = users.saveAndFlush(new User(name, passwords.encode(password), "虚构切片测试", null, role, LocalDateTime.now()));
        var principal = new CampusPrincipal(user); principal.eraseCredentials(); return principal;
    }
    @AfterEach void clean() {
        for (var actor : java.util.List.of(student, other, admin)) {
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", actor.getId());
            removeStudentOrderFixtures(jdbc, actor.getId());
        }
    }
    @AfterAll void usersCleanup() {
        clean();
        java.util.List.of(student, other, admin).forEach(actor -> removeUserFixture(jdbc, actor.getId()));
    }
    record Browser(MockHttpSession session, String token) { }
    Browser csrf(MockHttpSession session) throws Exception {
        var request = get("/api/v1/auth/csrf"); if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return new Browser((MockHttpSession) result.getRequest().getSession(false), json(result).get("token").asText());
    }
    JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()); }
    Browser login() throws Exception {
        var before = csrf(null);
        var result = mvc.perform(post("/api/v1/auth/login").session(before.session).header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", before.token).contentType("application/json")
                .content(mapper.writeValueAsString(java.util.Map.of("username", student.getUsername(), "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("虚构切片测试")).andReturn();
        return csrf((MockHttpSession) result.getRequest().getSession(false));
    }
    String body(String description) throws Exception { return mapper.writeValueAsString(java.util.Map.of("location", "虚构 A101", "category", "OTHER", "description", description)); }
    MvcResult create(Browser browser, String key, String content) throws Exception {
        return mvc.perform(post("/api/v1/orders").session(browser.session).header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", browser.token).header("Idempotency-Key", key).contentType("application/json").content(content)).andReturn();
    }
    RepairOrderForm form(String description) {
        var f = new RepairOrderForm(); f.setLocation("虚构 A101"); f.setCategory(RepairCategory.OTHER); f.setDescription(description); return f;
    }
    @Test void authenticationRotatesSessionRefreshesCsrfAndLogsOut() throws Exception {
        var before = csrf(null); String oldId = before.session.getId();
        mvc.perform(post("/api/v1/auth/login").session(before.session).header("Origin", ORIGIN).contentType("application/json")
                .content("{} ")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post("/api/v1/auth/login").session(before.session).header("Origin", ORIGIN).header("X-CSRF-TOKEN", before.token)
                .contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("username",student.getUsername(),"password",password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(student.getId().toString()));
        assertThat(before.session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/v1/me").session(before.session)).andExpect(status().isOk()).andExpect(jsonPath("$.password").doesNotExist());
        assertThat(create(before, UUID.randomUUID().toString(), body("旧 token")).getResponse().getStatus()).isEqualTo(403);
        var after = csrf(before.session);
        mvc.perform(post("/api/v1/auth/logout").session(after.session).header("Origin",ORIGIN).header("X-CSRF-TOKEN",after.token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
    @Test void anonymousRolesOriginsAndBadCredentialsAreDistinct() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/api/v1/orders").with(user(admin))).andExpect(status().isForbidden());
        var b = csrf(null);
        mvc.perform(post("/api/v1/auth/login").session(b.session).header("Origin","https://example.invalid")
                .header("X-CSRF-TOKEN",b.token).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(post("/api/v1/auth/login").session(b.session).header("Origin",ORIGIN)
                .header("X-CSRF-TOKEN",b.token).contentType("application/json").content("{\"username\":\"missing_user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
    @Test void persistedCreationReplaysOriginalResultAndDetailsRespectOwnership() throws Exception {
        var b = login(); String key = UUID.randomUUID().toString();
        var first = create(b, key, body("真实持久化测试 😀")); assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String id = json(first).get("id").asText();
        assertThat(create(b,key,body("真实持久化测试 😀")).getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        mvc.perform(get("/api/v1/orders/"+id).session(b.session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.history.incomplete").value(false)).andExpect(jsonPath("$.history.entries[0].action").value("SUBMITTED"))
                .andExpect(jsonPath("$.viewRole").doesNotExist());
        mvc.perform(get("/api/v1/orders/"+id).with(user(other))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id=?",Long.class,id)).isEqualTo(1);
        assertThat(create(b,key,body("不同内容")).getResponse().getStatus()).isEqualTo(409);
        var refreshed = csrf(b.session);
        mvc.perform(get("/api/v1/orders/"+id).session(refreshed.session)).andExpect(status().isOk());
    }
    @Test void concurrentSameKeyCreatesExactlyOneOrderAndEvent() throws Exception {
        String key = UUID.randomUUID().toString(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = java.util.stream.IntStream.range(0,6).mapToObj(i -> pool.submit(() -> {
                start.await(); return orders.create(student,form("并发"),key);
            })).toList(); start.countDown();
            var ids = new java.util.HashSet<String>(); for (var task : tasks) ids.add(task.get(20,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_orders WHERE student_id=?",Long.class,student.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id=?",Long.class,ids.iterator().next())).isEqualTo(1);
        }
    }
    @Test void responseLossRecoveryAndDifferentActorsKeepSeparateKeys() {
        String key = UUID.randomUUID().toString(); String original = orders.create(student,form("响应丢失"),key);
        assertThat(orders.create(student,form("响应丢失"),key)).isEqualTo(original);
        assertThat(orders.create(other,form("响应丢失"),key)).isNotEqualTo(original);
        assertThatThrownBy(() -> orders.create(student,form("修改"),key)).isInstanceOf(ApiProblem.class);
    }
    @Test void replayRechecksCurrentStudentPermissionBeforeReadingStoredResult() {
        String key = UUID.randomUUID().toString();
        orders.create(student, form("回放权限"), key);
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", student.getId());
        try {
            assertThatThrownBy(() -> orders.create(student, form("回放权限"), key))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            var changedPrincipal = new CampusPrincipal(users.findById(student.getId()).orElseThrow());
            assertThatThrownBy(() -> orders.create(changedPrincipal, form("回放权限"), key))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally { jdbc.update("UPDATE users SET role='STUDENT' WHERE id=?", student.getId()); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_orders WHERE student_id=?", Long.class, student.getId())).isEqualTo(1);
        assertThatThrownBy(() -> orders.create(null, form("回放权限"), key))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void transactionRollbackIncludesOrderEventAndIdempotency() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> {
            orders.create(student,form("回滚"),UUID.randomUUID().toString()); throw new IllegalStateException("deliberate test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_orders WHERE student_id=?",Long.class,student.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_requests WHERE actor_id=?",Long.class,student.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_events WHERE actor_id=?",Long.class,student.getId())).isZero();
    }
    @Test void paginationValidationAndUnicodeBoundaries() throws Exception {
        var b = login();
        assertThat(create(b,UUID.randomUUID().toString(),body("😀".repeat(2000))).getResponse().getStatus()).isEqualTo(201);
        assertThat(create(b,UUID.randomUUID().toString(),body("😀".repeat(2001))).getResponse().getStatus()).isEqualTo(400);
        assertThat(create(b,UUID.randomUUID().toString(),"{\"studentId\":\"123\",\"location\":\"x\",\"category\":\"OTHER\",\"description\":\"x\"}").getResponse().getStatus()).isEqualTo(400);
        orders.create(other,form("其他学生"),UUID.randomUUID().toString());
        orders.create(student,form("第二单"),UUID.randomUUID().toString());
        mvc.perform(get("/api/v1/orders?page=0&size=1").session(b.session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/orders?size=51").session(b.session)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders?sort=id,desc").session(b.session)).andExpect(status().isBadRequest());
    }
    @Test void absoluteSessionExpiryAndSsrRemainAvailable() throws Exception {
        var b = login(); b.session.setAttribute("FC_AUTH_AT",System.currentTimeMillis()-8*60*60*1000L);
        mvc.perform(post("/api/v1/orders").session(b.session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/student/orders")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/student/orders").with(user(student))).andExpect(status().isOk());
    }
    @Test void realHttpCookieSessionPersistsAcrossSeparateRequests() throws Exception {
        var cookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
        var client = java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build();
        var base = "http://127.0.0.1:" + port;
        var csrfResponse = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/auth/csrf")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(csrfResponse.statusCode()).isEqualTo(200);
        assertThat(csrfResponse.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly", "SameSite=Lax");
        String initialSession = cookies.getCookieStore().getCookies().getFirst().getValue();
        String token = mapper.readTree(csrfResponse.body()).get("token").asText();
        var loginResponse = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/auth/login"))
                .header("Origin",ORIGIN).header("X-CSRF-TOKEN",token).header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(java.util.Map.of("username",student.getUsername(),"password",password)))).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(loginResponse.statusCode()).isEqualTo(200);
        assertThat(cookies.getCookieStore().getCookies().getFirst().getValue()).isNotEqualTo(initialSession);
        var refresh = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/auth/csrf")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        token = mapper.readTree(refresh.body()).get("token").asText();
        var created = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/orders"))
                .header("Origin",ORIGIN).header("X-CSRF-TOKEN",token).header("Idempotency-Key",UUID.randomUUID().toString())
                .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body("真实 HTTP Cookie 链路"))).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        String id=mapper.readTree(created.body()).get("id").asText();
        var detail=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/orders/"+id)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(detail.body()).get("description").asText()).isEqualTo("真实 HTTP Cookie 链路");
    }
    @Test void wrongMediaTypeIsAJsonProblem() throws Exception {
        var b=login();
        mvc.perform(post("/api/v1/orders").session(b.session).header("Origin",ORIGIN).header("X-CSRF-TOKEN",b.token)
                .contentType("text/plain").content("invalid"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
