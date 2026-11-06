package com.fixcampus;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Tomcat requests: no MockMvc user, session or CSRF test post-processors. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemWorkflowIntegrationTest extends RealMySqlTestSupport {
    private static final Pattern CSRF = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private final String prefix = "sys_" + UUID.randomUUID().toString().substring(0, 8) + "_";
    private final String password = "System_" + UUID.randomUUID() + "!";
    private final Set<String> ownedNames = new LinkedHashSet<>();
    private final List<Session> sessions = new ArrayList<>();
    private final Map<String, User> actors = new LinkedHashMap<>();

    @BeforeAll
    void fixtures() {
        for (String key : List.of("student", "otherStudent", "worker", "otherWorker", "admin")) {
            Role role = key.toLowerCase().contains("student") ? Role.STUDENT
                    : key.toLowerCase().contains("worker") ? Role.WORKER : Role.ADMIN;
            String name = prefix + key.toLowerCase();
            ownedNames.add(name);
            actors.put(key, users.saveAndFlush(new User(name, passwords.encode(password),
                    "系统测试" + role.getLabel(), null, role, LocalDateTime.now(clock))));
        }
    }

    @AfterEach
    void closeSessionsAndRemoveOwnedOrders() {
        sessions.forEach(session -> session.client.close());
        sessions.clear();
        for (String name : ownedNames) {
            jdbc.update("DELETE FROM repair_orders WHERE student_id IN (SELECT id FROM users WHERE username=?)", name);
        }
    }

    @AfterAll
    void removeOwnedUsers() {
        for (String name : ownedNames) jdbc.update("DELETE FROM users WHERE username=?", name);
    }

    @Test
    void registeredStudentCompletesLifecycleThroughRealCookieSessions() throws Exception {
        Session student = session();
        String name = prefix + "registered";
        ownedNames.add(name);
        expect(student.submit("/register", "/register", Map.of("username", name, "password", password,
                "confirmPassword", password, "realName", "系统测试注册学生", "role", "ADMIN")), 303);
        User registered = users.findByUsername(name).orElseThrow();
        assertThat(registered.getRole()).isEqualTo(Role.STUDENT);
        assertThat(registered.getPassword()).isNotEqualTo(password);
        assertThat(passwords.matches(password, registered.getPassword())).isTrue();
        student.login(name, Role.STUDENT);
        Session admin = login("admin"), worker = login("worker");

        HttpResponse<String> invalid = student.submit("/student/orders/new", "/student/orders",
                Map.of("location", " ", "category", "OTHER", "description", " "));
        expect(invalid, 400);
        assertThat(invalid.body()).contains("请输入报修地点", "请输入问题描述");
        assertThat(countOwnedOrders()).isZero();

        String description = "甲".repeat(1998) + "\r\n🔧";
        long id = createOrder(student, description);
        String studentPath = "/student/orders/" + id, adminPath = "/admin/orders/" + id;
        String workerPath = "/worker/orders/" + id;
        assertState(id, "PENDING_ASSIGNMENT");
        assertThat(row(id).get("student_id")).isEqualTo(registered.getId());
        assertThat(row(id).get("worker_id")).isNull();
        assertThat(row(id).get("description")).isEqualTo("甲".repeat(1998) + "\n🔧");
        expect(student.submit(studentPath, studentPath + "/confirm", Map.of()), 409);

        Map<String, String> assignment = Map.of("workerId", actors.get("worker").getId().toString());
        expect(admin.submit(adminPath, adminPath + "/assign", assignment), 303);
        assertState(id, "PENDING_REPAIR");
        expect(admin.submit("/admin/orders", adminPath + "/assign", assignment), 409);
        expect(worker.submit(workerPath, workerPath + "/complete", Map.of("repairResult", "不能跳过开始维修")), 409);
        expect(worker.submit(workerPath, workerPath + "/start", Map.of()), 303);
        assertState(id, "IN_PROGRESS");
        expect(worker.submit(workerPath, workerPath + "/start", Map.of()), 409);
        expect(worker.submit(workerPath, workerPath + "/complete", Map.of("repairResult", "修".repeat(998) + "\r\n🔧")), 303);
        assertState(id, "PENDING_CONFIRMATION");
        assertThat(row(id).get("completed_at")).isNull();
        expect(student.submit(studentPath, studentPath + "/confirm", Map.of("completedAt", "2099-01-01T00:00:00")), 303);
        assertState(id, "COMPLETED");
        Map<String, Object> completed = row(id);
        assertThat(completed.get("completed_at")).isNotNull();
        assertThat(completed.get("repair_result")).isEqualTo("修".repeat(998) + "\n🔧");
        expect(student.submit(studentPath, studentPath + "/confirm", Map.of()), 409);
        expect(student.submit(studentPath, studentPath + "/cancel", Map.of()), 409);
        expect(worker.submit(workerPath, workerPath + "/complete", Map.of("repairResult", "不能覆盖")), 409);
        assertThat(row(id)).isEqualTo(completed);
        assertThat(expect(student.get(studentPath), 200).body()).contains("已完成", "完成时间");
        assertThat(expect(admin.get("/admin/orders?status=COMPLETED"), 200).body()).contains("系统测试地点");
        assertThat(expect(worker.get(workerPath), 200).body()).contains("已完成");
    }

    @Test
    void realSessionsEnforceRoleAndOwnershipBeforeReadingOrMutatingOrders() throws Exception {
        Session student = login("student"), stranger = login("otherStudent"), worker = login("worker");
        Session otherWorker = login("otherWorker"), admin = login("admin"), anonymous = session();
        long id = createOrder(student, "只有工单所有者和授权角色可以看到这段描述");
        String studentPath = "/student/orders/" + id, adminPath = "/admin/orders/" + id;
        String workerPath = "/worker/orders/" + id;
        Map<String, Object> pending = row(id);
        expect(anonymous.get(studentPath), 302);
        expect(student.get(adminPath), 403);
        expect(admin.get(studentPath), 403);
        expect(worker.get(adminPath), 403);
        for (String path : List.of(studentPath, "/student/orders/9223372036854775807")) {
            assertThat(expect(stranger.get(path), 404).body()).contains("工单不存在或无权访问")
                    .doesNotContain("只有工单所有者和授权角色");
            expect(stranger.submit("/student/orders", path + "/cancel",
                    Map.of("studentId", actors.get("student").getId().toString())), 404);
        }
        expect(student.submit("/student/orders", adminPath + "/assign",
                Map.of("workerId", actors.get("worker").getId().toString(), "role", "ADMIN")), 403);
        expect(admin.submit(adminPath, adminPath + "/assign",
                Map.of("workerId", actors.get("student").getId().toString())), 400);
        expect(worker.get(workerPath), 404);
        assertThat(row(id)).isEqualTo(pending);

        expect(admin.submit(adminPath, adminPath + "/assign",
                Map.of("workerId", actors.get("worker").getId().toString())), 303);
        Map<String, Object> assigned = row(id);
        assertThat(expect(otherWorker.get("/worker/orders"), 200).body()).doesNotContain("系统测试地点");
        expect(otherWorker.get(workerPath), 404);
        expect(otherWorker.submit("/worker/orders", workerPath + "/start", Map.of()), 404);
        expect(otherWorker.submit("/worker/orders", workerPath + "/complete", Map.of()), 404);
        expect(admin.submit("/admin/orders", workerPath + "/start", Map.of()), 403);
        expect(student.submit(studentPath, studentPath + "/cancel", Map.of()), 409);
        expect(worker.get(workerPath + "/start"), 405);
        assertThat(row(id)).isEqualTo(assigned);
        assertThat(expect(admin.get("/admin/orders?status=INVALID"), 400).body()).contains("请选择有效的工单状态");
    }

    @Test
    void csrfLogoutAndCancelledTerminalStateWorkAcrossIndependentRealSessions() throws Exception {
        Session anonymous = session();
        expect(anonymous.post("/login", Map.of()), 403);
        expect(anonymous.post("/register", Map.of()), 403);
        HttpResponse<String> failed = anonymous.submit("/login", "/login",
                Map.of("username", actors.get("student").getUsername(), "password", "Definitely_Wrong_123"));
        expect(failed, 302);
        assertThat(failed.headers().firstValue("Location").orElseThrow()).endsWith("/login?error");
        assertThat(expect(anonymous.get("/login?error"), 200).body()).contains("用户名或密码错误");
        expect(anonymous.get("/student/orders"), 302);

        Session first = login("student"), second = login("student"), admin = login("admin");
        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        long id = createOrder(first, "撤销与会话隔离测试");
        String detail = "/student/orders/" + id;
        Map<String, Object> pending = row(id);
        expect(first.post(detail + "/cancel", Map.of()), 403);
        expect(first.post(detail + "/cancel", Map.of("_csrf", "invalid-token")), 403);
        expect(first.post(detail + "/cancel", Map.of("_csrf", second.token(detail))), 403);
        assertThat(row(id)).isEqualTo(pending);
        expect(first.submit(detail, detail + "/cancel", Map.of()), 303);
        assertState(id, "CANCELLED");
        Map<String, Object> cancelled = row(id);
        expect(first.submit(detail, detail + "/cancel", Map.of()), 409);
        expect(first.submit(detail, detail + "/confirm", Map.of()), 409);
        expect(admin.submit("/admin/orders", "/admin/orders/" + id + "/assign",
                Map.of("workerId", actors.get("worker").getId().toString())), 409);
        assertThat(row(id)).isEqualTo(cancelled);

        String oldToken = first.token(detail);
        expect(first.post("/logout", Map.of()), 403);
        expect(first.get("/logout"), 405);
        expect(first.get(detail), 200);
        HttpResponse<String> logout = first.post("/logout", Map.of("_csrf", oldToken));
        expect(logout, 302);
        assertThat(logout.headers().firstValue("Location").orElseThrow()).endsWith("/login?logout");
        expect(first.get(detail), 302);
        expect(first.post(detail + "/confirm", Map.of("_csrf", oldToken)), 403);
        assertThat(expect(second.get(detail), 200).body()).contains("已取消");
        assertThat(row(id)).isEqualTo(cancelled);
    }

    private Session session() {
        Session session = new Session();
        sessions.add(session);
        return session;
    }

    private Session login(String key) throws Exception {
        Session session = session();
        session.login(actors.get(key).getUsername(), actors.get(key).getRole());
        return session;
    }

    private long createOrder(Session student, String description) throws Exception {
        HttpResponse<String> created = student.submit("/student/orders/new", "/student/orders",
                Map.of("location", "系统测试地点", "category", "OTHER", "description", description,
                        "status", "COMPLETED", "studentId", actors.get("otherStudent").getId().toString(),
                        "workerId", actors.get("otherWorker").getId().toString(), "completedAt", "2099-01-01T00:00:00"));
        expect(created, 303);
        String location = created.headers().firstValue("Location").orElseThrow();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private long countOwnedOrders() {
        return ownedNames.stream().mapToLong(name -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM repair_orders o JOIN users u ON o.student_id=u.id WHERE u.username=?", Long.class, name)).sum();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM repair_orders WHERE id=?", id);
    }

    private void assertState(long id, String state) {
        assertThat(row(id).get("status")).isEqualTo(state);
    }

    private static HttpResponse<String> expect(HttpResponse<String> response, int status) {
        // Keep bodies/cookies/tokens out of assertion output and shared reports.
        assertThat(response.statusCode()).as("HTTP %s %s", response.request().method(), response.uri().getPath()).isEqualTo(status);
        return response;
    }

    private class Session {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> get(String path) throws Exception {
            return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
            String body = fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .collect(Collectors.joining("&"));
            return client.send(request(path).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpResponse<String> submit(String tokenPage, String path, Map<String, String> fields) throws Exception {
            var parameters = new LinkedHashMap<>(fields);
            parameters.put("_csrf", token(tokenPage));
            return post(path, parameters);
        }

        String token(String path) throws Exception {
            var matcher = CSRF.matcher(expect(get(path), 200).body());
            assertThat(matcher.find()).as("CSRF input on %s", path).isTrue();
            return matcher.group(1);
        }

        void login(String username, Role role) throws Exception {
            String csrf = token("/login"), before = sessionId();
            HttpResponse<String> result = expect(post("/login", Map.of("username", username,
                    "password", password, "_csrf", csrf)), 302);
            assertThat(result.headers().firstValue("Location").orElseThrow()).endsWith(role.getHomePath());
            assertThat(sessionId()).isNotEmpty().isNotEqualTo(before);
            assertThat(result.headers().allValues("Set-Cookie").stream()
                    .anyMatch(value -> value.contains("JSESSIONID=") && value.contains("HttpOnly") && value.contains("SameSite=Lax"))).isTrue();
            expect(get(role.getHomePath()), 200);
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
                    .map(cookie -> cookie.getValue()).findFirst().orElse("");
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept-Language", "en-US");
        }

        private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    }
}
