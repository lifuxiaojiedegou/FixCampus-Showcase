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
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.view.OrderSummary;
import com.fixcampus.view.WorkerOrderDetail;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
class WorkerOrderIntegrationTest extends RealMySqlTestSupport {
    @Autowired MockMvc mvc;
    @Autowired RepairOrderService orders;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    private final List<Long> ownedUserIds = new ArrayList<>();
    private CampusPrincipal student, admin, worker, otherWorker;

    @BeforeAll
    void fixtures() {
        String prefix = "wk" + UUID.randomUUID().toString().substring(0, 8);
        student = fixture(prefix + "_s", Role.STUDENT);
        admin = fixture(prefix + "_a", Role.ADMIN);
        worker = fixture(prefix + "_w1", Role.WORKER);
        otherWorker = fixture(prefix + "_w2", Role.WORKER);
    }

    private CampusPrincipal fixture(String username, Role role) {
        var saved = users.saveAndFlush(new User(username, passwords.encode(UUID.randomUUID().toString()),
                "测试" + role.getLabel(), "student-contact-marker", role, LocalDateTime.now(clock)));
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
        form.setLocation("维修测试地点"); form.setCategory(RepairCategory.OTHER); form.setDescription("待处理故障");
        Long id = orders.createForStudent(student, form);
        boolean assigned = state != RepairStatus.PENDING_ASSIGNMENT && state != RepairStatus.CANCELLED;
        jdbc.update("UPDATE repair_orders SET status=?, worker_id=?, completed_at=? WHERE id=?", state.name(),
                assigned ? worker.getId() : null, state == RepairStatus.COMPLETED ? LocalDateTime.now(clock).plusSeconds(1) : null, id);
        return id;
    }

    private CompleteRepairForm result(String value) {
        var form = new CompleteRepairForm(); form.setRepairResult(value); return form;
    }

    @Test
    void emptyAndScopedListKeepsAllFourAssignedStatesInStableOrder() throws Exception {
        mvc.perform(get("/worker/orders").with(user(worker))).andExpect(status().isOk())
                .andExpect(content().string(containsString("暂时没有维修任务")));
        Long first = create(RepairStatus.PENDING_REPAIR), second = create(RepairStatus.IN_PROGRESS);
        Long third = create(RepairStatus.PENDING_CONFIRMATION), fourth = create(RepairStatus.COMPLETED);
        create(RepairStatus.PENDING_ASSIGNMENT); create(RepairStatus.CANCELLED);
        Long foreign = create(RepairStatus.PENDING_REPAIR);
        jdbc.update("UPDATE repair_orders SET worker_id=? WHERE id=?", otherWorker.getId(), foreign);
        jdbc.update("UPDATE repair_orders SET created_at='2026-10-01 12:00:00' WHERE student_id=?", student.getId());
        jdbc.update("UPDATE repair_orders SET created_at='2026-10-02 12:00:00' WHERE id=?", fourth);
        assertThat(orders.listForWorker(worker)).extracting(OrderSummary::id).containsExactly(fourth, third, second, first);
        mvc.perform(get("/worker/orders").with(user(worker)).param("workerId", otherWorker.getId().toString()))
                .andExpect(status().isOk()).andExpect(model().attribute("orders", orders.listForWorker(worker)));
        assertThat(orders.listForWorker(otherWorker)).extracting(OrderSummary::id).containsExactly(foreign);
    }

