package com.fixcampus.api.v1;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.repository.OrderAttachmentRepository;
import com.fixcampus.repository.OrderAttachmentRepository.Attachment;
import com.fixcampus.repository.RepairOrderRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.security.SessionLifecycle;
import com.fixcampus.service.AttachmentImages;
import com.fixcampus.service.AttachmentStorage;
import com.fixcampus.AttachmentFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.fixcampus.AttachmentFixtures.image;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = OrderAttachmentsApi.class, properties = {
        "fixcampus.web.origin=http://127.0.0.1:5173", "fixcampus.rate-limit.enabled=true",
        "fixcampus.rate-limit.write-requests=1", "fixcampus.attachments.max-file-bytes=1024",
        "fixcampus.attachments.max-total-bytes=1200"})
@ActiveProfiles("web-slice")
@Import({ApiSecurityConfig.class, OrderAttachments.class, AttachmentImages.class, AttachmentStorage.class,
        OrderAttachmentsApiTest.TransactionConfig.class})
class OrderAttachmentsApiTest {
    private static final String STUDENT = "/api/v1/orders/101/attachments";
    private static final String WORKER = "/api/v1/worker/orders/101/attachments";
    private static final String ADMIN = "/api/v1/admin/orders/101/attachments";
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static final LocalDateTime CREATED = LocalDateTime.parse("2026-11-26T14:30:00");
    @TempDir static Path root;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("fixcampus.attachments.storage-dir", () -> root.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired AttachmentImages images;
    @MockitoSpyBean AttachmentStorage storage;
    @MockitoBean(name = "apiAuthenticationManager") AuthenticationManager authentication;
    @MockitoBean SessionLifecycle lifecycle;
    @MockitoBean RepairOrderRepository orders;
    @MockitoBean OrderAttachmentRepository attachments;
    @MockitoBean Clock clock;

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionConfig {
        @Bean PlatformTransactionManager transactionManager() { return AttachmentFixtures.transactions(); }
    }

    private final User student = account(1L, Role.STUDENT);
    private final User worker = account(2L, Role.WORKER);
    private final User admin = account(3L, Role.ADMIN);
    private final RepairOrder order = new RepairOrder(student, "fixture", RepairCategory.OTHER, "fixture", CREATED);

    @BeforeEach
    void fixtures() {
        when(orders.findByIdForUpdate(101L)).thenReturn(Optional.of(order));
        when(orders.findForStudentById(101L, 1L)).thenReturn(Optional.of(order));
        when(orders.findForWorkerById(101L, 2L)).thenReturn(Optional.of(order));
        when(orders.findById(101L)).thenReturn(Optional.of(order));
        when(clock.instant()).thenReturn(Instant.parse("2026-11-26T06:30:00Z"));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void listsAndDownloadsWithTheViewersPrefixAndExactHeaders(Role role) throws Exception {
        var actor = switch (role) { case STUDENT -> student; case WORKER -> worker; case ADMIN -> admin; };
        String path = switch (role) { case STUDENT -> STUDENT; case WORKER -> WORKER; case ADMIN -> ADMIN; };
        var prepared = images.prepare(file());
        var key = storage.store(prepared, ignored -> false);
        var row = row(key, prepared.bytes().length, 1L, Role.STUDENT);
        if (role == Role.WORKER) {
            order.assignTo(worker);
            order.startRepair();
        }
        when(attachments.findByOrderId(101L)).thenReturn(List.of(row));
        when(attachments.findByIdAndOrderId(12L, 101L)).thenReturn(Optional.of(row));

        mvc.perform(get(path).with(user(principal(actor)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("101"))
                .andExpect(jsonPath("$.canUpload").value(role != Role.ADMIN))
                .andExpect(jsonPath("$.limits.maxFileBytes").value(1024))
                .andExpect(jsonPath("$.limits.maxWidth").value(4096))
                .andExpect(jsonPath("$.limits.maxHeight").value(4096))
                .andExpect(jsonPath("$.limits.maxPixels").value(16000000))
                .andExpect(jsonPath("$.limits.maxStudentAttachments").value(3))
                .andExpect(jsonPath("$.limits.maxWorkerAttachments").value(2))
                .andExpect(jsonPath("$.limits.allowedContentTypes", contains("image/png", "image/jpeg")))
                .andExpect(jsonPath("$.items[0].contentUrl").value(path + "/12/content"))
                .andExpect(jsonPath("$.items[0].canDelete").value(role == Role.STUDENT));
        mvc.perform(get(path + "/12/content").with(user(principal(actor)))).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, prepared.bytes().length))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"attachment-12.png\""))
                .andExpect(content().bytes(prepared.bytes()));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"STUDENT", "WORKER"})
    void uploadsOnlyOnTheMatchingWriteRoute(Role role) throws Exception {
        boolean studentUpload = role == Role.STUDENT;
        User actor = studentUpload ? student : worker;
        String path = studentUpload ? STUDENT : WORKER;
        if (!studentUpload) {
            order.assignTo(worker);
            order.startRepair();
        }
        when(attachments.insert(eq(101L), eq(actor.getId()), eq(role), eq("fixture.svg"), eq("image/png"),
                anyLong(), eq(3), eq(2), matches("[a-f0-9]{64}"), matches("[a-f0-9]{32}\\.png"), eq(CREATED)))
                .thenAnswer(call -> row(call.getArgument(9), call.getArgument(5), actor.getId(), role));
        upload(path, actor, file()).andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, path + "/12/content"))
                .andExpect(jsonPath("$.id").value("12"))
                .andExpect(jsonPath("$.orderId").value("101"))
                .andExpect(jsonPath("$.uploaderRole").value(role.name()))
                .andExpect(jsonPath("$.uploaderName").value("Fixture"))
                .andExpect(jsonPath("$.originalFilename").value("fixture.svg"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").isNumber())
                .andExpect(jsonPath("$.width").value(3))
                .andExpect(jsonPath("$.height").value(2))
                .andExpect(jsonPath("$.createdAt").value("2026-11-26T14:30:00+08:00"))
                .andExpect(jsonPath("$.canDelete").value(studentUpload))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.sha256").doesNotExist());
    }

    @Test
    void enforcesRolePrefixesAndObjectBoundariesIndependently() throws Exception {
        problem(mvc.perform(get(ADMIN).with(user(principal(student)))), 403, "ROLE_FORBIDDEN");
        problem(mvc.perform(get(STUDENT + "/12/content").with(user(principal(admin)))), 403, "ROLE_FORBIDDEN");
        problem(mvc.perform(get(WORKER).with(user(principal(student)))), 403, "ROLE_FORBIDDEN");
        problem(upload(STUDENT, worker, file()), 403, "ROLE_FORBIDDEN");
        problem(upload(WORKER, admin, file()), 403, "ROLE_FORBIDDEN");
        problem(remove(admin, STUDENT + "/12"), 403, "ROLE_FORBIDDEN");
        problem(remove(worker, WORKER + "/12"), 403, "ROLE_FORBIDDEN");
        problem(mvc.perform(get(ADMIN)), 401, "AUTH_REQUIRED");
        problem(mvc.perform(get(STUDENT + "/12/extra").with(user(principal(student)))), 403, "ROLE_FORBIDDEN");
        var visitor = account(4L, Role.STUDENT);
        problem(upload(STUDENT, visitor, file()), 404, "ORDER_NOT_FOUND");
        problem(remove(visitor, STUDENT + "/12"), 404, "ORDER_NOT_FOUND");
        verify(orders, never()).findByIdForUpdate(anyLong());
        problem(mvc.perform(get(WORKER + "/12/content").with(user(principal(account(5L, Role.WORKER))))),
                404, "ORDER_NOT_FOUND");
        problem(mvc.perform(get(ADMIN.replace("101", "102")).with(user(principal(admin)))), 404, "ORDER_NOT_FOUND");
        problem(mvc.perform(get(ADMIN + "/12/content").with(user(principal(admin)))), 404, "ATTACHMENT_NOT_FOUND");
        problem(remove(student, STUDENT + "/12"), 404, "ATTACHMENT_NOT_FOUND");
    }

    @Test
    void enforcesUploadStateRoleLimitsAndGlobalQuota() throws Exception {
        order.assignTo(worker);
        problem(upload(WORKER, worker, file()), 409, "ORDER_CONFLICT");
        when(attachments.countByOrderIdAndUploaderRole(101L, Role.STUDENT)).thenReturn(3L);
        problem(upload(STUDENT, student, file()), 409, "ATTACHMENT_LIMIT_EXCEEDED");
        when(attachments.sumSizeBytes()).thenReturn(1200L);
        problem(upload(WORKER, worker, file()), 409, "ORDER_CONFLICT");
        order.startRepair();
        problem(upload(WORKER, worker, file()), 409, "ATTACHMENT_QUOTA_EXCEEDED");
        when(attachments.countByOrderIdAndUploaderRole(101L, Role.WORKER)).thenReturn(2L);
        problem(upload(WORKER, worker, file()), 409, "ATTACHMENT_LIMIT_EXCEEDED");
        problem(upload(STUDENT, student, file()), 409, "ORDER_CONFLICT");
    }

    @Test
    void deletesOnlyTheStudentsOwnAttachmentWhileStillUnassigned() throws Exception {
        var prepared = images.prepare(file());
        var key = storage.store(prepared, ignored -> false);
        when(attachments.findByIdAndOrderId(12L, 101L)).thenReturn(Optional.of(row(key, 1, 2L, Role.WORKER)))
                .thenReturn(Optional.of(row(key, 1, 4L, Role.STUDENT))).thenReturn(Optional.of(row(key, 1, 1L, Role.STUDENT)));
        problem(remove(student, STUDENT + "/12"), 409, "ORDER_CONFLICT");
        problem(remove(student, STUDENT + "/12"), 409, "ORDER_CONFLICT");
        remove(student, STUDENT + "/12").andExpect(status().isNoContent());
        verify(attachments).delete(12L, 101L);
        assertThat(root.resolve(key)).doesNotExist();
        order.assignTo(worker);
        problem(remove(student, STUDENT + "/12"), 409, "ORDER_CONFLICT");
    }

    @Test
    void rejectsEmptyMissingExtraAndOversizedFilesAndMasksDiskFailures() throws Exception {
        problem(upload(STUDENT, student, new MockMultipartFile("file", new byte[0])), 400, "INVALID_ATTACHMENT");
        problem(upload(STUDENT, student, new MockMultipartFile("wrong", new byte[]{1})), 400, "INVALID_ATTACHMENT");
        problem(upload(STUDENT, student, file(), file()), 400, "INVALID_ATTACHMENT");
        problem(upload(STUDENT, student, file(), new MockMultipartFile("extra", new byte[]{1})),
                400, "INVALID_ATTACHMENT");
        problem(upload(STUDENT, student, new MockMultipartFile("file", new byte[1025])), 413, "PAYLOAD_TOO_LARGE");
        when(attachments.findByIdAndOrderId(12L, 101L)).thenReturn(Optional.of(row("a".repeat(32) + ".png", 1, 1L, Role.STUDENT)));
        problem(mvc.perform(get(STUDENT + "/12/content").with(user(principal(student)))), 503, "TEMPORARILY_UNAVAILABLE")
                .andExpect(jsonPath("$.message").value("附件存储暂不可用"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void contentRechecksMetadataAfterConcurrentDeletion(boolean resetDeletedOrder) throws Exception {
        var prepared = images.prepare(file());
        var key = storage.store(prepared, ignored -> false);
        var metadata = row(key, prepared.bytes().length, 1L, Role.STUDENT);
        when(attachments.findByIdAndOrderId(12L, 101L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return Files.exists(root.resolve(key)) ? Optional.of(metadata) : Optional.empty();
        });
        if (resetDeletedOrder) {
            when(orders.findForStudentById(101L, 1L)).thenAnswer(call ->
                    Files.exists(root.resolve(key)) ? Optional.of(order) : Optional.empty());
        }
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Files.delete(root.resolve(key));
            return call.callRealMethod();
        }).when(storage).read(key);

        problem(mvc.perform(get(STUDENT + "/12/content").with(user(principal(student)))),
                404, resetDeletedOrder ? "ORDER_NOT_FOUND" : "ATTACHMENT_NOT_FOUND");
        verify(orders, times(2)).findForStudentById(101L, 1L);
        verify(attachments, times(resetDeletedOrder ? 1 : 2)).findByIdAndOrderId(12L, 101L);
    }

    @Test
    void requiresOriginAndHeaderCsrfOnUploadAndDelete() throws Exception {
        problem(mvc.perform(multipart(STUDENT).file(file()).with(user(principal(student))).with(csrf().asHeader())),
                403, "ORIGIN_REJECTED");
        problem(mvc.perform(delete(STUDENT + "/12").with(user(principal(student))).with(csrf().asHeader())),
                403, "ORIGIN_REJECTED");
        problem(mvc.perform(multipart(STUDENT).file(file()).with(user(principal(student))).header("Origin", ORIGIN).with(csrf())),
                403, "CSRF_REJECTED");
        problem(mvc.perform(delete(STUDENT + "/12").with(user(principal(student))).header("Origin", ORIGIN)),
                403, "CSRF_REJECTED");
    }

    @Test
    void allAttachmentWritesUseTheOrderWriteBucket() throws Exception {
        var session = new MockHttpSession();
        problem(mvc.perform(delete(STUDENT + "/12").session(session).header("Origin", ORIGIN)
                .with(user(principal(student))).with(csrf().asHeader())), 404, "ATTACHMENT_NOT_FOUND");
        problem(mvc.perform(multipart(STUDENT).file(file()).session(session).header("Origin", ORIGIN)
                .with(user(principal(student))).with(csrf().asHeader())), 429, "RATE_LIMITED")
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        var workerSession = new MockHttpSession();
        for (int expected : new int[]{404, 429}) {
            problem(mvc.perform(multipart(WORKER).file(file()).session(workerSession).header("Origin", ORIGIN)
                            .with(user(principal(worker))).with(csrf().asHeader())),
                    expected, expected == 404 ? "ORDER_NOT_FOUND" : "RATE_LIMITED");
        }
    }

    private ResultActions upload(String path, User actor, MockMultipartFile... files) throws Exception {
        var request = multipart(path);
        for (var file : files) request.file(file);
        return mvc.perform(request.session(new MockHttpSession()).header("Origin", ORIGIN)
                .with(user(principal(actor))).with(csrf().asHeader()));
    }

    private ResultActions remove(User actor, String path) throws Exception {
        return mvc.perform(delete(path).session(new MockHttpSession()).header("Origin", ORIGIN)
                .with(user(principal(actor))).with(csrf().asHeader()));
    }

    private static ResultActions problem(ResultActions actual, int status, String code) throws Exception {
        return actual.andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static Attachment row(String key, long size, long uploaderId, Role role) {
        return new Attachment(12, 101, uploaderId, role, "Fixture", "fixture.svg", "image/png", size, 3, 2, key, CREATED);
    }

    private static MockMultipartFile file() throws Exception {
        return new MockMultipartFile("file", "../fixture.svg", "image/svg+xml", image("png", 3, 2));
    }

    private static CampusPrincipal principal(User actor) { return new CampusPrincipal(actor); }

    private static User account(long id, Role role) {
        var user = new User("fixture" + id, null, "Fixture", null, role, CREATED);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
