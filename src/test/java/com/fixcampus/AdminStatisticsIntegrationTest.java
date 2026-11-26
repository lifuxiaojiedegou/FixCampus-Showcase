package com.fixcampus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.repository.RepairOrderRepository;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.AdminStatisticsService;
import com.fixcampus.view.AdminStatistics.CategoryCount;
import com.fixcampus.view.AdminStatistics.DailyTrend;
import com.fixcampus.view.AdminStatistics.StatusCount;
import com.fixcampus.view.AdminStatistics.Turnaround;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static com.fixcampus.entity.OrderAction.CONFIRMED;
import static com.fixcampus.entity.OrderAction.REPAIR_COMPLETED;
import static com.fixcampus.entity.OrderAction.SUBMITTED;
import static com.fixcampus.entity.RepairCategory.*;
import static com.fixcampus.entity.RepairStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("web-slice")
@EnabledIfEnvironmentVariable(named = "FIXCAMPUS_DB_NAME", matches = "fixcampus_[a-z0-9_]+_test")
@TestPropertySource(properties = "fixcampus.web.origin=http://127.0.0.1:5173")
@Import(AdminStatisticsIntegrationTest.TimeConfiguration.class)
class AdminStatisticsIntegrationTest extends RealMySqlTestSupport {
    private static final String PATH = "/api/v1/admin/statistics";
    private static final String ORIGIN = "http://127.0.0.1:5173";

