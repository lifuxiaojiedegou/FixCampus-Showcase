package com.fixcampus;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.sql.DataSource;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.exception.OrderConflictException;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.view.OrderDetail;
import com.fixcampus.view.OrderSummary;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StudentRepairIntegrationTest extends RealMySqlTestSupport {
    @Autowired MockMvc mvc;
    @Autowired RepairOrderService service;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    private final List<Long> ownedUserIds = new ArrayList<>();
    private CampusPrincipal student, otherStudent, worker, admin;

    @BeforeAll
    void fixtures() {
        String prefix = "or" + UUID.randomUUID().toString().substring(0, 8);
        student = fixture(prefix + "_s1", Role.STUDENT);
        otherStudent = fixture(prefix + "_s2", Role.STUDENT);
        worker = fixture(prefix + "_w", Role.WORKER);
        admin = fixture(prefix + "_a", Role.ADMIN);
    }

    private CampusPrincipal fixture(String name, Role role) {
        var saved = users.saveAndFlush(new User(name, passwords.encode(UUID.randomUUID().toString()),
                "测试" + role.getLabel(), "private-contact-marker", role, LocalDateTime.now(clock)));
        ownedUserIds.add(saved.getId());
        var principal = new CampusPrincipal(saved);
        principal.eraseCredentials();
        return principal;
    }

    @AfterEach
    void cleanOwnedOrders() {
        for (Long id : ownedUserIds) { jdbc.update("DELETE FROM repair_orders WHERE student_id = ?", id); }
    }

    @AfterAll
    void cleanOwnedUsers() {
        cleanOwnedOrders();
        for (Long id : ownedUserIds) { jdbc.update("DELETE FROM users WHERE id = ?", id); }
    }

    private RepairOrderForm form() {
        var form = new RepairOrderForm();
        form.setLocation("  3号宿舍502室  ");
        form.setCategory(RepairCategory.AIR_CONDITIONING);
        form.setDescription("  空调不制冷\n已检查电源。  ");
        return form;
    }

    private MockHttpServletRequestBuilder request(CampusPrincipal actor) {
        return post("/student/orders").with(user(actor)).with(csrf())
                .param("location", "  3号宿舍502室  ").param("category", "AIR_CONDITIONING")
                .param("description", "  空调不制冷\n已检查电源。  ");
    }

    private long countOrders() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM repair_orders WHERE student_id = ?", Long.class, student.getId());
    }

    @Test
    void emptyListAndNewFormRenderFiveCategories() throws Exception {
        mvc.perform(get("/student/orders").with(user(student)))
                .andExpect(status().isOk()).andExpect(model().attribute("orders", List.of()))
                .andExpect(content().string(containsString("还没有报修记录")));
        mvc.perform(get("/student/orders/new").with(user(student)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
        for (RepairCategory category : RepairCategory.values()) {
            mvc.perform(get("/student/orders/new").with(user(student)))
                    .andExpect(content().string(containsString(category.getLabel())));
        }
    }

    @Test
    void createUsesSessionIdentityAndSystemFieldsAndFlashIsOnceOnly() throws Exception {
        var before = LocalDateTime.now(clock).minusSeconds(1);
        var result = mvc.perform(request(student).param("studentId", otherStudent.getId().toString())
                        .param("student.id", otherStudent.getId().toString()).param("workerId", worker.getId().toString())
                        .param("worker.id", worker.getId().toString()).param("status", "COMPLETED")
                        .param("createdAt", "2000-01-01T00:00:00").param("completedAt", "2000-01-01T00:00:00")
                        .param("repairResult", "forged-result").param("id", "1"))
                .andExpect(status().isSeeOther()).andExpect(flash().attributeExists("successMessage")).andReturn();
        String path = result.getResponse().getRedirectedUrl();
        long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
        var saved = jdbc.queryForMap("SELECT * FROM repair_orders WHERE id = ?", id);
        assertThat(saved.get("student_id")).isEqualTo(student.getId());
        assertThat(saved.get("worker_id")).isNull();
        assertThat(saved.get("completed_at")).isNull();
        assertThat(saved.get("repair_result")).isNull();
        var detail = service.getForStudent(student, id);
        assertThat(detail.status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
        assertThat(detail.createdAt()).isAfter(before);
        assertThat(detail.location()).isEqualTo("3号宿舍502室");
        assertThat(detail.description()).isEqualTo("空调不制冷\n已检查电源。");
        mvc.perform(get(path).with(user(student)).session((org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("报修已提交")));
        mvc.perform(get(path).with(user(student))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("报修已提交，请等待分配维修人员。"))));
        assertThat(countOrders()).isEqualTo(1);
    }

    static Stream<Arguments> invalidForms() {
        return Stream.of(Arguments.of("location", "", "location"), Arguments.of("location", "\u2003", "location"),
                Arguments.of("location", "😀".repeat(121), "location"), Arguments.of("description", " ", "description"),
                Arguments.of("description", "😀".repeat(2001), "description"), Arguments.of("category", "", "category"),
                Arguments.of("category", "INVALID_CATEGORY", "category"));
    }

    @ParameterizedTest @MethodSource("invalidForms")
    void invalidInputsReturn400AndNeverCreateOrder(String field, String value, String errorField) throws Exception {
        // Replace rather than submit two competing values for the same field.
        var parameters = new org.springframework.util.LinkedMultiValueMap<String, String>();
        parameters.set("location", "3号宿舍502室"); parameters.set("category", "OTHER"); parameters.set("description", "门锁故障");
        parameters.set(field, value);
        mvc.perform(post("/student/orders").with(user(student)).with(csrf()).params(parameters))
                .andExpect(status().isBadRequest()).andExpect(view().name("student/order-form"))
                .andExpect(model().attributeHasFieldErrors("repairOrderForm", errorField))
                .andExpect(content().string(not(containsString("Failed to convert"))));
        assertThat(countOrders()).isZero();
    }

    @ParameterizedTest @EnumSource(RepairCategory.class)
    void allFiveApprovedCategoriesArePersistedAsStableCodes(RepairCategory category) {
        var form = form(); form.setCategory(category);
        Long id = service.createForStudent(student, form);
        assertThat(jdbc.queryForObject("SELECT category FROM repair_orders WHERE id = ?", String.class, id)).isEqualTo(category.name());
    }

    @ParameterizedTest @ValueSource(strings={"\n", "\r\n", "\r"})
    void browserLineEndingsShareDescriptionBoundaryAndPersistLf(String lineEnding) throws Exception {
        String exact = "甲".repeat(1998) + lineEnding + "🔧";
        mvc.perform(post("/student/orders").with(user(student)).with(csrf()).param("location", "换行边界测试")
                        .param("category", "OTHER").param("description", exact + "多"))
                .andExpect(status().isBadRequest()).andExpect(model().attributeHasFieldErrors("repairOrderForm", "description"));
        assertThat(countOrders()).isZero();
        var response = mvc.perform(post("/student/orders").with(user(student)).with(csrf()).param("location", "换行边界测试")
                        .param("category", "OTHER").param("description", exact))
                .andExpect(status().isSeeOther()).andReturn();
        String path = response.getResponse().getRedirectedUrl();
        Long id = Long.valueOf(path.substring(path.lastIndexOf('/') + 1));
        assertThat(service.getForStudent(student, id).description()).isEqualTo("甲".repeat(1998) + "\n🔧");
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(description) FROM repair_orders WHERE id=?", Integer.class, id)).isEqualTo(2000);
    }

    @Test
    void unicodeCodePointBoundariesWorkInFormAndRealMySql() throws Exception {
        var result = mvc.perform(post("/student/orders").with(user(student)).with(csrf())
                .param("location", "😀".repeat(120)).param("category", "NETWORK")
                .param("description", "😀".repeat(2000))).andExpect(status().isSeeOther()).andReturn();
        var lengths = jdbc.queryForMap("SELECT CHAR_LENGTH(location) l, CHAR_LENGTH(description) d FROM repair_orders WHERE student_id = ?", student.getId());
        assertThat(((Number) lengths.get("l")).intValue()).isEqualTo(120);
        assertThat(((Number) lengths.get("d")).intValue()).isEqualTo(2000);
        assertThat(result.getResponse().getRedirectedUrl()).startsWith("/student/orders/");
    }

    @Test
    void serviceAlsoValidatesInputWhenCalledWithoutController() {
        var invalid = form(); invalid.setLocation(" ");
        assertThatThrownBy(() -> service.createForStudent(student, invalid)).isInstanceOf(ConstraintViolationException.class);
        assertThat(countOrders()).isZero();
    }

    @Test
    void listContainsOnlyOwnerIncludesHistoryAndHasStableNewestFirstOrder() throws Exception {
        Long first = service.createForStudent(student, form());
        Long second = service.createForStudent(student, form());
        Long newest = service.createForStudent(student, form());
        Long foreign = service.createForStudent(otherStudent, form());
        String tie = "2026-10-01 12:00:00";
        jdbc.update("UPDATE repair_orders SET created_at = ? WHERE id IN (?, ?)", tie, first, second);
        jdbc.update("UPDATE repair_orders SET created_at = '2026-10-02 12:00:00', status='COMPLETED', worker_id=?, completed_at='2026-10-03 12:00:00' WHERE id=?", worker.getId(), newest);
        service.cancel(student, first);
        assertThat(service.listForStudent(student)).extracting(OrderSummary::id).containsExactly(newest, second, first).doesNotContain(foreign);
        mvc.perform(get("/student/orders").with(user(student)).param("studentId", otherStudent.getId().toString()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("已完成")))
                .andExpect(content().string(containsString("已取消")))
                .andExpect(content().string(not(containsString("/student/orders/" + foreign + "\""))));
    }

    @Test
    void ownDetailEscapesHtmlAndDoesNotExposeWorkerContactsOrHashes() throws Exception {
        var form = form(); form.setLocation("<b>测试地点</b>"); form.setDescription("<script>alert('xss')</script>\n第二行");
        Long id = service.createForStudent(student, form);
        jdbc.update("UPDATE repair_orders SET status='PENDING_CONFIRMATION', worker_id=?, repair_result=? WHERE id=?", worker.getId(), "<img src=x onerror=alert(1)>", id);
        mvc.perform(get("/student/orders/" + id).with(user(student)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("&lt;img")))
                .andExpect(content().string(containsString(worker.getRealName())))
                .andExpect(content().string(not(containsString("private-contact-marker"))))
                .andExpect(content().string(not(containsString("{pbkdf2"))))
                .andExpect(content().string(not(containsString("<script>alert"))))
                .andExpect(content().string(not(containsString("确认撤销"))))
                .andExpect(content().string(containsString("/student/orders/" + id + "/confirm")))
                .andExpect(content().string(containsString(">确认完成</button>")));
        assertThat(OrderDetail.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("student", "worker", "password", "phone");
    }

    @Test
    void foreignAndMissingOrdersShare404AndNeverLeakDetailsOrMutate() throws Exception {
        var form = form(); form.setLocation("foreign-secret-location");
        Long id = service.createForStudent(otherStudent, form);
        for (long target : new long[]{id, Long.MAX_VALUE, 0, -1}) {
            mvc.perform(get("/student/orders/" + target).with(user(student)))
                    .andExpect(status().isNotFound()).andExpect(content().string(containsString("工单不存在或无权访问")))
                    .andExpect(content().string(not(containsString("foreign-secret-location"))));
            mvc.perform(post("/student/orders/" + target + "/cancel").with(user(student)).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        assertThat(service.getForStudent(otherStudent, id).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
    }

    @ParameterizedTest @EnumSource(value=Role.class, names={"WORKER", "ADMIN"})
    void otherRolesAreBlockedAtHttpAndServiceBoundaries(Role role) throws Exception {
        var actor = role == Role.WORKER ? worker : admin;
        Long id = service.createForStudent(student, form());
        for (String path : new String[]{"/student/orders", "/student/orders/new", "/student/orders/" + id}) {
            mvc.perform(get(path).with(user(actor))).andExpect(status().isForbidden());
        }
        mvc.perform(request(actor)).andExpect(status().isForbidden());
        mvc.perform(post("/student/orders/" + id + "/cancel").with(user(actor)).with(csrf())).andExpect(status().isForbidden());
        assertThatThrownBy(() -> service.createForStudent(actor, form())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listForStudent(actor)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getForStudent(actor, id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.cancel(actor, id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anonymousAndCsrfFailuresCannotWriteAndGetCannotCancel() throws Exception {
        Long id = service.createForStudent(student, form());
        mvc.perform(get("/student/orders/new")).andExpect(status().isFound());
        mvc.perform(post("/student/orders").with(csrf())).andExpect(status().isFound());
        mvc.perform(post("/student/orders/" + id + "/cancel").with(csrf())).andExpect(status().isFound());
        mvc.perform(post("/student/orders").with(user(student))).andExpect(status().isForbidden());
        mvc.perform(post("/student/orders/" + id + "/cancel").with(user(student))).andExpect(status().isForbidden());
        mvc.perform(post("/student/orders/" + id + "/cancel").with(user(student)).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        mvc.perform(get("/student/orders/" + id + "/cancel").with(user(student))).andExpect(status().isMethodNotAllowed());
        assertThat(service.getForStudent(student, id).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    void cancelKeepsRecordAndIgnoresForgedValuesAndRepeatedCancelConflicts() throws Exception {
        Long id = service.createForStudent(student, form());
        var before = service.getForStudent(student, id);
        mvc.perform(post("/student/orders/" + id + "/cancel").with(user(student)).with(csrf())
                        .param("status", "COMPLETED").param("workerId", worker.getId().toString())
                        .param("studentId", otherStudent.getId().toString()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/student/orders/" + id));
        var after = service.getForStudent(student, id);
        assertThat(after.status()).isEqualTo(RepairStatus.CANCELLED);
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.completedAt()).isNull(); assertThat(after.workerName()).isNull();
        mvc.perform(post("/student/orders/" + id + "/cancel").with(user(student)).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get("/student/orders/" + id).with(user(student))).andExpect(status().isOk())
                .andExpect(content().string(containsString("已取消"))).andExpect(content().string(not(containsString("确认撤销"))));
        assertThat(countOrders()).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(value=RepairStatus.class, names={"PENDING_REPAIR", "IN_PROGRESS", "PENDING_CONFIRMATION", "COMPLETED", "CANCELLED"})
    void onlyPendingAssignmentCanBeCancelled(RepairStatus state) throws Exception {
        Long id = service.createForStudent(student, form());
        boolean assigned = state != RepairStatus.CANCELLED;
        jdbc.update("UPDATE repair_orders SET status=?, worker_id=?, completed_at=? WHERE id=?", state.name(),
                assigned ? worker.getId() : null, state == RepairStatus.COMPLETED ? LocalDateTime.now(clock).plusSeconds(1) : null, id);
        mvc.perform(post("/student/orders/" + id + "/cancel").with(user(student)).with(csrf())).andExpect(status().isConflict());
        assertThat(service.getForStudent(student, id).status()).isEqualTo(state);
    }

    @Test
    void twoConcurrentCancellationsHaveExactlyOneWinner() throws Exception {
        Long id = service.createForStudent(student, form());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i=0; i<2; i++) tasks.add(pool.submit(() -> {
                start.await();
                try { service.cancel(student, id); return "success"; }
                catch (OrderConflictException ex) { return "conflict"; }
            }));
            start.countDown();
            assertThat(List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("success", "conflict");
        }
        assertThat(service.getForStudent(student, id).status()).isEqualTo(RepairStatus.CANCELLED);
    }

    @Test
    void cancellationWaitsForRowLockAndDoesNotOverwriteNewAssignment() throws Exception {
        Long id = service.createForStudent(student, form());
        try (var blocker = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.prepareStatement("SELECT id FROM repair_orders WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, id); try (var ignored = statement.executeQuery()) { }
                }
                var entered = new CountDownLatch(1);
                var attempt = pool.submit(() -> { entered.countDown(); return catchThrowable(() -> service.cancel(student, id)); });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> attempt.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                // Fixture simulates a future assignment transaction; no admin business endpoint is implemented.
                try (var statement = blocker.prepareStatement("UPDATE repair_orders SET status='PENDING_REPAIR', worker_id=? WHERE id=?")) {
                    statement.setLong(1, worker.getId()); statement.setLong(2, id); statement.executeUpdate();
                }
                blocker.commit();
                assertThat(attempt.get(5, TimeUnit.SECONDS)).isInstanceOf(OrderConflictException.class);
            } finally { blocker.rollback(); }
        }
        assertThat(service.getForStudent(student, id).status()).isEqualTo(RepairStatus.PENDING_REPAIR);
    }

    @Test
    void realMySqlLockTimeoutIsTranslatedAndOrderIsNotChanged() throws Exception {
        Long id = service.createForStudent(student, form());
        try (var blocker = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.prepareStatement("SELECT id FROM repair_orders WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, id); try (var ignored = statement.executeQuery()) { }
                }
                var attempt = pool.submit(() -> catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    int old = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                    try { service.cancel(student, id); }
                    finally { jdbc.execute("SET SESSION innodb_lock_wait_timeout=" + old); }
                })));
                assertThat(attempt.get(10, TimeUnit.SECONDS)).isInstanceOf(PessimisticLockingFailureException.class);
            } finally { blocker.rollback(); }
        }
        assertThat(service.getForStudent(student, id).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
    }

    @ParameterizedTest
    @ValueSource(strings={"category='INVALID'", "status='INVALID'", "worker_id=1", "completed_at=NOW(6)", "location=' '", "description=' '"})
    void realMySqlRejectsInconsistentOrderUpdates(String invalidUpdate) {
        Long id = service.createForStudent(student, form());
        // worker_id uses a known existing account so the intended CHECK, rather than a missing FK, is tested.
        invalidUpdate = invalidUpdate.equals("worker_id=1") ? "worker_id=" + worker.getId() : invalidUpdate;
        String sql = "UPDATE repair_orders SET " + invalidUpdate + " WHERE id=" + id;
        var failure = catchThrowable(() -> jdbc.update(sql));
        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(((SQLException) failure.getCause()).getErrorCode()).isEqualTo(3819);
        assertThat(service.getForStudent(student, id).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
    }

    @Test
    void realMySqlForeignKeysRestrictMissingOwnerAndAccountDeletion() {
        Long id = service.createForStudent(student, form());
        assertThatThrownBy(() -> jdbc.update("UPDATE repair_orders SET student_id=? WHERE id=?", Long.MAX_VALUE, id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id=?", student.getId())).isInstanceOf(DataAccessException.class);
        assertThat(users.existsById(student.getId())).isTrue();
    }

    @Test
    void unassignedOrdersCannotBeConfirmedOrStarted() throws Exception {
        Long id = service.createForStudent(student, form());
        mvc.perform(post("/student/orders/" + id + "/confirm").with(user(student)).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post("/worker/orders/" + id + "/start").with(user(worker)).with(csrf())).andExpect(status().isNotFound());
        assertThat(service.getForStudent(student, id).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
    }
}
