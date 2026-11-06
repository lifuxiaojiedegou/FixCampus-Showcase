package com.fixcampus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.exception.OrderConflictException;
import com.fixcampus.exception.OrderNotFoundException;
import com.fixcampus.form.CompleteRepairForm;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.repository.RepairOrderRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.view.OrderSummary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StudentConfirmationIntegrationTest extends RealMySqlTestSupport {
    @Autowired MockMvc mvc;
    @Autowired RepairOrderService orders;
    @Autowired UserRepository users;
    @Autowired RepairOrderRepository entities;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    private final List<Long> ownedUserIds = new ArrayList<>();
    private CampusPrincipal student, otherStudent, admin, worker;

    @BeforeAll
    void fixtures() {
        String prefix = "cf" + UUID.randomUUID().toString().substring(0, 8);
        student = fixture(prefix + "_s1", Role.STUDENT);
        otherStudent = fixture(prefix + "_s2", Role.STUDENT);
        admin = fixture(prefix + "_a", Role.ADMIN);
        worker = fixture(prefix + "_w", Role.WORKER);
    }

    private CampusPrincipal fixture(String username, Role role) {
        var saved = users.saveAndFlush(new User(username, passwords.encode(UUID.randomUUID().toString()),
                "测试" + role.getLabel(), "private-contact-marker", role, LocalDateTime.now(clock)));
        ownedUserIds.add(saved.getId());
        var actor = new CampusPrincipal(saved); actor.eraseCredentials(); return actor;
    }

    @AfterEach
    void cleanOwnedOrders() {
        for (Long id : ownedUserIds) { jdbc.update("DELETE FROM repair_orders WHERE student_id=?", id); }
    }

    @AfterAll
    void cleanOwnedUsers() {
        cleanOwnedOrders();
        for (Long id : ownedUserIds) { jdbc.update("DELETE FROM users WHERE id=?", id); }
    }

    private Long create(RepairStatus state) {
        var form = new RepairOrderForm();
        form.setLocation("确认测试地点"); form.setCategory(RepairCategory.OTHER); form.setDescription("待确认的故障描述");
        Long id = orders.createForStudent(student, form);
        if (state == RepairStatus.PENDING_ASSIGNMENT) return id;
        if (state == RepairStatus.CANCELLED) { orders.cancel(student, id); return id; }
        orders.assign(admin, id, worker.getId());
        if (state == RepairStatus.PENDING_REPAIR) return id;
        orders.start(worker, id);
        if (state == RepairStatus.IN_PROGRESS) return id;
        orders.complete(worker, id, result("已排除故障\n<script>结果</script>"));
        if (state == RepairStatus.COMPLETED) orders.confirm(student, id);
        return id;
    }

    private CompleteRepairForm result(String value) {
        var form = new CompleteRepairForm(); form.setRepairResult(value); return form;
    }

    @Test
    void ownerConfirmationUsesServerTimePreservesRepairAndRedirectsWithOneTimeFlash() throws Exception {
        Long id = create(RepairStatus.PENDING_CONFIRMATION), untouched = create(RepairStatus.PENDING_CONFIRMATION);
        var before = orders.getForAdmin(admin, id);
        assertThat(before.completedAt()).isNull();
        LocalDateTime earliest = LocalDateTime.now(clock).minusSeconds(1);
        var response = mvc.perform(post("/student/orders/" + id + "/confirm").with(user(student)).with(csrf())
                        .param("id", untouched.toString()).param("studentId", otherStudent.getId().toString())
                        .param("workerId", admin.getId().toString()).param("status", "CANCELLED")
                        .param("completedAt", "2000-01-01T00:00:00").param("createdAt", "2000-01-01T00:00:00")
                        .param("repairResult", "伪造维修结果").param("location", "伪造地点").param("description", "伪造描述"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/student/orders/" + id))
                .andExpect(flash().attribute("successMessage", "已确认维修完成，工单记录将继续保留。")).andReturn();
        var after = orders.getForAdmin(admin, id);
        assertThat(after.status()).isEqualTo(RepairStatus.COMPLETED);
        assertThat(after.completedAt()).isBetween(earliest, LocalDateTime.now(clock).plusSeconds(1));
        assertThat(after.completedAt()).isAfterOrEqualTo(after.createdAt());
        assertThat(after.studentUsername()).isEqualTo(before.studentUsername());
        assertThat(after.workerUsername()).isEqualTo(before.workerUsername());
        assertThat(after.location()).isEqualTo(before.location()); assertThat(after.description()).isEqualTo(before.description());
        assertThat(after.repairResult()).isEqualTo(before.repairResult()); assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(orders.getForStudent(student, untouched).status()).isEqualTo(RepairStatus.PENDING_CONFIRMATION);
        var session = (MockHttpSession) response.getRequest().getSession(false);
        mvc.perform(get("/student/orders/" + id).with(user(student)).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("已确认维修完成，工单记录将继续保留。")))
                .andExpect(content().string(containsString("完成时间")))
                .andExpect(content().string(not(containsString(">确认完成</button>"))))
                .andExpect(content().string(not(containsString(">确认撤销</button>"))));
        mvc.perform(get("/student/orders/" + id).with(user(student)).session(session)).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("successMessage"))
                .andExpect(content().string(not(containsString("class=\"notice notice-success\""))));
        assertThat(orders.listForStudent(student)).extracting(OrderSummary::id).contains(id);
        assertThat(orders.listForWorker(worker)).extracting(OrderSummary::id).contains(id);
        assertThat(orders.listForAdmin(admin, RepairStatus.COMPLETED)).extracting(OrderSummary::id).containsExactly(id);
        for (var actor : List.of(student, worker, admin)) {
            String prefix = switch (actor.getRole()) { case STUDENT -> "/student"; case WORKER -> "/worker"; case ADMIN -> "/admin"; };
            mvc.perform(get(prefix + "/orders/" + id).with(user(actor))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("已完成")))
                    .andExpect(content().string(containsString("完成时间")))
                    .andExpect(content().string(containsString("&lt;script&gt;结果&lt;/script&gt;")))
                    .andExpect(content().string(not(containsString("{pbkdf2"))));
        }
    }

    @ParameterizedTest @EnumSource(RepairStatus.class)
    void onlyPendingConfirmationShowsActionAndCanBeConfirmed(RepairStatus state) throws Exception {
        Long id = create(state); var before = orders.getForStudent(student, id);
        assertThat(before.canConfirm()).isEqualTo(state == RepairStatus.PENDING_CONFIRMATION);
        mvc.perform(get("/student/orders/" + id).with(user(student))).andExpect(status().isOk())
                .andExpect(state == RepairStatus.PENDING_CONFIRMATION
                        ? content().string(containsString(">确认完成</button>"))
                        : content().string(not(containsString(">确认完成</button>"))));
        mvc.perform(post("/student/orders/" + id + "/confirm").with(user(student)).with(csrf()))
                .andExpect(status().is(state == RepairStatus.PENDING_CONFIRMATION ? 303 : 409));
        if (state != RepairStatus.PENDING_CONFIRMATION) assertThat(orders.getForStudent(student, id)).isEqualTo(before);
    }

    @Test
    void foreignAndMissingOrdersShare404WithoutDetailsAndCannotBeChanged() throws Exception {
        Long id = create(RepairStatus.PENDING_CONFIRMATION); var before = orders.getForAdmin(admin, id);
        for (Long hidden : List.of(id, 0L, -1L, Long.MAX_VALUE)) {
            mvc.perform(post("/student/orders/" + hidden + "/confirm").with(user(otherStudent)).with(csrf())
                            .param("studentId", student.getId().toString()))
                    .andExpect(status().isNotFound()).andExpect(content().string(containsString("工单不存在或无权访问")))
                    .andExpect(content().string(not(containsString("待确认的故障描述"))));
            assertThatThrownBy(() -> orders.confirm(otherStudent, hidden)).isInstanceOf(OrderNotFoundException.class);
        }
        assertThatThrownBy(() -> orders.confirm(student, null)).isInstanceOf(OrderNotFoundException.class);
        assertThat(orders.getForAdmin(admin, id)).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value=Role.class, names={"ADMIN", "WORKER"})
    void otherRolesCannotConfirmAtEitherBoundary(Role role) throws Exception {
        Long id = create(RepairStatus.PENDING_CONFIRMATION);
        var actor = role == Role.ADMIN ? admin : worker; var before = orders.getForStudent(student, id);
        mvc.perform(post("/student/orders/" + id + "/confirm").with(user(actor)).with(csrf())).andExpect(status().isForbidden());
        assertThatThrownBy(() -> orders.confirm(actor, id)).isInstanceOf(AccessDeniedException.class);
        assertThat(orders.getForStudent(student, id)).isEqualTo(before);
    }

    @Test
    void anonymousCsrfAndGetRequestsNeverConfirm() throws Exception {
        Long id = create(RepairStatus.PENDING_CONFIRMATION); var before = orders.getForStudent(student, id);
        mvc.perform(post("/student/orders/" + id + "/confirm").with(csrf())).andExpect(status().isFound());
        mvc.perform(post("/student/orders/" + id + "/confirm").with(user(student))).andExpect(status().isForbidden());
        mvc.perform(get("/student/orders/" + id + "/confirm").with(user(student))).andExpect(status().isMethodNotAllowed());
        assertThatThrownBy(() -> orders.confirm(null, id)).isInstanceOf(AccessDeniedException.class);
        assertThat(orders.getForStudent(student, id)).isEqualTo(before);
    }

    @Test
    void completedOrderRejectsEveryMutationAndKeepsFirstTimeAndResult() throws Exception {
        Long id = create(RepairStatus.COMPLETED); var before = orders.getForAdmin(admin, id);
        for (String action : List.of("confirm", "cancel")) {
            mvc.perform(post("/student/orders/" + id + "/" + action).with(user(student)).with(csrf())
                            .param("completedAt", "2099-01-01T00:00:00"))
                    .andExpect(status().isConflict()).andExpect(header().doesNotExist("Location"));
        }
        for (String action : List.of("start", "complete")) {
            mvc.perform(post("/worker/orders/" + id + "/" + action).with(user(worker)).with(csrf()).param("repairResult", "覆盖"))
                    .andExpect(status().isConflict());
        }
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf()).param("workerId", worker.getId().toString()))
                .andExpect(status().isConflict());
        assertThat(orders.getForAdmin(admin, id)).isEqualTo(before);
    }

    @Test
    void confirmationWithNoRepairResultIsAllowed() {
        Long id = create(RepairStatus.IN_PROGRESS);
        orders.complete(worker, id, result(null)); orders.confirm(student, id);
        var detail = orders.getForStudent(student, id);
        assertThat(detail.status()).isEqualTo(RepairStatus.COMPLETED);
        assertThat(detail.completedAt()).isNotNull(); assertThat(detail.repairResult()).isNull();
    }

    @Test
    void concurrentConfirmationsHaveOneWinner() throws Exception {
        Long id = create(RepairStatus.PENDING_CONFIRMATION); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> confirmAfter(start, id)); var second = pool.submit(() -> confirmAfter(start, id));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("success", "conflict");
        }
        var after = orders.getForStudent(student, id);
        assertThat(after.status()).isEqualTo(RepairStatus.COMPLETED); assertThat(after.completedAt()).isNotNull();
        assertThatThrownBy(() -> orders.confirm(student, id)).isInstanceOf(OrderConflictException.class);
        assertThat(orders.getForStudent(student, id)).isEqualTo(after);
    }

    private String confirmAfter(CountDownLatch start, Long id) throws InterruptedException {
        start.await();
        try { orders.confirm(student, id); return "success"; }
        catch (OrderConflictException exception) { return "conflict"; }
    }

    @Test
    void waitingConfirmationReadsNewStateAndPreservesFirstTimestamp() throws Exception {
        Long id = create(RepairStatus.PENDING_CONFIRMATION);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var winner = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                    orders.confirm(student, id); entities.flush();
                    var firstTime = jdbc.queryForObject("SELECT completed_at FROM repair_orders WHERE id=?", LocalDateTime.class, id);
                    held.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock release timed out"); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                    return firstTime;
                }));
                assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
                var loser = pool.submit(() -> catchThrowable(() -> orders.confirm(student, id)));
                assertThatThrownBy(() -> loser.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); var firstTime = winner.get(5, TimeUnit.SECONDS);
                assertThat(loser.get(5, TimeUnit.SECONDS)).isInstanceOf(OrderConflictException.class);
                assertThat(orders.getForStudent(student, id).completedAt()).isEqualTo(firstTime);
            } finally { release.countDown(); }
        }
        var after = orders.getForStudent(student, id);
        assertThat(after.status()).isEqualTo(RepairStatus.COMPLETED); assertThat(after.completedAt()).isNotNull();
    }

    @Test
    void rollbackRestoresBothStateAndTimeAndAllowsLaterRetry() {
        Long id = create(RepairStatus.PENDING_CONFIRMATION); var before = orders.getForStudent(student, id);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            orders.confirm(student, id);
            entities.flush();
            assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM repair_orders WHERE id=?", Boolean.class, id)).isTrue();
            throw new IllegalStateException("Force test transaction rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(orders.getForStudent(student, id)).isEqualTo(before);
        orders.confirm(student, id); assertThat(orders.getForStudent(student, id).completedAt()).isNotNull();
    }

    @Test
    void realMySqlLockTimeoutNeverWritesCompletionOrTime() throws Exception {
        Long id = create(RepairStatus.PENDING_CONFIRMATION); var before = orders.getForStudent(student, id);
        try (var blocker = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.prepareStatement("SELECT id FROM repair_orders WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, id); try (var ignored = statement.executeQuery()) { }
                }
                var attempt = pool.submit(() -> catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    int old = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                    try { orders.confirm(student, id); }
                    finally { jdbc.execute("SET SESSION innodb_lock_wait_timeout=" + old); }
                })));
                assertThat(attempt.get(10, TimeUnit.SECONDS)).isInstanceOf(PessimisticLockingFailureException.class);
            } finally { blocker.rollback(); }
        }
        assertThat(orders.getForStudent(student, id)).isEqualTo(before);
    }
}