    @Autowired AdminStatisticsService statistics;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RepairOrderRepository orders;
    @Autowired PasswordEncoder passwords;
    @Autowired Clock clock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final String password = UUID.randomUUID().toString();
    private final List<Long> ownedUsers = new ArrayList<>();
    private final List<Long> ownedOrders = new ArrayList<>();
    private User student;
    private User worker;
    private User admin;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary Clock adminStatisticsClock() {
            return Clock.fixed(Instant.parse("2026-11-26T15:59:59.999999Z"), ZoneId.of("Asia/Hong_Kong"));
        }
    }

    @BeforeEach
    void createAccountsInAnEmptyOrderDatabase() {
        assertThat(orders.count()).as("statistics tests require no pre-existing orders in the isolated schema").isZero();
        String prefix = "stats_" + UUID.randomUUID().toString().substring(0, 8);
        student = account(prefix + "_s", Role.STUDENT);
        worker = account(prefix + "_w", Role.WORKER);
        admin = account(prefix + "_a", Role.ADMIN);
    }

    @AfterEach
    void removeOnlyTestFixtures() {
        for (long id : ownedOrders) {
            jdbc.update("DELETE FROM repair_orders WHERE id = ?", id);
        }
        for (long id : ownedUsers) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void emptyDatabaseReturnsZeroCountsAndNullDurations() {
        var result = statistics.query(new CampusPrincipal(admin), 14);

        assertThat(result.totalOrders()).isZero();
        assertThat(result.statusCounts()).extracting(StatusCount::status).containsExactly(RepairStatus.values());
        assertThat(result.statusCounts()).extracting(StatusCount::count).containsOnly(0L);
        assertThat(result.categoryCounts()).extracting(CategoryCount::category).containsExactly(RepairCategory.values());
        assertThat(result.categoryCounts()).extracting(CategoryCount::count).containsOnly(0L);
        assertThat(result.dailyTrend()).containsExactlyElementsOf(LocalDate.parse("2026-11-13")
                .datesUntil(LocalDate.parse("2026-11-27")).map(date -> new DailyTrend(date.toString(), 0, 0, 0)).toList());
        assertThat(result.durations().repairTurnaround()).isEqualTo(new Turnaround(0, 0, null, null, null));
        assertThat(result.durations().confirmationTurnaround()).isEqualTo(new Turnaround(0, 0, null, null, null));
    }

    @Test
    void sessionsEnforceRolesAndAdminReceivesRealSqlAggregates() throws Exception {
        var start = LocalDateTime.parse("2026-11-20T00:00");
        var end = LocalDateTime.parse("2026-11-26T23:59:59.999999");
        var afterWindow = LocalDateTime.parse("2026-11-27T00:00");
        order(PENDING_ASSIGNMENT, WATER_ELECTRICITY, start, null);
        order(PENDING_REPAIR, DOORS_FURNITURE, start.minusNanos(1_000), null);
        order(IN_PROGRESS, NETWORK, afterWindow, null);
        order(CANCELLED, AIR_CONDITIONING, end, null);

        var pendingStart = LocalDateTime.parse("2026-11-21T10:00");
        long pending = order(PENDING_CONFIRMATION, OTHER, pendingStart, null);
        event(pending, SUBMITTED, pendingStart);
        event(pending, REPAIR_COMPLETED, pendingStart.plusSeconds(210));

        var crossingStart = LocalDateTime.parse("2026-11-22T23:59");
        completedOrderWithHistory(WATER_ELECTRICITY, crossingStart, crossingStart.plusMinutes(1),
                LocalDateTime.parse("2026-11-24T00:00"));
        var beforeWindow = start.minusDays(1);
        completedOrderWithHistory(DOORS_FURNITURE, beforeWindow, beforeWindow, beforeWindow);
        completedOrderWithHistory(OTHER, afterWindow, afterWindow.plusSeconds(3), afterWindow.plusSeconds(3));

        var partialStart = LocalDateTime.parse("2026-11-25T09:00");
        order(COMPLETED, NETWORK, partialStart, partialStart.plusMinutes(11));
        long partial = order(PENDING_CONFIRMATION, AIR_CONDITIONING, partialStart, null);
        event(partial, SUBMITTED, partialStart);

        long reversed = order(COMPLETED, OTHER, partialStart, partialStart.plusMinutes(5));
        event(reversed, SUBMITTED, partialStart);
        event(reversed, REPAIR_COMPLETED, partialStart.minusNanos(1_000));
        event(reversed, CONFIRMED, partialStart.minusNanos(1_000));

        mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        for (var actor : List.of(student, worker)) {
            mvc.perform(get(PATH).session(login(actor))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
        }
        mvc.perform(get(PATH).queryParam("days", "7").session(login(admin)))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(content().json("""
                        {
                          "generatedAt":"2026-11-26T23:59:59.999999+08:00", "timezone":"Asia/Hong_Kong",
                          "windowDays":7, "windowStartDate":"2026-11-20", "windowEndDate":"2026-11-26", "totalOrders":11,
                          "statusCounts":[{"status":"PENDING_ASSIGNMENT","count":1},{"status":"PENDING_REPAIR","count":1},
                            {"status":"IN_PROGRESS","count":1},{"status":"PENDING_CONFIRMATION","count":2},
                            {"status":"COMPLETED","count":5},{"status":"CANCELLED","count":1}],
                          "categoryCounts":[{"category":"WATER_ELECTRICITY","count":2},{"category":"DOORS_FURNITURE","count":2},
                            {"category":"NETWORK","count":2},{"category":"AIR_CONDITIONING","count":2},{"category":"OTHER","count":3}],
                          "dailyTrend":[
                            {"date":"2026-11-20","createdCount":1,"repairCompletedCount":0,"confirmedCount":0},
                            {"date":"2026-11-21","createdCount":1,"repairCompletedCount":1,"confirmedCount":0},
                            {"date":"2026-11-22","createdCount":1,"repairCompletedCount":0,"confirmedCount":0},
                            {"date":"2026-11-23","createdCount":0,"repairCompletedCount":1,"confirmedCount":0},
                            {"date":"2026-11-24","createdCount":0,"repairCompletedCount":0,"confirmedCount":1},
                            {"date":"2026-11-25","createdCount":3,"repairCompletedCount":1,"confirmedCount":1},
                            {"date":"2026-11-26","createdCount":1,"repairCompletedCount":0,"confirmedCount":0}
                          ],
                          "durations":{
                            "repairTurnaround":{"sampleCount":4,"missingEventCount":3,"averageMinutes":1.1,"minMinutes":0.0,"maxMinutes":3.5},
                            "confirmationTurnaround":{"sampleCount":3,"missingEventCount":2,"averageMinutes":480.4,"minMinutes":0.0,"maxMinutes":1441.0}
                          },
                          "locationAggregation":{"supported":false,"reason":"报修地点当前为自由文本字段，缺少可验证的结构化区域来源，暂不提供区域聚合。"}
                        }
                        """, JsonCompareMode.STRICT));
    }

    private User account(String username, Role role) {
        var user = users.saveAndFlush(new User(username, passwords.encode(password), "虚构统计测试", null, role,
                LocalDateTime.now(clock)));
        ownedUsers.add(user.getId());
        return user;
    }

    private long order(RepairStatus status, RepairCategory category, LocalDateTime createdAt, LocalDateTime completedAt) {
        var order = new RepairOrder(student, "虚构报修地点", category, "虚构报修内容", createdAt);
        switch (status) {
            case PENDING_ASSIGNMENT -> { }
            case CANCELLED -> order.cancel();
            case PENDING_REPAIR -> order.assignTo(worker);
            case IN_PROGRESS -> {
                order.assignTo(worker);
                order.startRepair();
            }
            case PENDING_CONFIRMATION, COMPLETED -> {
                order.assignTo(worker);
                order.startRepair();
                order.submitRepair("处理完成");
                if (status == COMPLETED) {
                    order.confirmCompletion(completedAt);
                }
            }
        }
        long id = orders.saveAndFlush(order).getId();
        ownedOrders.add(id);
        return id;
    }

    private void completedOrderWithHistory(RepairCategory category, LocalDateTime submittedAt, LocalDateTime repairedAt,
            LocalDateTime confirmedAt) {
        long id = order(COMPLETED, category, submittedAt, confirmedAt);
        event(id, SUBMITTED, submittedAt);
        event(id, REPAIR_COMPLETED, repairedAt);
        event(id, CONFIRMED, confirmedAt);
    }

    private void event(long orderId, OrderAction action, LocalDateTime occurredAt) {
        var actor = action == REPAIR_COMPLETED ? worker : student;
        jdbc.update("""
                INSERT INTO order_events (order_id, action, actor_id, actor_role, actor_name, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, orderId, action.name(), actor.getId(), actor.getRole().name(), actor.getRealName(), occurredAt);
    }

    private MockHttpSession login(User account) throws Exception {
        var csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var token = mapper.readTree(csrf.getResponse().getContentAsString());
        var session = (MockHttpSession) csrf.getRequest().getSession(false);
        var login = mvc.perform(post("/api/v1/auth/login").session(session).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(token.get("headerName").asText(), token.get("token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", account.getUsername(), "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(account.getRole().name())).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }
}
