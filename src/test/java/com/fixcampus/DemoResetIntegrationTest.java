package com.fixcampus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fixcampus.api.v1.StudentOrders;
import com.fixcampus.config.DemoDataInitializer;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.repository.OrderEventRepository;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.DemoAccountService;
import com.fixcampus.service.DemoResetService;
import com.fixcampus.service.DemoResetService.ResetSummary;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.view.OrderDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import static com.fixcampus.entity.OrderAction.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

@ActiveProfiles("web-slice")
@TestPropertySource(properties = {"fixcampus.demo.enabled=true", "fixcampus.demo.reset.enabled=true",
        "fixcampus.demo.reset.cron=-"})
@Import(DemoResetIntegrationTest.TimeConfiguration.class)
class DemoResetIntegrationTest extends RealMySqlTestSupport {
    @Autowired DemoResetService reset;
    @Autowired DemoAccountService accounts;
    @Autowired UserRepository users;
    @Autowired RepairOrderService orders;
    @Autowired StudentOrders studentOrders;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired Clock clock;
    @MockitoSpyBean OrderEventRepository events;
    // Use the real account service in fixture setup so the cached Spring context owns no database rows.
    @MockitoBean(enforceOverride = true) DemoDataInitializer startupInitializer;

    private final List<Long> ownedUsers = new ArrayList<>();
    private final List<Long> ownedOrders = new ArrayList<>();
    private User student;
    private User worker;
    private User admin;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary Clock demoResetClock() {
            return Clock.fixed(Instant.parse("2026-11-26T20:00:00Z"), ZoneId.of("Asia/Hong_Kong"));
        }
    }

    @BeforeEach
    void prepareDemoAccountsAndVisitorOrder() {
        var missing = List.of("student01", "admin01", "worker01").stream()
                .filter(username -> !users.existsByUsername(username)).toList();
        accounts.initialize(Map.of(Role.STUDENT, UUID.randomUUID().toString(), Role.ADMIN, UUID.randomUUID().toString(),
                Role.WORKER, UUID.randomUUID().toString()));
        missing.forEach(username -> ownedUsers.add(account(username).getId()));
        student = account("student01");
        admin = account("admin01");
        worker = account("worker01");

        String name = "reset_" + UUID.randomUUID().toString().substring(0, 8);
        var visitor = users.saveAndFlush(new User(name, passwords.encode(UUID.randomUUID().toString()),
                "虚构访客", null, Role.STUDENT, LocalDateTime.now(clock)));
        ownedUsers.add(visitor.getId());
        var form = new RepairOrderForm();
        form.setLocation("访客报修测试");
        form.setCategory(RepairCategory.OTHER);
        form.setDescription("定时重置前创建");
        ownedOrders.add(Long.valueOf(studentOrders.create(principal(visitor), form, UUID.randomUUID().toString())));
    }

    @AfterEach
    void removeCommittedFixtures() {
        if (TestTransaction.isActive()) {
            return; // The success case rolls back fixture setup and both resets together.
        }
        for (Long id : ownedOrders) {
            jdbc.update("DELETE FROM idempotency_requests WHERE order_id = ?", id);
            jdbc.update("DELETE FROM notifications WHERE order_id = ?", id);
            jdbc.update("DELETE FROM repair_orders WHERE id = ?", id); // Events cascade by order ID.
        }
        for (Long id : ownedUsers) {
            jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    @Transactional
    void resetReplacesVisitorDataWithTheSameCompleteDemoBaselineOnEveryRun() {
        var originalUsers = userRows();
        assertThat(jdbc.queryForList("SELECT id FROM notifications WHERE order_id = ?", Long.class,
                ownedOrders.getFirst())).isNotEmpty();
        var expected = new ResetSummary(count("idempotency_requests"), count("order_events"),
                count("repair_orders"), 6, 17);

        assertThat(reset.resetDemoData()).isEqualTo(expected);
        var first = assertDemoBaseline(ownedOrders.getFirst());
        jdbc.update("UPDATE notifications SET read_at = ? WHERE recipient_id = ?", LocalDateTime.now(clock), admin.getId());
        assertThat(userRows().equals(originalUsers)).as("all user rows, including password hashes").isTrue();

        assertThat(reset.resetDemoData()).isEqualTo(new ResetSummary(0, 17, 6, 6, 17));
        var second = assertDemoBaseline(ownedOrders.getFirst());
        assertThat(second.stream().map(OrderDetail::history)).containsExactlyElementsOf(first.stream().map(OrderDetail::history).toList());
        assertThat(userRows().equals(originalUsers)).as("all user rows, including password hashes").isTrue();
    }

    @Test
    void failureAfterPartialSeedingRollsBackAllDeletesAndInserts() {
        // Run outside a test transaction so a post-failure read observes the service's rollback.
        var originalUsers = userRows();
        var originalOrders = jdbc.queryForList("SELECT * FROM repair_orders ORDER BY id");
        var originalOrderIds = jdbc.queryForList("SELECT id FROM repair_orders ORDER BY id", Long.class);
        var originalEvents = jdbc.queryForList("SELECT * FROM order_events ORDER BY id");
        var originalRequests = idempotencyRows();
        var originalNotifications = notificationRows();
        var failure = new DataAccessResourceFailureException("Forced fixture seeding failure");
        doAnswer(call -> {
            var currentOrderIds = jdbc.queryForList("SELECT id FROM repair_orders ORDER BY id", Long.class);
            ownedOrders.addAll(currentOrderIds.stream().filter(id -> !originalOrderIds.contains(id)).toList());
            assertThat(currentOrderIds).hasSize(2).doesNotContainAnyElementsOf(originalOrderIds);
            assertThat(count("order_events")).isEqualTo(1);
            assertThat(count("notifications")).isEqualTo(adminCount());
            assertThat(count("idempotency_requests")).isZero();
            throw failure;
        }).when(events).saveAllAndFlush(argThat(batch -> batch instanceof List<?> list && list.size() == 2));

        assertThatThrownBy(reset::resetDemoData).isSameAs(failure);
        assertThat(jdbc.queryForList("SELECT * FROM repair_orders ORDER BY id")).isEqualTo(originalOrders);
        assertThat(jdbc.queryForList("SELECT * FROM order_events ORDER BY id")).isEqualTo(originalEvents);
        assertThat(idempotencyRows()).isEqualTo(originalRequests);
        assertThat(notificationRows()).isEqualTo(originalNotifications);
        assertThat(userRows().equals(originalUsers)).as("all user rows, including password hashes").isTrue();
    }

    private List<OrderDetail> assertDemoBaseline(long visitorOrderId) {
        var ids = jdbc.queryForList("SELECT id FROM repair_orders ORDER BY id", Long.class);
        assertThat(ids).hasSize(6).doesNotContain(visitorOrderId);
        assertThat(count("idempotency_requests")).isZero();
        assertThat(count("order_events")).isEqualTo(17);
        var notifications = notificationRows();
        assertThat(notifications).hasSize(14 + 7 * adminCount()).allSatisfy(row -> {
            assertThat(ids).contains((Long) row.get("order_id"));
            assertThat(row.get("read_at")).isNull();
            assertThat(row.get("idempotency_key")).isEqualTo("order:" + row.get("order_id") + ":"
                    + row.get("event_type") + ":recipient:" + row.get("recipient_id"));
        });
        assertThat(eventsFor(student)).containsExactlyInAnyOrder("ORDER_ASSIGNED", "ORDER_ASSIGNED", "ORDER_ASSIGNED",
                "ORDER_ASSIGNED", "REPAIR_STARTED", "REPAIR_STARTED", "REPAIR_STARTED", "REPAIR_COMPLETED",
                "REPAIR_COMPLETED");
        assertThat(eventsFor(worker)).containsExactlyInAnyOrder("ORDER_ASSIGNED", "ORDER_ASSIGNED", "ORDER_ASSIGNED",
                "ORDER_ASSIGNED", "ORDER_CONFIRMED");
        assertThat(eventsFor(admin)).containsExactlyInAnyOrder("ORDER_SUBMITTED", "ORDER_SUBMITTED", "ORDER_SUBMITTED",
                "ORDER_SUBMITTED", "ORDER_SUBMITTED", "ORDER_SUBMITTED", "ORDER_CANCELLED");
        var details = ids.stream().map(id -> orders.getForStudent(principal(student), id)).toList();
        assertThat(details).extracting(DemoOrder::from).containsExactly(
                new DemoOrder("演示宿舍 A101", RepairCategory.AIR_CONDITIONING, "【课程演示】空调启动后不制冷，请检查。",
                        RepairStatus.PENDING_ASSIGNMENT, null, List.of(SUBMITTED)),
                new DemoOrder("演示教学楼 B202", RepairCategory.NETWORK, "【课程演示】教室网络插口无法连接。",
                        RepairStatus.PENDING_REPAIR, null, List.of(SUBMITTED, ASSIGNED)),
                new DemoOrder("演示图书馆 C303", RepairCategory.DOORS_FURNITURE, "【课程演示】阅览室门锁卡顿。",
                        RepairStatus.IN_PROGRESS, null, List.of(SUBMITTED, ASSIGNED, REPAIR_STARTED)),
                new DemoOrder("演示实验楼 D404", RepairCategory.WATER_ELECTRICITY, "【课程演示】照明灯闪烁。",
                        RepairStatus.PENDING_CONFIRMATION, "已更换灯管，等待学生核对。",
                        List.of(SUBMITTED, ASSIGNED, REPAIR_STARTED, REPAIR_COMPLETED)),
                new DemoOrder("演示体育馆 E505", RepairCategory.OTHER, "【课程演示】储物柜把手松动。",
                        RepairStatus.COMPLETED, "已紧固把手并检查使用情况。",
                        List.of(SUBMITTED, ASSIGNED, REPAIR_STARTED, REPAIR_COMPLETED, CONFIRMED)),
                new DemoOrder("演示食堂 F606", RepairCategory.WATER_ELECTRICITY, "【课程演示】水龙头滴水，已自行确认无需报修。",
                        RepairStatus.CANCELLED, null, List.of(SUBMITTED, CANCELLED)));
        details.forEach(this::assertTimeline);
        return details;
    }

    private void assertTimeline(OrderDetail order) {
        var history = order.history();
        assertThat(history.incomplete()).isFalse();
        assertThat(orders.getForAdmin(principal(admin), order.id()).history()).isEqualTo(history);
        var actors = jdbc.query("SELECT action, actor_id FROM order_events WHERE order_id = ? ORDER BY id",
                (rs, index) -> new EventActor(OrderAction.valueOf(rs.getString("action")), rs.getLong("actor_id")),
                order.id());
        assertThat(actors).hasSize(history.entries().size());
        assertThat(order.createdAt()).isEqualTo(history.entries().getFirst().occurredAt());
        assertThat(history.entries()).extracting(entry -> entry.occurredAt()).isSorted().doesNotHaveDuplicates()
                .allSatisfy(time -> assertThat(time).isBetween(order.createdAt(), LocalDateTime.now(clock)));
        for (int index = 0; index < history.entries().size(); index++) {
            var entry = history.entries().get(index);
            var actor = switch (entry.action()) {
                case SUBMITTED, CONFIRMED, CANCELLED -> student;
                case ASSIGNED -> admin;
                case REPAIR_STARTED, REPAIR_COMPLETED -> worker;
            };
            assertThat(actors.get(index)).isEqualTo(new EventActor(entry.action(), actor.getId()));
            assertThat(entry.actorRole()).isEqualTo(actor.getRole());
            assertThat(entry.actorName()).isEqualTo(actor.getRealName());
            if (entry.action() == REPAIR_COMPLETED) {
                assertThat(entry.detail()).isEqualTo(order.repairResult());
            }
        }
        if (order.status() == RepairStatus.COMPLETED) {
            assertThat(order.completedAt()).isEqualTo(history.entries().getLast().occurredAt());
        } else {
            assertThat(order.completedAt()).isNull();
        }
    }

    private record EventActor(OrderAction action, long actorId) { }

    private record DemoOrder(String location, RepairCategory category, String description, RepairStatus status,
                             String repairResult, List<OrderAction> actions) {
        static DemoOrder from(OrderDetail order) {
            return new DemoOrder(order.location(), order.category(), order.description(), order.status(),
                    order.repairResult(), order.history().entries().stream().map(entry -> entry.action()).toList());
        }
    }

    private User account(String name) { return users.findByUsername(name).orElseThrow(); }

    private static CampusPrincipal principal(User user) {
        var principal = new CampusPrincipal(user);
        principal.eraseCredentials();
        return principal;
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private int adminCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'", Integer.class);
    }
    private List<Map<String, Object>> notificationRows() {
        return jdbc.queryForList("SELECT * FROM notifications ORDER BY id");
    }
    private List<String> eventsFor(User recipient) {
        return jdbc.queryForList("SELECT event_type FROM notifications WHERE recipient_id = ?",
                String.class, recipient.getId());
    }

    private List<Map<String, Object>> userRows() { return jdbc.queryForList("SELECT * FROM users ORDER BY id"); }

    private List<Map<String, Object>> idempotencyRows() {
        return jdbc.queryForList("""
                SELECT id, actor_id, operation_code, request_key, HEX(payload_hash) AS payload_hash, order_id, created_at
                FROM idempotency_requests ORDER BY id
                """);
    }
}
