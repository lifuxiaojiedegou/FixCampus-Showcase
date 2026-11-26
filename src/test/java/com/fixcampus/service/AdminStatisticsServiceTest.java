package com.fixcampus.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.entity.Role;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.view.AdminStatistics.CategoryCount;
import com.fixcampus.view.AdminStatistics.DailyTrend;
import com.fixcampus.view.AdminStatistics.StatusCount;
import com.fixcampus.view.AdminStatistics.Turnaround;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminStatisticsServiceTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T20:00:00Z"), ZoneId.of("Asia/Hong_Kong"));
    private final AdminStatisticsService statistics = new AdminStatisticsService(jdbc, clock);
    private final CampusPrincipal admin = principal(Role.ADMIN);

    @ParameterizedTest
    @NullSource
    @EnumSource(value = Role.class, names = {"STUDENT", "WORKER"})
    void deniesMissingAndNonAdminActorsBeforeQuerying(Role role) {
        assertThatThrownBy(() -> statistics.query(role == null ? null : principal(role), 14))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -7, 15, 365})
    void rejectsUnsupportedDaysBeforeQuerying(int days) {
        assertThatThrownBy(() -> statistics.query(admin, days)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void emptyAggregatesKeepEveryEnumAndDateAndUseNullDurations() throws Exception {
        aggregate("REPAIR_COMPLETED", List.of("PENDING_CONFIRMATION", "COMPLETED"), 0, 0, null, null, null);
        aggregate("CONFIRMED", List.of("COMPLETED"), 0, 0, null, null, null);

        var result = statistics.query(admin, 7);

        assertThat(result.generatedAt()).isEqualTo("2026-11-27T04:00+08:00");
        assertThat(result.timezone()).isEqualTo("Asia/Hong_Kong");
        assertThat(result.windowDays()).isEqualTo(7);
        assertThat(result.windowStartDate()).isEqualTo("2026-11-21");
        assertThat(result.windowEndDate()).isEqualTo("2026-11-27");
        assertThat(result.totalOrders()).isZero();
        assertThat(result.statusCounts()).extracting(StatusCount::status).containsExactly(RepairStatus.values());
        assertThat(result.statusCounts()).extracting(StatusCount::count).containsOnly(0L);
        assertThat(result.categoryCounts()).extracting(CategoryCount::category).containsExactly(RepairCategory.values());
        assertThat(result.categoryCounts()).extracting(CategoryCount::count).containsOnly(0L);
        assertThat(result.dailyTrend()).containsExactlyElementsOf(LocalDate.parse("2026-11-21")
                .datesUntil(LocalDate.parse("2026-11-28")).map(date -> new DailyTrend(date.toString(), 0, 0, 0)).toList());
        assertThat(result.durations().repairTurnaround()).isEqualTo(new Turnaround(0, 0, null, null, null));
        assertThat(result.durations().confirmationTurnaround()).isEqualTo(new Turnaround(0, 0, null, null, null));
        verify(jdbc).query(anyString(), eq(Map.of("windowStart", LocalDateTime.parse("2026-11-21T00:00"),
                "windowEnd", LocalDateTime.parse("2026-11-28T00:00"))), ArgumentMatchers.<RowMapper<DailyTrend>>any());
    }

    @Test
    void roundsDecimalMicrosecondsAndReportsMissingSamplesIndependently() throws Exception {
        aggregate("REPAIR_COMPLETED", List.of("PENDING_CONFIRMATION", "COMPLETED"), 5, 2,
                "105000000", "0", "210000000");
        aggregate("CONFIRMED", List.of("COMPLETED"), 3, 2, "2999999.5", "2999999", "3000000");

        var durations = statistics.query(admin, 14).durations();

        assertThat(durations.repairTurnaround()).isEqualTo(new Turnaround(2, 3,
                new BigDecimal("1.8"), new BigDecimal("0.0"), new BigDecimal("3.5")));
        assertThat(durations.confirmationTurnaround()).isEqualTo(new Turnaround(2, 1,
                new BigDecimal("0.0"), new BigDecimal("0.0"), new BigDecimal("0.1")));
    }

    @Test
    void normalizesCaseInsensitiveStatusAndCategoryKeys() throws Exception {
        var statusRow1 = mock(ResultSet.class);
        when(statusRow1.getString(1)).thenReturn("completed");
        when(statusRow1.getLong(2)).thenReturn(2L);
        var statusRow2 = mock(ResultSet.class);
        when(statusRow2.getString(1)).thenReturn("COMPLETED");
        when(statusRow2.getLong(2)).thenReturn(1L);
        var categoryRow = mock(ResultSet.class);
        when(categoryRow.getString(1)).thenReturn("network");
        when(categoryRow.getLong(2)).thenReturn(3L);
        when(jdbc.query(contains("repair_orders GROUP BY UPPER(status)"),
                ArgumentMatchers.<RowMapper<Map.Entry<String, Long>>>any())).thenAnswer(invocation -> {
                    RowMapper<Map.Entry<String, Long>> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(statusRow1, 0), mapper.mapRow(statusRow2, 1));
                });
        when(jdbc.query(contains("repair_orders GROUP BY UPPER(category)"),
                ArgumentMatchers.<RowMapper<Map.Entry<String, Long>>>any())).thenAnswer(invocation -> {
                    RowMapper<Map.Entry<String, Long>> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(categoryRow, 0));
                });
        aggregate("REPAIR_COMPLETED", List.of("PENDING_CONFIRMATION", "COMPLETED"), 3, 0, null, null, null);
        aggregate("CONFIRMED", List.of("COMPLETED"), 3, 0, null, null, null);

        var result = statistics.query(admin, 7);

        assertThat(result.totalOrders()).isEqualTo(3L);
        assertThat(result.statusCounts()).filteredOn(c -> c.status() == RepairStatus.COMPLETED)
                .extracting(StatusCount::count).containsExactly(3L);
        assertThat(result.categoryCounts()).filteredOn(c -> c.category() == RepairCategory.NETWORK)
                .extracting(CategoryCount::count).containsExactly(3L);
    }

    private void aggregate(String action, List<String> statuses, long eligible, long samples,
            String average, String minimum, String maximum) throws Exception {
        var row = mock(ResultSet.class);
        when(row.getLong("eligible_count")).thenReturn(eligible);
        when(row.getLong("sample_count")).thenReturn(samples);
        if (average != null) {
            when(row.getBigDecimal("average_micros")).thenReturn(new BigDecimal(average));
            when(row.getBigDecimal("min_micros")).thenReturn(new BigDecimal(minimum));
            when(row.getBigDecimal("max_micros")).thenReturn(new BigDecimal(maximum));
        }
        when(jdbc.queryForObject(anyString(), eq(Map.of("targetAction", action, "eligibleStatuses", statuses)),
                ArgumentMatchers.<RowMapper<Turnaround>>any())).thenAnswer(invocation -> {
                    RowMapper<Turnaround> mapper = invocation.getArgument(2);
                    return mapper.mapRow(row, 0);
                });
    }

    private static CampusPrincipal principal(Role role) {
        var principal = mock(CampusPrincipal.class);
        when(principal.getRole()).thenReturn(role);
        return principal;
    }
}
