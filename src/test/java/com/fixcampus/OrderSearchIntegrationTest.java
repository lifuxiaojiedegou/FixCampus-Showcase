package com.fixcampus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.form.OrderFilterForm;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.view.OrderSummary;
import com.fixcampus.view.WorkerTodoSummary;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderSearchIntegrationTest extends RealMySqlTestSupport {
    @Autowired MockMvc mvc;
    @Autowired RepairOrderService service;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    private final List<Long> ownedUsers = new ArrayList<>();
    private final Map<RepairStatus, Long> own = new EnumMap<>(RepairStatus.class);
    private final Map<RepairStatus, Long> foreign = new EnumMap<>(RepairStatus.class);
    private CampusPrincipal student, otherStudent, worker, otherWorker, admin;

    @BeforeAll
    void users() {
        String prefix = "search_" + UUID.randomUUID().toString().substring(0, 8);
        student = fixture(prefix + "_s", Role.STUDENT);
        otherStudent = fixture(prefix + "_s2", Role.STUDENT);
        worker = fixture(prefix + "_w", Role.WORKER);
        otherWorker = fixture(prefix + "_w2", Role.WORKER);
        admin = fixture(prefix + "_a", Role.ADMIN);
    }

    private CampusPrincipal fixture(String name, Role role) {
        var account = users.saveAndFlush(new User(name, passwords.encode(UUID.randomUUID().toString()),
                "查询测试" + role.getLabel(), null, role, LocalDateTime.now(clock)));
        ownedUsers.add(account.getId());
        var actor = new CampusPrincipal(account); actor.eraseCredentials(); return actor;
    }

    @BeforeEach
    void orders() {
        own.clear(); foreign.clear();
        for (RepairStatus state : RepairStatus.values()) {
            own.put(state, create(student, worker, state, location(state)));
            foreign.put(state, create(otherStudent, otherWorker, state, location(state) + " 他人专属"));
        }
    }

    private String location(RepairStatus state) {
        return switch (state) {
            case PENDING_ASSIGNMENT -> "A_100%! 一号楼";
            case PENDING_REPAIR -> "教学楼2026 一层";
            case IN_PROGRESS -> "教学楼2026 二层";
            case PENDING_CONFIRMATION -> "宿舍2026";
            case COMPLETED -> "宿舍已完成";
            case CANCELLED -> "其他地点";
        };
    }

    private Long create(CampusPrincipal owner, CampusPrincipal assigned, RepairStatus state, String location) {
        var form = new RepairOrderForm(); form.setLocation(location); form.setCategory(RepairCategory.OTHER);
        form.setDescription("查询夹具描述，不应通过越权搜索获取");
        Long id = service.createForStudent(owner, form);
        boolean hasWorker = state != RepairStatus.PENDING_ASSIGNMENT && state != RepairStatus.CANCELLED;
        jdbc.update("UPDATE repair_orders SET status=?,worker_id=?,created_at='2026-10-01 09:00:00',completed_at=? WHERE id=?",
                state.name(), hasWorker ? assigned.getId() : null,
                state == RepairStatus.COMPLETED ? LocalDateTime.of(2026, 10, 1, 10, 0) : null, id);
        return id;
    }

    @AfterEach
    void removeOrders() { for (Long id : ownedUsers) jdbc.update("DELETE FROM repair_orders WHERE student_id=?", id); }

    @AfterAll
    void removeUsers() { removeOrders(); for (Long id : ownedUsers) jdbc.update("DELETE FROM users WHERE id=?", id); }

    private CampusPrincipal actor(Role role) { return switch (role) { case STUDENT -> student; case WORKER -> worker; case ADMIN -> admin; }; }
    private String route(Role role) { return role.getHomePath(); }
    private OrderFilterForm filter(String status, String q) { var f = new OrderFilterForm(); f.setStatus(status); f.setQ(q); return f; }
    private List<OrderSummary> search(Role role, OrderFilterForm filter) {
        return switch (role) {
            case STUDENT -> service.searchForStudent(student, filter);
            case WORKER -> service.searchForWorker(worker, filter);
            case ADMIN -> service.searchForAdmin(admin, filter);
        };
    }

    @ParameterizedTest @EnumSource(Role.class)
    void statusAndLocationCombineWithinTheCurrentRoleScope(Role role) throws Exception {
        var f = filter("IN_PROGRESS", "  教学楼2026  ");
        var expected = role == Role.ADMIN
                ? List.of(foreign.get(RepairStatus.IN_PROGRESS), own.get(RepairStatus.IN_PROGRESS))
                : List.of(own.get(RepairStatus.IN_PROGRESS));
        assertThat(search(role, f)).extracting(OrderSummary::id).containsExactlyElementsOf(expected);
        mvc.perform(get(route(role)).with(user(actor(role))).param("status", f.getStatus()).param("q", "  教学楼2026  "))
                .andExpect(status().isOk()).andExpect(model().attribute("orders", search(role, f)))
                .andExpect(content().string(containsString("value=\"教学楼2026\"")));
    }

    @ParameterizedTest @EnumSource(Role.class)
    void exactIdNumericLocationAndLikeMetacharactersHaveDefinedSemantics(Role role) {
        Long id = own.get(RepairStatus.IN_PROGRESS);
        for (String q : List.of(id.toString(), "#" + id)) {
            assertThat(search(role, filter("", q))).extracting(OrderSummary::id).containsExactly(id);
        }
        assertThat(search(role, filter("", "2026"))).hasSize(role == Role.ADMIN ? 6 : 3);
        assertThat(search(role, filter("", "0"))).hasSize(role == Role.ADMIN ? 8 : role == Role.WORKER ? 3 : 4);
        for (String q : List.of("%", "_", "!", "100%!")) {
            var matches = search(role, filter("", q));
            if (role == Role.WORKER) assertThat(matches).isEmpty();
            else assertThat(matches).extracting(OrderSummary::id).containsExactlyElementsOf(role == Role.ADMIN
                    ? List.of(foreign.get(RepairStatus.PENDING_ASSIGNMENT), own.get(RepairStatus.PENDING_ASSIGNMENT))
                    : List.of(own.get(RepairStatus.PENDING_ASSIGNMENT)));
        }
        for (String q : List.of("9223372036854775808", "' OR 1=1 --", "\\")) {
            assertThat(search(role, filter("", q))).isEmpty();
        }
    }

    @ParameterizedTest @EnumSource(Role.class)
    void todoCountsAreScopedAndIndependentOfListFilters(Role role) throws Exception {
        var result = mvc.perform(get(route(role)).with(user(actor(role))).param("q", "不存在的地点"))
                .andExpect(status().isOk()).andExpect(model().attribute("orders", List.of()))
                .andExpect(content().string(containsString("没有符合条件的工单")));
        if (role == Role.WORKER) {
            result.andExpect(model().attribute("todo", new WorkerTodoSummary(1, 1)))
                    .andExpect(content().string(containsString("status=PENDING_REPAIR")))
                    .andExpect(content().string(containsString("status=IN_PROGRESS")));
        } else {
            result.andExpect(model().attribute("pendingCount", role == Role.STUDENT ? 1L : 2L))
                    .andExpect(content().string(containsString("status=" + (role == Role.STUDENT ? "PENDING_CONFIRMATION" : "PENDING_ASSIGNMENT"))));
        }
    }

    @Test
    void foreignIdsAndForgedIdentityParametersCannotExpandSearchOrCounts() throws Exception {
        for (Role role : List.of(Role.STUDENT, Role.WORKER)) {
            Long hidden = foreign.get(RepairStatus.IN_PROGRESS);
            mvc.perform(get(route(role)).with(user(actor(role))).param("q", hidden.toString())
                            .param("studentId", otherStudent.getId().toString()).param("workerId", otherWorker.getId().toString()).param("role", "ADMIN"))
                    .andExpect(status().isOk()).andExpect(model().attribute("orders", List.of()));
            assertThat(search(role, filter("", "他人专属"))).isEmpty();
        }
        for (CampusPrincipal wrong : List.of(worker, admin)) {
            assertThatThrownBy(() -> service.searchForStudent(wrong, filter("", ""))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.countAwaitingConfirmation(wrong)).isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.searchForAdmin(student, filter("", ""))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.countAwaitingAssignment(worker)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.searchForWorker(student, filter("", ""))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.workerTodo(admin)).isInstanceOf(AccessDeniedException.class);
        for (Role role : Role.values()) mvc.perform(get(route(role)).param("q", "教学楼")).andExpect(status().isFound());
    }

    @Test
    void studentSeesSixStatusesAndWorkerOnlyFourAssignedStates() throws Exception {
        for (RepairStatus state : RepairStatus.values()) {
            mvc.perform(get(route(Role.STUDENT)).with(user(student)).param("status", state.name()))
                    .andExpect(status().isOk()).andExpect(model().attribute("orders", search(Role.STUDENT, filter(state.name(), ""))));
            boolean assigned = state != RepairStatus.PENDING_ASSIGNMENT && state != RepairStatus.CANCELLED;
            mvc.perform(get(route(Role.WORKER)).with(user(worker)).param("status", state.name()))
                    .andExpect(status().is(assigned ? 200 : 400))
                    .andExpect(model().attribute("orders", assigned ? search(Role.WORKER, filter(state.name(), "")) : List.of()));
        }
    }

    @Test
    void invalidFiltersReturnChinese400AndServiceAlsoValidatesCodePointLimit() throws Exception {
        for (Role role : Role.values()) {
            mvc.perform(get(route(role)).with(user(actor(role))).param("status", "UNKNOWN"))
                    .andExpect(status().isBadRequest()).andExpect(model().attribute("orders", List.of()))
                    .andExpect(content().string(containsString("请选择有效的工单状态")));
            mvc.perform(get(route(role)).with(user(actor(role))).param("q", "🔧".repeat(121)))
                    .andExpect(status().isBadRequest()).andExpect(model().attribute("orders", List.of()))
                    .andExpect(content().string(containsString("搜索内容最多 120 个字符")));
            mvc.perform(get(route(role)).with(user(actor(role))).param("q", "🔧".repeat(120)))
                    .andExpect(status().isOk()).andExpect(model().attribute("orders", List.of()));
            assertThatThrownBy(() -> search(role, filter("", "🔧".repeat(121)))).isInstanceOf(ConstraintViolationException.class);
            assertThatThrownBy(() -> search(role, filter("UNKNOWN", ""))).isInstanceOf(ConstraintViolationException.class);
        }
    }

    @Test
    void htmlInputIsEscapedAndSearchNeverMutatesOrders() throws Exception {
        Long id = create(student, worker, RepairStatus.PENDING_ASSIGNMENT, "<script>查询地点</script>");
        var before = jdbc.queryForList("SELECT * FROM repair_orders ORDER BY id");
        mvc.perform(get(route(Role.STUDENT)).with(user(student)).param("q", "<script>"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>查询地点"))))
                .andExpect(model().attribute("orders", search(Role.STUDENT, filter("", "<script>"))));
        assertThat(search(Role.STUDENT, filter("", "<script>"))).extracting(OrderSummary::id).containsExactly(id);
        assertThat(jdbc.queryForList("SELECT * FROM repair_orders ORDER BY id")).isEqualTo(before);
    }

    @Test
    void matchingOrdersKeepStableNewestFirstOrdering() {
        assertThat(search(Role.STUDENT, filter("", "教学楼2026"))).extracting(OrderSummary::id)
                .containsExactly(own.get(RepairStatus.IN_PROGRESS), own.get(RepairStatus.PENDING_REPAIR));
        assertThat(search(Role.WORKER, filter("COMPLETED", ""))).extracting(OrderSummary::id)
                .containsExactly(own.get(RepairStatus.COMPLETED));
    }
}
