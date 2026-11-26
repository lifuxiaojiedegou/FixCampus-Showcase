package com.fixcampus;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixcampus.api.v1.ApiProblem;
import com.fixcampus.api.v1.OrderAttachments;
import com.fixcampus.config.DemoDataInitializer;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.repository.RepairOrderRepository;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.AttachmentImages;
import com.fixcampus.service.AttachmentStorage;
import com.fixcampus.service.DemoAccountService;
import com.fixcampus.service.DemoResetService;
import com.fixcampus.service.RepairOrderService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.fixcampus.AttachmentFixtures.image;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("web-slice")
@EnabledIfEnvironmentVariable(named = "FIXCAMPUS_DB_NAME", matches = "fixcampus_m421_[a-z0-9_]+_test")
@TestPropertySource(properties = {"fixcampus.web.origin=http://127.0.0.1:5173", "server.servlet.session.cookie.secure=false",
        "fixcampus.demo.enabled=true", "fixcampus.demo.reset.enabled=true", "fixcampus.demo.reset.cron=-"})
class OrderAttachmentIntegrationTest extends RealMySqlTestSupport {
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static final String STUDENT = "/api/v1/orders/";
    private static final String WORKER = "/api/v1/worker/orders/";
    private static final String ADMIN = "/api/v1/admin/orders/";
    @TempDir static Path root;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("fixcampus.attachments.storage-dir", () -> root.toString());
        registry.add("fixcampus.attachments.max-total-bytes", () -> {
            try {
                return 2L * image("png", 3, 2).length;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired UserRepository users;
    @Autowired RepairOrderRepository orders;
    @Autowired RepairOrderService workflow;
    @Autowired PasswordEncoder passwords;
    @Autowired AttachmentImages images;
    @Autowired OrderAttachments attachments;
    @Autowired DemoAccountService demoAccounts;
    @Autowired DemoResetService reset;
    @LocalServerPort int port;
    @MockitoBean(enforceOverride = true) DemoDataInitializer startupInitializer;
    @MockitoSpyBean AttachmentStorage storage;

    private final List<Long> ownedUsers = new ArrayList<>();
    private final List<Long> ownedOrders = new ArrayList<>();
    private final String password = UUID.randomUUID().toString();

    @AfterEach
    void removeFixtures() throws Exception {
        for (long id : ownedOrders) {
            jdbc.update("DELETE FROM notifications WHERE order_id = ?", id);
            var keys = jdbc.queryForList("SELECT storage_key FROM order_attachments WHERE order_id = ?", String.class, id);
            jdbc.update("DELETE FROM order_attachments WHERE order_id = ?", id);
            for (String key : keys) Files.deleteIfExists(root.resolve(key));
            jdbc.update("DELETE FROM repair_orders WHERE id = ?", id);
        }
        ownedUsers.forEach(id -> removeUserFixture(jdbc, id));
    }

    @Test
    void realMigrationsAndSchemaMatchTheAttachmentContract() {
        flyway.validate();
        assertThat(flyway.info().applied()).allSatisfy(migration -> assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS))
                .extracting(migration -> migration.getVersion().getVersion()).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(COLUMN_NAME, ':', COLLATION_NAME) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_attachments' AND COLLATION_NAME = 'ascii_bin'
                """, String.class)).containsExactlyInAnyOrder("content_type:ascii_bin", "sha256:ascii_bin", "storage_key:ascii_bin");
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(REFERENCED_TABLE_NAME, ':', DELETE_RULE) FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'order_attachments'
                """, String.class)).containsExactlyInAnyOrder("repair_orders:RESTRICT", "users:RESTRICT");
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(INDEX_NAME, ':', GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_attachments' AND INDEX_NAME IN
                    ('idx_order_attachments_order_created', 'idx_order_attachments_order_role')
                GROUP BY INDEX_NAME
                """, String.class)).containsExactlyInAnyOrder(
                "idx_order_attachments_order_created:order_id,created_at,id",
                "idx_order_attachments_order_role:order_id,uploader_role");
    }

    @Test
    void threeRolesUsePersistedAttachmentsAndRespectOwnershipAndDeleteState() throws Exception {
        var student = account(Role.STUDENT);
        var worker = account(Role.WORKER);
        var admin = account(Role.ADMIN);
        var otherStudent = account(Role.STUDENT);
        var otherWorker = account(Role.WORKER);
        long id = order(student);
        long second = order(student);
        String studentPath = path(STUDENT, id);
        String workerPath = path(WORKER, id);
        String adminPath = path(ADMIN, id);
        String deleted = upload(student, path(STUDENT, second));
        String deletedKey = key(deleted);
        mvc.perform(delete(path(STUDENT, second) + "/" + deleted).header("Origin", ORIGIN)
                .with(user(principal(student))).with(csrf().asHeader())).andExpect(status().isNoContent());
        assertThat(root.resolve(deletedKey)).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_attachments WHERE id = ?", Long.class, deleted)).isZero();

        String studentFile = upload(student, studentPath);
        assertThat(jdbc.queryForObject("SELECT sha256 FROM order_attachments WHERE id = ?", String.class, studentFile))
                .isEqualTo(images.prepare(file()).sha256());
        mvc.perform(get(adminPath).with(user(principal(student)))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
        mvc.perform(get(studentPath + "/" + studentFile + "/content").with(user(principal(otherStudent))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(get(path(STUDENT, second) + "/" + studentFile + "/content").with(user(principal(student))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
        mvc.perform(get(workerPath).with(user(principal(worker))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        workflow.assign(principal(admin), id, worker.getId());
        workflow.start(principal(worker), id);
        String workerFile = upload(worker, workerPath);
        mvc.perform(get(workerPath).with(user(principal(otherWorker))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        for (var viewer : Map.of(student, studentPath, worker, workerPath, admin, adminPath).entrySet()) {
            String visiblePath = viewer.getValue();
            var actor = viewer.getKey();
            mvc.perform(get(visiblePath).with(user(principal(actor)))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].id", contains(studentFile, workerFile)))
                    .andExpect(jsonPath("$.items[0].canDelete").value(false))
                    .andExpect(jsonPath("$.items[1].uploaderRole").value("WORKER"))
                    .andExpect(jsonPath("$.items[1].contentUrl").value(visiblePath + "/" + workerFile + "/content"));
            mvc.perform(get(visiblePath + "/" + workerFile + "/content").with(user(principal(actor))))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(content().bytes(images.prepare(file()).bytes()));
        }
        mvc.perform(delete(studentPath + "/" + studentFile).header("Origin", ORIGIN)
                .with(user(principal(student))).with(csrf().asHeader())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_CONFLICT"));
        uploadRequest(student, path(STUDENT, second)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_QUOTA_EXCEEDED"));
    }

    @Test
    void concurrentUploadsAcrossOrdersRespectTheGlobalQuota() throws Exception {
        var student = account(Role.STUDENT);
        var actor = principal(student);
        var source = images.prepare(file());
        var orderIds = List.of(order(student), order(student), order(student));
        var start = new CyclicBarrier(orderIds.size());
        try (var pool = Executors.newFixedThreadPool(orderIds.size())) {
            var results = orderIds.stream().map(id -> pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    attachments.upload(actor, id, source);
                    return "CREATED";
                } catch (ApiProblem rejected) {
                    return rejected.code();
                }
            })).toList();
            var outcomes = new ArrayList<String>();
            for (var result : results) outcomes.add(result.get(15, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "CREATED", "ATTACHMENT_QUOTA_EXCEEDED");
        }
        assertThat(jdbc.queryForObject("SELECT SUM(size_bytes) FROM order_attachments", Long.class))
                .isEqualTo(source.bytes().length * 2L);
    }

    @Test
    void rollingBackUploadRemovesTheFileAndRollingBackDeletePreservesIt() throws Exception {
        var student = account(Role.STUDENT);
        var actor = principal(student);
        long orderId = order(student);
        var prepared = images.prepare(file());
        var rolledBack = new AtomicReference<Path>();
        var failure = new IllegalStateException("Forced pre-commit failure");
        doAnswer(call -> {
            String key = (String) call.callRealMethod();
            rolledBack.set(root.resolve(key));
            assertThat(rolledBack.get()).exists();
            failBeforeCommit(failure);
            return key;
        }).doCallRealMethod().when(storage).store(eq(prepared), any());
        assertThatThrownBy(() -> attachments.upload(actor, orderId, prepared)).isSameAs(failure);
        assertThat(rolledBack.get()).doesNotExist();
        assertThat(attachments.list(actor, orderId).items()).isEmpty();
        String committed = attachments.upload(actor, orderId, prepared).id();
        String committedKey = key(committed);
        Path disk = root.resolve(committedKey);
        doAnswer(call -> {
            call.callRealMethod();
            assertThat(disk).exists();
            failBeforeCommit(failure);
            return null;
        }).when(storage).deleteAfterCommit(committedKey);
        assertThatThrownBy(() -> attachments.delete(actor, orderId, Long.parseLong(committed))).isSameAs(failure);
        assertThat(disk).exists();
        assertThat(attachments.list(actor, orderId).items()).extracting(OrderAttachments.OrderAttachmentItem::id)
                .containsExactly(committed);
    }

    private static void failBeforeCommit(RuntimeException failure) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { throw failure; }
        });
    }

    @Test
    void demoResetClearsRowsAndManagedFilesOnCommit() throws Exception {
        assertThat(orders.count()).as("reset test requires the isolated schema with no unrelated orders").isZero();
        var names = List.of("student01", "worker01", "admin01").stream().filter(name -> !users.existsByUsername(name)).toList();
        demoAccounts.initialize(Map.of(Role.STUDENT, UUID.randomUUID().toString(), Role.WORKER, UUID.randomUUID().toString(),
                Role.ADMIN, UUID.randomUUID().toString()));
        names.forEach(name -> ownedUsers.add(users.findByUsername(name).orElseThrow().getId()));
        var student = account(Role.STUDENT);
        String id = upload(student, path(STUDENT, order(student)));
        Path disk = root.resolve(key(id));
        Path orphan = Files.write(root.resolve("e".repeat(32) + ".jpg"), new byte[]{1});
        assertThat(disk).exists();

        reset.resetDemoData();
        ownedOrders.addAll(jdbc.queryForList("SELECT id FROM repair_orders", Long.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_attachments", Long.class)).isZero();
        assertThat(root).isDirectory();
        assertThat(disk).doesNotExist();
        assertThat(orphan).doesNotExist();
    }

    @Test
    void servletRejectsOversizedAndMalformedMultipartWithProblemJson() throws Exception {
        var student = account(Role.STUDENT);
        String path = path(STUDENT, order(student));
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String csrf = httpCsrf(client);
        String login = mapper.writeValueAsString(Map.of("username", student.getUsername(), "password", password));
        var auth = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(auth.statusCode()).isEqualTo(200);
        csrf = httpCsrf(client);
        String boundary = "AttachmentIntegrationBoundary";
        var head = HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\n\r\n");
        var oversized = HttpRequest.BodyPublishers.concat(head,
                HttpRequest.BodyPublishers.ofByteArray(new byte[3 * 1024 * 1024 + 1]),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n", StandardCharsets.US_ASCII));
        var largeResponse = postMultipart(client, path, csrf, "multipart/form-data; boundary=" + boundary, oversized);
        assertProblem(largeResponse, 413, "PAYLOAD_TOO_LARGE");
        var malformed = postMultipart(client, path, csrf, "multipart/form-data", HttpRequest.BodyPublishers.ofString("bad"));
        assertProblem(malformed, 400, "INVALID_ATTACHMENT");
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    private HttpResponse<String> postMultipart(HttpClient client, String path, String csrf,
                                               String type, HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", csrf).header("Content-Type", type).POST(body).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String httpCsrf(HttpClient client) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body()).path("token").asText();
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private String upload(User actor, String path) throws Exception {
        var response = uploadRequest(actor, path).andExpect(status().isCreated()).andReturn().getResponse();
        String id = mapper.readTree(response.getContentAsByteArray()).path("id").asText();
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo(path + "/" + id + "/content");
        return id;
    }

    private ResultActions uploadRequest(User actor, String path) throws Exception {
        return mvc.perform(multipart(path).file(file()).with(user(principal(actor))).header("Origin", ORIGIN)
                .with(csrf().asHeader()));
    }

    private String key(String id) {
        return jdbc.queryForObject("SELECT storage_key FROM order_attachments WHERE id = ?", String.class, id);
    }

    private static String path(String prefix, long id) { return prefix + id + "/attachments"; }

    private static CampusPrincipal principal(User actor) { return new CampusPrincipal(actor); }

    private static MockMultipartFile file() throws Exception {
        return new MockMultipartFile("file", "fixture.txt", "text/plain", image("png", 3, 2));
    }

    private User account(Role role) {
        String username = "attach_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var user = users.saveAndFlush(new User(username, passwords.encode(password), "附件测试", null, role, LocalDateTime.now()));
        ownedUsers.add(user.getId());
        return user;
    }

    private long order(User student) {
        long id = orders.saveAndFlush(new RepairOrder(student, "附件测试", RepairCategory.OTHER, "图片", LocalDateTime.now())).getId();
        ownedOrders.add(id);
        return id;
    }
}