    @Test
    void detailShowsOnlyNecessaryStudentInformationAndEscapesUserText() throws Exception {
        Long id = create(RepairStatus.PENDING_REPAIR);
        jdbc.update("UPDATE repair_orders SET location=?, description=? WHERE id=?", "<script>地点</script>", "<img src=x onerror=alert(1)>", id);
        var detail = orders.getForWorker(worker, id);
        assertThat(detail.studentName()).isEqualTo(student.getRealName());
        assertThat(detail.studentPhone()).isEqualTo("student-contact-marker");
        assertThat(WorkerOrderDetail.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("password", "student", "worker", "studentUsername");
        mvc.perform(get("/worker/orders/" + id).with(user(worker))).andExpect(status().isOk())
                .andExpect(content().string(containsString("student-contact-marker")))
                .andExpect(content().string(containsString("&lt;script&gt;地点&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<img src=x"))))
                .andExpect(content().string(not(containsString("{pbkdf2"))))
                .andExpect(content().string(not(containsString(student.getUsername()))));
        jdbc.update("UPDATE users SET phone=NULL WHERE id=?", student.getId());
        try {
            mvc.perform(get("/worker/orders/" + id).with(user(worker))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("未提供")));
        } finally { jdbc.update("UPDATE users SET phone='student-contact-marker' WHERE id=?", student.getId()); }
    }

    @Test
    void startAndCompleteUsePathSessionAndWhitelistWith303AndOneTimeFlash() throws Exception {
        Long id = create(RepairStatus.PENDING_REPAIR), untouched = create(RepairStatus.PENDING_REPAIR);
        var before = orders.getForAdmin(admin, id);
        var started = mvc.perform(post("/worker/orders/" + id + "/start").with(user(worker)).with(csrf())
                        .param("id", untouched.toString()).param("status", "COMPLETED").param("repairResult", "forged-start-result"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/worker/orders/" + id))
                .andExpect(flash().attribute("successMessage", "已开始维修，工单已进入维修中状态。")).andReturn();
        var inProgress = orders.getForWorker(worker, id);
        assertThat(inProgress.status()).isEqualTo(RepairStatus.IN_PROGRESS);
        assertThat(inProgress.repairResult()).isNull(); assertThat(inProgress.completedAt()).isNull();
        var session = (MockHttpSession) started.getRequest().getSession(false);
        mvc.perform(get("/worker/orders/" + id).with(user(worker)).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("已开始维修，工单已进入维修中状态。")))
                .andExpect(content().string(containsString("id=\"repairResult\"")))
                .andExpect(content().string(not(containsString(">开始维修</button>"))));
        mvc.perform(get("/worker/orders/" + id).with(user(worker)).session(session)).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("successMessage"))
                .andExpect(content().string(not(containsString("class=\"notice notice-success\""))));
        var completed = mvc.perform(post("/worker/orders/" + id + "/complete").with(user(worker)).with(csrf())
                        .param("repairResult", "  已更换开关\n请保持干燥 <script>alert(1)</script>  ")
                        .param("id", untouched.toString()).param("studentId", admin.getId().toString())
                        .param("workerId", otherWorker.getId().toString()).param("worker.id", otherWorker.getId().toString())
                        .param("status", "COMPLETED").param("location", "forged-location").param("description", "forged-description")
                        .param("createdAt", "2000-01-01T00:00:00").param("completedAt", "2000-01-01T00:00:00"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/worker/orders/" + id))
                .andExpect(flash().attribute("successMessage", "维修结果已提交，等待报修学生确认。")).andReturn();
        var after = orders.getForAdmin(admin, id);
        assertThat(after.status()).isEqualTo(RepairStatus.PENDING_CONFIRMATION);
        assertThat(after.repairResult()).isEqualTo("已更换开关\n请保持干燥 <script>alert(1)</script>");
        assertThat(after.completedAt()).isNull();
        assertThat(after.studentUsername()).isEqualTo(before.studentUsername());
        assertThat(after.workerUsername()).isEqualTo(before.workerUsername());
        assertThat(after.location()).isEqualTo(before.location()); assertThat(after.description()).isEqualTo(before.description());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(orders.getForWorker(worker, untouched).status()).isEqualTo(RepairStatus.PENDING_REPAIR);
        var completeSession = (MockHttpSession) completed.getRequest().getSession(false);
        mvc.perform(get("/worker/orders/" + id).with(user(worker)).session(completeSession)).andExpect(status().isOk())
                .andExpect(content().string(containsString("维修结果已提交，等待报修学生确认。")))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("id=\"repairResult\""))));
        mvc.perform(get("/worker/orders/" + id).with(user(worker)).session(completeSession)).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("successMessage"))
                .andExpect(content().string(not(containsString("class=\"notice notice-success\""))));
        for (var viewer : List.of(student, admin)) {
            String prefix = viewer.getRole() == Role.STUDENT ? "/student" : "/admin";
            mvc.perform(get(prefix + "/orders/" + id).with(user(viewer))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("待确认")))
                    .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                    .andExpect(content().string(not(containsString("student-contact-marker"))));
        }
        mvc.perform(post("/student/orders/" + id + "/confirm").with(user(student)).with(csrf())).andExpect(status().isSeeOther());
        assertThat(orders.getForStudent(student, id).status()).isEqualTo(RepairStatus.COMPLETED);
        assertThat(orders.getForStudent(student, id).completedAt()).isNotNull();
    }

    @Test
    void omittedEmptyAndWhitespaceResultsAllPersistAsNull() throws Exception {
        for (String value : new String[]{null, "", " \t\r\n　 "}) {
            Long id = create(RepairStatus.IN_PROGRESS);
            var request = post("/worker/orders/" + id + "/complete").with(user(worker)).with(csrf());
            if (value != null) request.param("repairResult", value);
            mvc.perform(request).andExpect(status().isSeeOther());
            var detail = orders.getForWorker(worker, id);
            assertThat(detail.status()).isEqualTo(RepairStatus.PENDING_CONFIRMATION);
            assertThat(detail.repairResult()).isNull(); assertThat(detail.completedAt()).isNull();
        }
    }

    @ParameterizedTest @ValueSource(strings={"\n", "\r\n", "\r"})
    void browserLineEndingsShareRepairResultBoundaryAndPersistLf(String lineEnding) throws Exception {
        Long id = create(RepairStatus.IN_PROGRESS);
        String exact = "修".repeat(998) + lineEnding + "🔧";
        var before = orders.getForWorker(worker, id);
        mvc.perform(post("/worker/orders/" + id + "/complete").with(user(worker)).with(csrf()).param("repairResult", exact + "多"))
                .andExpect(status().isBadRequest()).andExpect(model().attributeHasFieldErrors("completeRepairForm", "repairResult"));
        assertThat(orders.getForWorker(worker, id)).isEqualTo(before);
        mvc.perform(post("/worker/orders/" + id + "/complete").with(user(worker)).with(csrf()).param("repairResult", exact))
                .andExpect(status().isSeeOther());
        assertThat(orders.getForWorker(worker, id).repairResult()).isEqualTo("修".repeat(998) + "\n🔧");
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(repair_result) FROM repair_orders WHERE id=?", Integer.class, id)).isEqualTo(1000);
    }

    @Test
    void unicodeBoundaryAccepts1000CodePointsAndRejects1001WithoutTruncating() throws Exception {
        Long id = create(RepairStatus.IN_PROGRESS);
        String maximum = "🔧".repeat(1000), oversized = maximum + "好";
        var before = orders.getForWorker(worker, id);
        mvc.perform(post("/worker/orders/" + id + "/complete").with(user(worker)).with(csrf()).param("repairResult", oversized))
                .andExpect(status().isBadRequest()).andExpect(view().name("worker/order-detail"))
                .andExpect(model().attributeHasFieldErrors("completeRepairForm", "repairResult"))
                .andExpect(content().string(containsString("维修说明最多填写 1000 个字符")))
                .andExpect(content().string(containsString(oversized)));
        assertThat(orders.getForWorker(worker, id)).isEqualTo(before);
        assertThatThrownBy(() -> orders.complete(worker, id, result(oversized))).isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> orders.complete(worker, id, null)).isInstanceOf(ConstraintViolationException.class);
        mvc.perform(post("/worker/orders/" + id + "/complete").with(user(worker)).with(csrf()).param("repairResult", "  " + maximum + "  "))
                .andExpect(status().isSeeOther());
        assertThat(orders.getForWorker(worker, id).repairResult()).isEqualTo(maximum);
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(repair_result) FROM repair_orders WHERE id=?", Integer.class, id)).isEqualTo(1000);
    }

    @ParameterizedTest @EnumSource(RepairStatus.class)
    void onlyAssignedPendingRepairCanStartAndOnlyInProgressCanComplete(RepairStatus state) throws Exception {
        Long startId = create(state), completeId = create(state);
        var startBefore = orders.getForAdmin(admin, startId); var completeBefore = orders.getForAdmin(admin, completeId);
        boolean assigned = state != RepairStatus.PENDING_ASSIGNMENT && state != RepairStatus.CANCELLED;
        int startStatus = !assigned ? 404 : state == RepairStatus.PENDING_REPAIR ? 303 : 409;
        int completeStatus = !assigned ? 404 : state == RepairStatus.IN_PROGRESS ? 303 : 409;
        mvc.perform(post("/worker/orders/" + startId + "/start").with(user(worker)).with(csrf())).andExpect(status().is(startStatus));
        mvc.perform(post("/worker/orders/" + completeId + "/complete").with(user(worker)).with(csrf()).param("repairResult", "结果"))
                .andExpect(status().is(completeStatus));
        if (startStatus != 303) assertThat(orders.getForAdmin(admin, startId)).isEqualTo(startBefore);
        if (completeStatus != 303) assertThat(orders.getForAdmin(admin, completeId)).isEqualTo(completeBefore);
        if (assigned) {
            mvc.perform(get("/worker/orders/" + startId).with(user(worker))).andExpect(status().isOk());
        } else {
            mvc.perform(get("/worker/orders/" + startId).with(user(worker))).andExpect(status().isNotFound());
        }
    }

    @Test
    void foreignUnassignedAndMissingObjectsAllReturn404WithoutLeakingOrMutating() throws Exception {
        Long foreign = create(RepairStatus.IN_PROGRESS), unassigned = create(RepairStatus.PENDING_ASSIGNMENT);
        jdbc.update("UPDATE repair_orders SET worker_id=? WHERE id=?", otherWorker.getId(), foreign);
        var before = orders.getForAdmin(admin, foreign);
        for (long id : new long[]{foreign, unassigned, 0, -1, Long.MAX_VALUE}) {
            for (String suffix : new String[]{"", "/start", "/complete"}) {
                var request = suffix.isEmpty() ? get("/worker/orders/" + id) : post("/worker/orders/" + id + suffix);
                mvc.perform(request.with(user(worker)).with(csrf()).param("repairResult", "x"))
                        .andExpect(status().isNotFound()).andExpect(content().string(not(containsString("待处理故障"))))
                        .andExpect(content().string(not(containsString("student-contact-marker"))));
            }
        }
        assertThatThrownBy(() -> orders.getForWorker(worker, foreign)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> orders.start(worker, foreign)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> orders.complete(worker, foreign, result("x"))).isInstanceOf(OrderNotFoundException.class);
        mvc.perform(post("/worker/orders/" + foreign + "/complete").with(user(worker)).with(csrf()).param("repairResult", "x".repeat(1001)))
                .andExpect(status().isNotFound());
        assertThat(orders.getForAdmin(admin, foreign)).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value=Role.class, names={"STUDENT", "ADMIN"})
    void otherRolesCannotReadOrOperateAtHttpAndServiceBoundaries(Role role) throws Exception {
        var actor = role == Role.STUDENT ? student : admin;
        Long id = create(RepairStatus.IN_PROGRESS);
        mvc.perform(get("/worker/orders").with(user(actor))).andExpect(status().isForbidden());
        mvc.perform(get("/worker/orders/" + id).with(user(actor))).andExpect(status().isForbidden());
        for (String action : new String[]{"start", "complete"}) {
            mvc.perform(post("/worker/orders/" + id + "/" + action).with(user(actor)).with(csrf())).andExpect(status().isForbidden());
        }
        assertThatThrownBy(() -> orders.listForWorker(actor)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orders.getForWorker(actor, id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orders.start(actor, id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orders.complete(actor, id, result(null))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anonymousCsrfAndGetRequestsCannotMutateAndWorkerCannotConfirm() throws Exception {
        Long id = create(RepairStatus.PENDING_REPAIR); var before = orders.getForWorker(worker, id);
        mvc.perform(get("/worker/orders")).andExpect(status().isFound()).andExpect(redirectedUrl("http://localhost/login"));
        mvc.perform(get("/worker/orders/" + id)).andExpect(status().isFound());
        for (String action : new String[]{"start", "complete"}) {
            String path = "/worker/orders/" + id + "/" + action;
            mvc.perform(post(path).with(csrf())).andExpect(status().isFound());
            mvc.perform(post(path).with(user(worker))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user(worker))).andExpect(status().isMethodNotAllowed());
        }
        mvc.perform(post("/student/orders/" + id + "/confirm").with(user(worker)).with(csrf())).andExpect(status().isForbidden());
        assertThatThrownBy(() -> orders.start(null, id)).isInstanceOf(AccessDeniedException.class);
        assertThat(orders.getForWorker(worker, id)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void duplicateConcurrentTransitionsHaveOneWinnerAndPreserveFirstResult(boolean completing) throws Exception {
        Long id = create(completing ? RepairStatus.IN_PROGRESS : RepairStatus.PENDING_REPAIR);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transition(start, completing, id, "结果一"));
            var second = pool.submit(() -> transition(start, completing, id, "结果二"));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("success", "conflict");
        }
        var after = orders.getForWorker(worker, id);
        assertThat(after.status()).isEqualTo(completing ? RepairStatus.PENDING_CONFIRMATION : RepairStatus.IN_PROGRESS);
        if (completing) {
            assertThat(after.repairResult()).isIn("结果一", "结果二");
            assertThatThrownBy(() -> orders.complete(worker, id, result("覆盖结果"))).isInstanceOf(OrderConflictException.class);
            assertThat(orders.getForWorker(worker, id).repairResult()).isEqualTo(after.repairResult());
        }
        assertThat(after.completedAt()).isNull();
    }

    private String transition(CountDownLatch start, boolean completing, Long id, String text) throws InterruptedException {
        start.await();
        try {
            if (completing) orders.complete(worker, id, result(text)); else orders.start(worker, id);
            return "success";
        } catch (OrderConflictException exception) { return "conflict"; }
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void waitingTransitionReadsCommittedStateAndDoesNotOverwrite(boolean completing) throws Exception {
        Long id = create(completing ? RepairStatus.IN_PROGRESS : RepairStatus.PENDING_REPAIR);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var winner = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    if (completing) orders.complete(worker, id, result("先提交的结果")); else orders.start(worker, id);
                    held.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock release timed out"); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                }));
                assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
                var loser = pool.submit(() -> catchThrowable(() -> {
                    if (completing) orders.complete(worker, id, result("后提交的结果")); else orders.start(worker, id);
                }));
                assertThatThrownBy(() -> loser.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); winner.get(5, TimeUnit.SECONDS);
                assertThat(loser.get(5, TimeUnit.SECONDS)).isInstanceOf(OrderConflictException.class);
            } finally { release.countDown(); }
        }
        if (completing) assertThat(orders.getForWorker(worker, id).repairResult()).isEqualTo("先提交的结果");
        assertThat(orders.getForWorker(worker, id).completedAt()).isNull();
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void realMySqlLockTimeoutLeavesStatusAndResultUnchanged(boolean completing) throws Exception {
        Long id = create(completing ? RepairStatus.IN_PROGRESS : RepairStatus.PENDING_REPAIR);
        var before = orders.getForWorker(worker, id);
        try (var blocker = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.prepareStatement("SELECT id FROM repair_orders WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, id); try (var ignored = statement.executeQuery()) { }
                }
                var attempt = pool.submit(() -> catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    int old = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                    try {
                        if (completing) orders.complete(worker, id, result("不应保存")); else orders.start(worker, id);
                    } finally { jdbc.execute("SET SESSION innodb_lock_wait_timeout=" + old); }
                })));
                assertThat(attempt.get(10, TimeUnit.SECONDS)).isInstanceOf(PessimisticLockingFailureException.class);
            } finally { blocker.rollback(); }
        }
        assertThat(orders.getForWorker(worker, id)).isEqualTo(before);
    }
}
