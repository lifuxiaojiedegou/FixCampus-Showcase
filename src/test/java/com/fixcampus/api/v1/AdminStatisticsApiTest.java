package com.fixcampus.api.v1;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.security.SessionLifecycle;
import com.fixcampus.service.AdminStatisticsService;
import com.fixcampus.view.AdminStatistics;
import com.fixcampus.view.AdminStatistics.CategoryCount;
import com.fixcampus.view.AdminStatistics.DailyTrend;
import com.fixcampus.view.AdminStatistics.Durations;
import com.fixcampus.view.AdminStatistics.LocationAggregation;
import com.fixcampus.view.AdminStatistics.StatusCount;
import com.fixcampus.view.AdminStatistics.Turnaround;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static com.fixcampus.entity.RepairCategory.*;
import static com.fixcampus.entity.RepairStatus.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminStatisticsApi.class, properties = "fixcampus.web.origin=http://127.0.0.1:5173")
@ActiveProfiles("web-slice")
@Import(ApiSecurityConfig.class)
class AdminStatisticsApiTest {
    private static final String PATH = "/api/v1/admin/statistics";
    private final CampusPrincipal admin = principal(Role.ADMIN);

    @Autowired MockMvc mvc;
    @MockitoBean(name = "apiAuthenticationManager") AuthenticationManager authentication;
    @MockitoBean SessionLifecycle lifecycle;
    @MockitoBean AdminStatisticsService statistics;

    @Test
    void anonymousRequestRequiresAuthentication() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        verifyNoInteractions(statistics);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"STUDENT", "WORKER"})
    void onlyAdministratorsCanReadStatistics(Role role) throws Exception {
        mvc.perform(get(PATH).with(user(principal(role)))).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
        verifyNoInteractions(statistics);
    }

    @Test
    void adminReceivesTheExactStatisticsSchemaAndHeaders() throws Exception {
        var trend = LocalDate.parse("2026-11-20").datesUntil(LocalDate.parse("2026-11-27"))
                .map(date -> new DailyTrend(date.toString(), 0, 0, 0)).toList();
        when(statistics.query(admin, 7)).thenReturn(new AdminStatistics("2026-11-26T14:00+08:00",
                "Asia/Hong_Kong", 7, "2026-11-20", "2026-11-26", 2,
                List.of(new StatusCount(PENDING_ASSIGNMENT, 1), new StatusCount(PENDING_REPAIR, 0),
                        new StatusCount(IN_PROGRESS, 0), new StatusCount(PENDING_CONFIRMATION, 1),
                        new StatusCount(COMPLETED, 0), new StatusCount(CANCELLED, 0)),
                List.of(new CategoryCount(WATER_ELECTRICITY, 2), new CategoryCount(DOORS_FURNITURE, 0),
                        new CategoryCount(NETWORK, 0), new CategoryCount(AIR_CONDITIONING, 0), new CategoryCount(OTHER, 0)),
                trend, new Durations(new Turnaround(1, 0, new BigDecimal("3.5"), new BigDecimal("3.5"),
                        new BigDecimal("3.5")), new Turnaround(0, 0, null, null, null)),
                new LocationAggregation(false, "报修地点当前为自由文本字段，缺少可验证的结构化区域来源，暂不提供区域聚合。")));

        mvc.perform(get(PATH).with(user(admin)).queryParam("days", "7"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().exists(ApiRequestIdFilter.HEADER))
                .andExpect(content().json("""
                        {
                          "generatedAt":"2026-11-26T14:00+08:00", "timezone":"Asia/Hong_Kong",
                          "windowDays":7, "windowStartDate":"2026-11-20", "windowEndDate":"2026-11-26", "totalOrders":2,
                          "statusCounts":[{"status":"PENDING_ASSIGNMENT","count":1},{"status":"PENDING_REPAIR","count":0},
                            {"status":"IN_PROGRESS","count":0},{"status":"PENDING_CONFIRMATION","count":1},
                            {"status":"COMPLETED","count":0},{"status":"CANCELLED","count":0}],
                          "categoryCounts":[{"category":"WATER_ELECTRICITY","count":2},{"category":"DOORS_FURNITURE","count":0},
                            {"category":"NETWORK","count":0},{"category":"AIR_CONDITIONING","count":0},{"category":"OTHER","count":0}],
                          "dailyTrend":[
                            {"date":"2026-11-20","createdCount":0,"repairCompletedCount":0,"confirmedCount":0},
                            {"date":"2026-11-21","createdCount":0,"repairCompletedCount":0,"confirmedCount":0},
                            {"date":"2026-11-22","createdCount":0,"repairCompletedCount":0,"confirmedCount":0},
                            {"date":"2026-11-23","createdCount":0,"repairCompletedCount":0,"confirmedCount":0},
                            {"date":"2026-11-24","createdCount":0,"repairCompletedCount":0,"confirmedCount":0},
                            {"date":"2026-11-25","createdCount":0,"repairCompletedCount":0,"confirmedCount":0},
                            {"date":"2026-11-26","createdCount":0,"repairCompletedCount":0,"confirmedCount":0}
                          ],
                          "durations":{
                            "repairTurnaround":{"sampleCount":1,"missingEventCount":0,"averageMinutes":3.5,"minMinutes":3.5,"maxMinutes":3.5},
                            "confirmationTurnaround":{"sampleCount":0,"missingEventCount":0,"averageMinutes":null,"minMinutes":null,"maxMinutes":null}
                          },
                          "locationAggregation":{"supported":false,"reason":"报修地点当前为自由文本字段，缺少可验证的结构化区域来源，暂不提供区域聚合。"}
                        }
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"7", "14", "30"})
    void acceptsSupportedDaysAndDefaultsOnlyWhenAbsent(String days) throws Exception {
        var request = get(PATH).with(user(admin));
        if (days != null) {
            request.queryParam("days", days);
        }
        mvc.perform(request).andExpect(status().isOk());
        verify(statistics).query(admin, days == null ? 14 : Integer.parseInt(days));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "15", "-7", "365", "abc", "7.0", "", "2147483648"})
    void rejectsInvalidDaysIncludingAnExplicitEmptyQueryValue(String days) throws Exception {
        mvc.perform(get(PATH + "?days=" + days).with(user(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("统计天数仅支持 7、14 或 30"));
        verifyNoInteractions(statistics);
    }

    private static CampusPrincipal principal(Role role) {
        return new CampusPrincipal(new User("fixture", null, "统计测试", null, role, LocalDateTime.MIN));
    }
}
