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
import com.fixcampus.exception.InvalidWorkerException;
import com.fixcampus.exception.OrderConflictException;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.service.UserService;
import com.fixcampus.view.AdminOrderDetail;
import com.fixcampus.view.OrderSummary;
import com.fixcampus.view.WorkerOption;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminOrderIntegrationTest extends RealMySqlTestSupport {
    @Autowired MockMvc mvc;
    @Autowired RepairOrderService orders;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    private final List<Long> ownedUserIds = new ArrayList<>();
    private CampusPrincipal admin, student, otherStudent, worker, otherWorker;

    @BeforeAll
    void fixtures() {
        String prefix = "ad" + UUID.randomUUID().toString().substring(0, 8);
        admin = fixture(prefix + "_a", Role.ADMIN);
        student = fixture(prefix + "_s1", Role.STUDENT);
        otherStudent = fixture(prefix + "_s2", Role.STUDENT);
        worker = fixture(prefix + "_w1", Role.WORKER);
        otherWorker = fixture(prefix + "_w2", Role.WORKER);
    }

    private CampusPrincipal fixture(String username, Role role) {
        var saved = users.saveAndFlush(new User(username, passwords.encode(UUID.randomUUID().toString()),
                "测试" + role.getLabel(), "private-contact-marker", role, LocalDateTime.now(clock)));
        ownedUserIds.add(saved.getId());
        var actor = new CampusPrincipal(saved); actor.eraseCredentials(); return actor;
    }

    @AfterEach
    void cleanOnlyOwnedData() {
        for (Long id : ownedUserIds) { jdbc.update("DELETE FROM repair_orders WHERE student_id=?", id); }
        for (var actor : List.of(worker, otherWorker)) {
            jdbc.update("UPDATE users SET role='WORKER' WHERE id=?", actor.getId());
        }
    }

    @AfterAll
    void removeOwnedUsers() {
        cleanOnlyOwnedData();
        for (Long id : ownedUserIds) { jdbc.update("DELETE FROM users WHERE id=?", id); }
    }

    private Long create(CampusPrincipal owner) {
        var form = new RepairOrderForm();
        form.setLocation("管理员测试地点"); form.setCategory(RepairCategory.OTHER); form.setDescription("测试故障描述");
        return orders.createForStudent(owner, form);
    }

    private Long createState(RepairStatus state) {
        Long id = create(student);
        boolean assigned = state != RepairStatus.PENDING_ASSIGNMENT && state != RepairStatus.CANCELLED;
        jdbc.update("UPDATE repair_orders SET status=?, worker_id=?, completed_at=? WHERE id=?", state.name(),
                assigned ? worker.getId() : null, state == RepairStatus.COMPLETED ? LocalDateTime.now(clock).plusSeconds(1) : null, id);
        return id;
    }

    @Test
    void emptyAndAllStateListsRenderAndIncludeBothStudentsWithStableOrdering() throws Exception {
        mvc.perform(get("/admin/orders").with(user(admin))).andExpect(status().isOk())
                .andExpect(content().string(containsString("暂时没有工单")));
        Long first = create(student), second = create(otherStudent), newest = create(student);
        jdbc.update("UPDATE repair_orders SET created_at='2026-10-01 12:00:00' WHERE id IN (?,?)", first, second);
        jdbc.update("UPDATE repair_orders SET created_at='2026-10-02 12:00:00' WHERE id=?", newest);
        assertThat(orders.listForAdmin(admin, null)).extracting(OrderSummary::id).containsExactly(newest, second, first);
        mvc.perform(get("/admin/orders").with(user(admin))).andExpect(status().isOk())
                .andExpect(model().attribute("orders", orders.listForAdmin(admin, null)));
        mvc.perform(get("/admin/orders").param("status", "").with(user(admin))).andExpect(status().isOk())
                .andExpect(model().attribute("orders", orders.listForAdmin(admin, null)));
    }

    @ParameterizedTest @EnumSource(RepairStatus.class)
    void eachOfSixStatusFiltersReturnsOnlyItsOwnState(RepairStatus state) throws Exception {
        Long expected = null;
        for (RepairStatus fixtureState : RepairStatus.values()) {
            Long id = createState(fixtureState); if (fixtureState == state) expected = id;
        }
        assertThat(orders.listForAdmin(admin, state)).extracting(OrderSummary::id).containsExactly(expected);
        mvc.perform(get("/admin/orders").param("status", state.name()).with(user(admin)))
                .andExpect(status().isOk()).andExpect(model().attribute("selectedStatus", state))
                .andExpect(model().attribute("orders", orders.listForAdmin(admin, state)));
    }

    @ParameterizedTest @ValueSource(strings={"UNKNOWN", "pending_repair", " ", "null", "<script>", "PENDING_REPAIR,CANCELLED"})
    void invalidFilterIs400AndNeverSilentlyReturnsAllOrders(String invalid) throws Exception {
        Long id = create(student);
        mvc.perform(get("/admin/orders").param("status", invalid).with(user(admin)))
                .andExpect(status().isBadRequest()).andExpect(model().attribute("orders", List.of()))
                .andExpect(content().string(containsString("请选择有效的工单状态")))
                .andExpect(content().string(not(containsString("管理员测试地点"))))
                .andExpect(content().string(not(containsString("<script>"))));
        assertThat(orders.getForAdmin(admin, id).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
    }

    @Test
    void emptyFilteredListIsVisible() throws Exception {
        create(student);
        mvc.perform(get("/admin/orders").param("status", "COMPLETED").with(user(admin)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("没有符合条件的工单")));
    }

    @Test
    void candidatesContainOnlyWorkersAndDetailIncludesSafeAccountIdentification() throws Exception {
        Long id = create(otherStudent);
        assertThat(userService.listWorkers(admin)).extracting(WorkerOption::id).containsExactly(worker.getId(), otherWorker.getId());
        var detail = orders.getForAdmin(admin, id);
        assertThat(detail.studentUsername()).isEqualTo(otherStudent.getUsername());
        assertThat(detail.studentName()).isEqualTo(otherStudent.getRealName());
        assertThat(detail.workerUsername()).isNull();
        String html = mvc.perform(get("/admin/orders/" + id).with(user(admin))).andExpect(status().isOk())
                .andExpect(content().string(containsString("data-required-message=\"请选择维修人员\"")))
                .andExpect(content().string(not(containsString("{pbkdf2"))))
                .andExpect(content().string(not(containsString("private-contact-marker")))).andReturn().getResponse().getContentAsString();
        String options = html.substring(html.indexOf("<select"), html.indexOf("</select>"));
        assertThat(options).contains(worker.getUsername(), otherWorker.getUsername())
                .doesNotContain(student.getUsername(), otherStudent.getUsername(), admin.getUsername());
        assertThat(AdminOrderDetail.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("password", "phone", "student", "worker");
        assertThat(WorkerOption.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id", "username", "realName");
    }

    @Test
    void assignmentUsesPathAndWorkerOnlyAndPreservesOriginalOrderFields() throws Exception {
        Long id = create(student), otherId = create(otherStudent);
        var before = orders.getForAdmin(admin, id);
        var result = mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf())
                        .param("workerId", worker.getId().toString()).param("id", otherId.toString())
                        .param("studentId", otherStudent.getId().toString()).param("student.id", otherStudent.getId().toString())
                        .param("status", "COMPLETED").param("location", "forged-location")
                        .param("description", "forged-description").param("repairResult", "forged-result")
                        .param("completedAt", "2000-01-01T00:00:00").param("createdAt", "2000-01-01T00:00:00"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/admin/orders/" + id))
                .andExpect(flash().attributeExists("successMessage")).andReturn();
        var after = orders.getForAdmin(admin, id);
        assertThat(after.status()).isEqualTo(RepairStatus.PENDING_REPAIR);
        assertThat(after.workerUsername()).isEqualTo(worker.getUsername());
        assertThat(after.studentUsername()).isEqualTo(before.studentUsername());
        assertThat(after.location()).isEqualTo(before.location());
        assertThat(after.description()).isEqualTo(before.description());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.completedAt()).isNull(); assertThat(after.repairResult()).isNull();
        assertThat(orders.getForAdmin(admin, otherId).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
        mvc.perform(get("/admin/orders/" + id).with(user(admin)).session((MockHttpSession) result.getRequest().getSession(false)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("派单成功，工单已进入待维修状态。")))
                .andExpect(content().string(not(containsString("id=\"workerId\""))));
        mvc.perform(get("/admin/orders/" + id).with(user(admin))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("派单成功，工单已进入待维修状态。"))));
        mvc.perform(get("/student/orders/" + id).with(user(student))).andExpect(status().isOk())
                .andExpect(content().string(containsString("待维修"))).andExpect(content().string(containsString(worker.getRealName())))
                .andExpect(content().string(not(containsString(worker.getUsername()))))
                .andExpect(content().string(not(containsString("private-contact-marker"))))
                .andExpect(content().string(not(containsString("确认撤销"))));
        mvc.perform(post("/student/orders/" + id + "/cancel").with(user(student)).with(csrf())).andExpect(status().isConflict());
    }

    @ParameterizedTest @ValueSource(strings={"", "0", "-1", "not-a-number", "9223372036854775808"})
    void invalidWorkerFormReturns400InChineseWithNoMutation(String invalid) throws Exception {
        Long id = create(student);
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf()).param("workerId", invalid))
                .andExpect(status().isBadRequest()).andExpect(view().name("admin/order-detail"))
                .andExpect(model().attributeHasFieldErrors("assignWorkerForm", "workerId"))
                .andExpect(content().string(containsString("请选择")))
                .andExpect(content().string(not(containsString("Failed to convert"))));
        assertThat(orders.getForAdmin(admin, id).workerUsername()).isNull();
    }

    @ParameterizedTest @EnumSource(value=Role.class, names={"STUDENT", "ADMIN"})
    void nonWorkerAccountCannotBeAssigned(Role targetRole) throws Exception {
        Long id = create(student);
        Long targetId = targetRole == Role.STUDENT ? otherStudent.getId() : admin.getId();
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf()).param("workerId", targetId.toString()))
                .andExpect(status().isBadRequest()).andExpect(model().attributeHasFieldErrors("assignWorkerForm", "workerId"));
        assertThatThrownBy(() -> orders.assign(admin, id, targetId)).isInstanceOf(InvalidWorkerException.class);
        assertThat(orders.getForAdmin(admin, id).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
    }

    @Test
    void nonexistentAndStaleWorkerCandidatesAreRecheckedAtSubmission() throws Exception {
        Long id = create(student);
        assertThat(userService.listWorkers(admin)).extracting(WorkerOption::id).contains(worker.getId());
        jdbc.update("UPDATE users SET role='STUDENT' WHERE id=?", worker.getId());
        for (Long candidate : List.of(worker.getId(), Long.MAX_VALUE)) {
            mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf()).param("workerId", candidate.toString()))
                    .andExpect(status().isBadRequest()).andExpect(model().attributeHasFieldErrors("assignWorkerForm", "workerId"));
        }
        assertThat(userService.listWorkers(admin)).extracting(WorkerOption::id).doesNotContain(worker.getId());
        assertThat(orders.getForAdmin(admin, id).workerUsername()).isNull();
    }

    @Test
    void noEligibleWorkersShowsExplanationAndDisabledAssignmentControls() throws Exception {
        Long id = create(student);
        jdbc.update("UPDATE users SET role='STUDENT' WHERE id IN (?,?)", worker.getId(), otherWorker.getId());
        assertThat(userService.listWorkers(admin)).isEmpty();
        mvc.perform(get("/admin/orders/" + id).with(user(admin))).andExpect(status().isOk())
                .andExpect(model().attribute("workers", List.of()))
                .andExpect(content().string(containsString("暂无可分配的维修人员")))
                .andExpect(content().string(containsString("disabled=\"disabled\"")));
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf()).param("workerId", worker.getId().toString()))
                .andExpect(status().isBadRequest());
        assertThat(orders.getForAdmin(admin, id).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
    }

    @ParameterizedTest @ValueSource(longs={0, -1, Long.MAX_VALUE})
    void missingOrInvalidOrderIdsReturn404ForDetailAndAssignment(long id) throws Exception {
        mvc.perform(get("/admin/orders/" + id).with(user(admin))).andExpect(status().isNotFound());
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf()).param("workerId", worker.getId().toString()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest @EnumSource(value=RepairStatus.class, names={"PENDING_REPAIR", "IN_PROGRESS", "PENDING_CONFIRMATION", "COMPLETED", "CANCELLED"})
    void nonPendingStatesRejectAssignmentAndReassignmentWithoutChangingData(RepairStatus state) throws Exception {
        Long id = createState(state);
        var before = orders.getForAdmin(admin, id);
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf()).param("workerId", otherWorker.getId().toString()))
                .andExpect(status().isConflict());
        assertThat(orders.getForAdmin(admin, id)).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(value=Role.class, names={"STUDENT", "WORKER"})
    void nonAdminCannotReadOrAssignAtHttpAndServiceBoundaries(Role role) throws Exception {
        var actor = role == Role.STUDENT ? student : worker;
        Long id = create(student);
        for (String path : new String[]{"/admin/orders", "/admin/orders?status=INVALID", "/admin/orders/" + id}) {
            mvc.perform(get(path).with(user(actor))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(actor)).with(csrf()).param("workerId", worker.getId().toString()))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> orders.listForAdmin(actor, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orders.getForAdmin(actor, id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orders.assign(actor, id, worker.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> userService.listWorkers(actor)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anonymousCsrfAndGetRequestsCannotAssignAndAdminCannotOperateOtherRoles() throws Exception {
        Long id = create(student);
        mvc.perform(get("/admin/orders")).andExpect(status().isFound());
        mvc.perform(get("/admin/orders/" + id)).andExpect(status().isFound());
        mvc.perform(post("/admin/orders/" + id + "/assign").with(csrf()).param("workerId", worker.getId().toString())).andExpect(status().isFound());
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).param("workerId", worker.getId().toString())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/orders/" + id + "/assign").with(user(admin)).with(csrf().useInvalidToken()).param("workerId", worker.getId().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/orders/" + id + "/assign").with(user(admin))).andExpect(status().isMethodNotAllowed());
        for (String operation : new String[]{"/student/orders/" + id + "/cancel", "/student/orders/" + id + "/confirm", "/worker/orders/" + id + "/start"}) {
            mvc.perform(post(operation).with(user(admin)).with(csrf())).andExpect(status().isForbidden());
        }
        assertThatThrownBy(() -> orders.listForAdmin(null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> userService.listWorkers(null)).isInstanceOf(AccessDeniedException.class);
        assertThat(orders.getForAdmin(admin, id).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
    }

    @Test
    void directServiceRejectsMissingAndNonpositiveWorkerIds() {
        Long id = create(student);
        assertThatThrownBy(() -> orders.assign(admin, id, null)).isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> orders.assign(admin, id, 0L)).isInstanceOf(ConstraintViolationException.class);
        assertThat(orders.getForAdmin(admin, id).workerUsername()).isNull();
    }

    @Test
    void adminDetailsEscapeAllUserControlledText() throws Exception {
        Long id = create(student);
        jdbc.update("UPDATE repair_orders SET location=?, description=?, repair_result=?, status='PENDING_CONFIRMATION', worker_id=? WHERE id=?",
                "<b>地点</b>", "<script>alert(1)</script>", "<img src=x onerror=alert(1)>", worker.getId(), id);
        mvc.perform(get("/admin/orders/" + id).with(user(admin))).andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("&lt;img")))
                .andExpect(content().string(not(containsString("<script>alert"))))
                .andExpect(content().string(not(containsString("private-contact-marker"))));
    }

    @Test
    void concurrentAssignmentsToDifferentWorkersHaveExactlyOneWinner() throws Exception {
        Long id = create(student); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> assignWhenReleased(start, id, worker.getId()));
            var second = pool.submit(() -> assignWhenReleased(start, id, otherWorker.getId()));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("success", "conflict");
        }
        var result = orders.getForAdmin(admin, id);
        assertThat(result.status()).isEqualTo(RepairStatus.PENDING_REPAIR);
        assertThat(result.workerUsername()).isIn(worker.getUsername(), otherWorker.getUsername());
        assertThat(result.completedAt()).isNull();
    }

    private String assignWhenReleased(CountDownLatch start, Long id, Long workerId) throws InterruptedException {
        start.await();
        try { orders.assign(admin, id, workerId); return "success"; }
        catch (OrderConflictException exception) { return "conflict"; }
    }

    @ParameterizedTest @ValueSource(booleans={true, false})
    void assignmentAndCancellationRespectWhicheverTransactionCommitsFirst(boolean assignmentFirst) throws Exception {
        Long id = create(student); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var winner = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    if (assignmentFirst) orders.assign(admin, id, worker.getId()); else orders.cancel(student, id);
                    held.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock release timed out"); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                }));
                assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
                var loser = pool.submit(() -> catchThrowable(() -> {
                    if (assignmentFirst) orders.cancel(student, id); else orders.assign(admin, id, worker.getId());
                }));
                assertThatThrownBy(() -> loser.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); winner.get(5, TimeUnit.SECONDS);
                assertThat(loser.get(5, TimeUnit.SECONDS)).isInstanceOf(OrderConflictException.class);
            } finally { release.countDown(); }
        }
        var result = orders.getForAdmin(admin, id);
        assertThat(result.status()).isEqualTo(assignmentFirst ? RepairStatus.PENDING_REPAIR : RepairStatus.CANCELLED);
        assertThat(result.workerUsername()).isEqualTo(assignmentFirst ? worker.getUsername() : null);
    }

    @Test
    void realMySqlAssignmentLockTimeoutLeavesOrderUnassigned() throws Exception {
        Long id = create(student);
        try (var blocker = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.prepareStatement("SELECT id FROM repair_orders WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, id); try (var ignored = statement.executeQuery()) { }
                }
                var attempt = pool.submit(() -> catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    int old = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                    try { orders.assign(admin, id, worker.getId()); }
                    finally { jdbc.execute("SET SESSION innodb_lock_wait_timeout=" + old); }
                })));
                assertThat(attempt.get(10, TimeUnit.SECONDS)).isInstanceOf(PessimisticLockingFailureException.class);
            } finally { blocker.rollback(); }
        }
        assertThat(orders.getForAdmin(admin, id).status()).isEqualTo(RepairStatus.PENDING_ASSIGNMENT);
        assertThat(orders.getForAdmin(admin, id).workerUsername()).isNull();
    }
}
