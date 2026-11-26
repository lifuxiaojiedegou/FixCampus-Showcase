package com.fixcampus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.repository.RepairOrderRepository;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.NotificationService;
import com.fixcampus.service.RepairOrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("web-slice")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationIntegrationTest extends RealMySqlTestSupport {
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static final String ORDERS = "/api/v1/orders";
    private static final String WORKER_ORDERS = "/api/v1/worker/orders";
    private static final String ADMIN_ORDERS = "/api/v1/admin/orders";
    private static final String INBOX = "/api/v1/notifications";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired RepairOrderRepository orders;
    @Autowired RepairOrderService repairOrders;
    @Autowired NotificationService notifications;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;

    private final String loginInput = UUID.randomUUID().toString();
    private CampusPrincipal student;
    private CampusPrincipal otherStudent;
    private CampusPrincipal worker;
    private CampusPrincipal otherWorker;
    private CampusPrincipal admin;

    private record Browser(MockHttpSession session, String csrf) { }

    @BeforeAll
    void createAccounts() {
        String prefix = "notify_" + UUID.randomUUID().toString().substring(0, 8);
        String encoded = encoder.encode(loginInput);
        student = createAccount(prefix + "_sa", encoded, Role.STUDENT);
        otherStudent = createAccount(prefix + "_sb", encoded, Role.STUDENT);
        worker = createAccount(prefix + "_wa", encoded, Role.WORKER);
        otherWorker = createAccount(prefix + "_wb", encoded, Role.WORKER);
        admin = createAccount(prefix + "_ad", encoded, Role.ADMIN);
    }

    @AfterEach
    void deleteTestOrders() {
        jdbc.update("DELETE FROM idempotency_requests WHERE actor_id IN (?, ?)", student.getId(), otherStudent.getId());
        jdbc.update("""
                DELETE FROM notifications WHERE order_id IN (SELECT id FROM repair_orders WHERE student_id IN (?, ?))
                """, student.getId(), otherStudent.getId());
        jdbc.update("DELETE FROM repair_orders WHERE student_id IN (?, ?)", student.getId(), otherStudent.getId());
    }

    @AfterAll
    void deleteAccounts() {
        users.deleteAllById(List.of(student.getId(), otherStudent.getId(), worker.getId(), otherWorker.getId(), admin.getId()));
    }

    @Test
    void isolatesStudentNotificationsFromOtherAccounts() throws Exception {
        long orderId = existingOrder(true);
        write(login(worker), ORDERS + "/" + orderId + "/start", "{}").andExpect(status().isNoContent());
        var studentBrowser = login(student);
        JsonNode notice = latestNotice(studentBrowser, orderId, "REPAIR_STARTED");
        long noticeId = Long.parseLong(notice.path("id").asText());

        for (var actor : List.of(otherStudent, worker, admin)) {
            var outsider = login(actor);
            mvc.perform(get(INBOX).session(outsider.session())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.unreadCount").value(0));
            unreadCount(outsider, 0);
            write(outsider, INBOX + "/" + noticeId + "/read", "{}").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
            write(outsider, INBOX + "/read-all", "{}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.markedCount").value(0));
        }

        assertThat(readTime(noticeId)).isNull();
        unreadCount(studentBrowser, 1);
    }

    @Test
    void failedOrderTransitionsLeaveNoNotifications() throws Exception {
        long orderId = existingOrder(false);
        write(login(student), ORDERS + "/" + orderId + "/confirm", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_CONFLICT"));
        write(login(admin), ORDERS + "/" + orderId + "/assign", assignment(otherStudent))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WORKER"));

        assertThat(notificationCount(orderId)).isZero();
        assertThat(orderStatus(orderId)).isEqualTo("PENDING_ASSIGNMENT");
    }

    @Test
    void outerRollbackRevertsOrderAndNotificationWrites() {
        var transaction = new TransactionTemplate(transactions);
        Long createdId = transaction.execute(state -> {
            var form = new RepairOrderForm();
            form.setLocation("虚构回滚报修");
            form.setCategory(RepairCategory.OTHER);
            form.setDescription("外层事务回滚");
            long id = repairOrders.createForStudent(student, form);
            assertThat(notificationCount(id)).isPositive();
            state.setRollbackOnly();
            return id;
        });
        assertThat(orders.existsById(createdId)).isFalse();
        assertThat(notificationCount(createdId)).isZero();

        long existingId = existingOrder(false);
        transaction.executeWithoutResult(state -> {
            repairOrders.assign(admin, existingId, worker.getId());
            assertThat(notificationCount(existingId)).isEqualTo(2);
            state.setRollbackOnly();
        });
        assertThat(orderStatus(existingId)).isEqualTo("PENDING_ASSIGNMENT");
        assertThat(notificationCount(existingId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id = ?", Long.class, existingId))
                .isZero();
    }

    @Test
    void duplicatePublicationAndMarkingPreserveReadTime() throws Exception {
        var studentBrowser = login(student);
        var adminBrowser = login(admin);
        long orderId = submit(studentBrowser);
        JsonNode notice = latestNotice(adminBrowser, orderId, "ORDER_SUBMITTED");
        long noticeId = Long.parseLong(notice.path("id").asText());
        String path = INBOX + "/" + noticeId + "/read";

        JsonNode first = json(write(adminBrowser, path, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.toString(noticeId)))
                .andExpect(jsonPath("$.read").value(true)).andExpect(jsonPath("$.unreadCount").value(0)).andReturn());
        String firstReadAt = first.path("readAt").asText();
        assertThat(OffsetDateTime.parse(firstReadAt).getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        var storedReadAt = readTime(noticeId);

        new TransactionTemplate(transactions).executeWithoutResult(state -> {
            var order = orders.findById(orderId).orElseThrow();
            notifications.publish(order, OrderAction.SUBMITTED, student.getId(), order.getCreatedAt());
            notifications.publish(order, OrderAction.SUBMITTED, student.getId(), order.getCreatedAt());
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE order_id = ? AND recipient_id = ?",
                Long.class, orderId, admin.getId())).isEqualTo(1);
        assertThat(readTime(noticeId)).isEqualTo(storedReadAt);
        write(adminBrowser, path, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").value(firstReadAt)).andExpect(jsonPath("$.unreadCount").value(0));

        submit(studentBrowser);
        long latestId = submit(studentBrowser);
        mvc.perform(get(INBOX).session(adminBrowser.session()).param("size", "1").param("unreadOnly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(Long.toString(latestId)))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.unreadCount").value(2));
        write(adminBrowser, INBOX + "/read-all", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.markedCount").value(2)).andExpect(jsonPath("$.unreadCount").value(0));
        write(adminBrowser, INBOX + "/read-all", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.markedCount").value(0)).andExpect(jsonPath("$.unreadCount").value(0));
        assertThat(readTime(noticeId)).isEqualTo(storedReadAt);
    }

    @Test
    void threeRoleWorkflowNotifiesEachRecipientAndPreservesOrderVisibility() throws Exception {
        var studentBrowser = login(student);
        var adminBrowser = login(admin);
        var workerBrowser = login(worker);
        var otherWorkerBrowser = login(otherWorker);
        long orderId = submit(studentBrowser);
        String orderPath = ORDERS + "/" + orderId;

        latestNotice(adminBrowser, orderId, "ORDER_SUBMITTED");
        visibleOrder(adminBrowser, ADMIN_ORDERS, orderId);
        write(adminBrowser, orderPath + "/assign", assignment(worker)).andExpect(status().isNoContent());
        latestNotice(studentBrowser, orderId, "ORDER_ASSIGNED");
        latestNotice(workerBrowser, orderId, "ORDER_ASSIGNED");
        visibleOrder(studentBrowser, ORDERS, orderId);
        visibleOrder(workerBrowser, WORKER_ORDERS, orderId);
        mvc.perform(get(WORKER_ORDERS + "/" + orderId).session(otherWorkerBrowser.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        write(workerBrowser, orderPath + "/start", "{}").andExpect(status().isNoContent());
        latestNotice(studentBrowser, orderId, "REPAIR_STARTED");
        write(workerBrowser, orderPath + "/complete", "{\"repairResult\":\"已修复\"}").andExpect(status().isNoContent());
        latestNotice(studentBrowser, orderId, "REPAIR_COMPLETED");
        write(studentBrowser, orderPath + "/confirm", "{}").andExpect(status().isNoContent());
        latestNotice(workerBrowser, orderId, "ORDER_CONFIRMED");

        long cancelled = submit(studentBrowser);
        write(studentBrowser, ORDERS + "/" + cancelled + "/cancel", "{}").andExpect(status().isNoContent());
        latestNotice(adminBrowser, cancelled, "ORDER_CANCELLED");
        unreadCount(otherWorkerBrowser, 0);
    }

    private CampusPrincipal createAccount(String name, String encoded, Role role) {
        var actor = new CampusPrincipal(users.saveAndFlush(new User(name, encoded, name, null, role, LocalDateTime.now())));
        actor.eraseCredentials();
        return actor;
    }

    private Browser csrf(MockHttpSession session) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new Browser((MockHttpSession) result.getRequest().getSession(false), json(result).path("token").asText());
    }

    private Browser login(CampusPrincipal actor) throws Exception {
        var browser = csrf(null);
        write(browser, "/api/v1/auth/login",
                mapper.writeValueAsString(Map.of("username", actor.getUsername(), "password", loginInput)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(actor.getId().toString()));
        return csrf(browser.session());
    }

    private MockHttpServletRequestBuilder jsonPost(Browser browser, String path, String body) {
        return post(path).session(browser.session()).header("Origin", ORIGIN).header("X-CSRF-TOKEN", browser.csrf())
                .contentType("application/json").content(body);
    }

    private ResultActions write(Browser browser, String path, String body) throws Exception {
        return mvc.perform(jsonPost(browser, path, body));
    }

    private long submit(Browser browser) throws Exception {
        String body = """
                {"location":"虚构维修地点","category":"OTHER","description":"通知集成测试"}
                """;
        var result = mvc.perform(jsonPost(browser, ORDERS, body).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn();
        return Long.parseLong(json(result).path("id").asText());
    }

    private long existingOrder(boolean assigned) {
        var order = new RepairOrder(users.getReferenceById(student.getId()), "虚构维修地点", RepairCategory.OTHER,
                "通知集成测试", LocalDateTime.now());
        if (assigned) {
            order.assignTo(users.getReferenceById(worker.getId()));
        }
        return orders.saveAndFlush(order).getId();
    }

    private LocalDateTime readTime(long noticeId) {
        return jdbc.queryForObject("SELECT read_at FROM notifications WHERE id = ?", LocalDateTime.class, noticeId);
    }

    private long notificationCount(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE order_id = ?", Long.class, orderId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM repair_orders WHERE id = ?", String.class, orderId);
    }

    private JsonNode latestNotice(Browser browser, long orderId, String eventType) throws Exception {
        var result = mvc.perform(get(INBOX).session(browser.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value(Long.toString(orderId)))
                .andExpect(jsonPath("$.items[0].eventType").value(eventType))
                .andExpect(jsonPath("$.items[0].read").value(false)).andReturn();
        return json(result).path("items").get(0);
    }

    private void unreadCount(Browser browser, int expected) throws Exception {
        mvc.perform(get(INBOX + "/unread-count").session(browser.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(expected));
    }

    private void visibleOrder(Browser browser, String orderPath, long orderId) throws Exception {
        mvc.perform(get(orderPath + "/" + orderId).session(browser.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.toString(orderId)));
    }

    private static String assignment(CampusPrincipal actor) {
        return "{\"workerId\":\"" + actor.getId() + "\"}";
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
