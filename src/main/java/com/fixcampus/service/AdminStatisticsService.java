package com.fixcampus.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.entity.Role;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.view.AdminStatistics;
import com.fixcampus.view.AdminStatistics.CategoryCount;
import com.fixcampus.view.AdminStatistics.DailyTrend;
import com.fixcampus.view.AdminStatistics.Durations;
import com.fixcampus.view.AdminStatistics.LocationAggregation;
import com.fixcampus.view.AdminStatistics.StatusCount;
import com.fixcampus.view.AdminStatistics.Turnaround;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile({"web-slice", "demo", "prod"})
public class AdminStatisticsService {
    private static final BigDecimal MICROSECONDS_PER_MINUTE = BigDecimal.valueOf(60_000_000);
    private static final LocationAggregation LOCATION = new LocationAggregation(false,
            "报修地点当前为自由文本字段，缺少可验证的结构化区域来源，暂不提供区域聚合。");
    private static final String STATUS_COUNTS = "SELECT UPPER(status), COUNT(*) FROM repair_orders GROUP BY UPPER(status)";
    private static final String CATEGORY_COUNTS = "SELECT UPPER(category), COUNT(*) FROM repair_orders GROUP BY UPPER(category)";
    private static final String DAILY_TREND = """
            SELECT trend_date, SUM(created_count), SUM(repair_completed_count), SUM(confirmed_count)
            FROM (
                SELECT DATE(created_at) AS trend_date, COUNT(*) AS created_count,
                       0 AS repair_completed_count, 0 AS confirmed_count
                FROM repair_orders
                WHERE created_at >= :windowStart AND created_at < :windowEnd
                GROUP BY DATE(created_at)
                UNION ALL
                SELECT DATE(occurred_at), 0, COUNT(CASE WHEN action = 'REPAIR_COMPLETED' THEN 1 END),
                       COUNT(CASE WHEN action = 'CONFIRMED' THEN 1 END)
                FROM order_events
                WHERE occurred_at >= :windowStart AND occurred_at < :windowEnd
                  AND action IN ('REPAIR_COMPLETED', 'CONFIRMED')
                GROUP BY DATE(occurred_at)
            ) AS daily
            GROUP BY trend_date
            """;
    // The fractional scale keeps SQL AVG precise enough to round at the minute boundary even for large sample counts.
    private static final String TURNAROUND = """
            SELECT COUNT(*) AS eligible_count, COUNT(micros) AS sample_count,
                   AVG(micros) AS average_micros, MIN(micros) AS min_micros, MAX(micros) AS max_micros
            FROM (
                SELECT CASE WHEN target.occurred_at >= submitted.occurred_at THEN
                           CAST(TIMESTAMPDIFF(MICROSECOND, submitted.occurred_at, target.occurred_at) AS DECIMAL(40,20))
                       END AS micros
                FROM repair_orders AS orders
                LEFT JOIN order_events AS submitted ON submitted.order_id = orders.id AND submitted.action = 'SUBMITTED'
                LEFT JOIN order_events AS target ON target.order_id = orders.id AND target.action = :targetAction
                WHERE orders.status IN (:eligibleStatuses)
            ) AS samples
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AdminStatisticsService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static boolean supportsDays(int days) {
        return days == 7 || days == 14 || days == 30;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminStatistics query(CampusPrincipal actor, int days) {
        if (actor == null || actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Admin role required");
        }
        if (!supportsDays(days)) {
            throw new IllegalArgumentException("Unsupported statistics window");
        }

        var now = ZonedDateTime.now(clock);
        var endDate = now.toLocalDate();
        var startDate = endDate.minusDays(days - 1L);
        var statuses = countsByValue(STATUS_COUNTS);
        var statusCounts = Arrays.stream(RepairStatus.values())
                .map(status -> new StatusCount(status, statuses.getOrDefault(status.name(), 0L))).toList();
        long totalOrders = statusCounts.stream().mapToLong(StatusCount::count).sum();
        var categories = countsByValue(CATEGORY_COUNTS);
        var categoryCounts = Arrays.stream(RepairCategory.values())
                .map(category -> new CategoryCount(category, categories.getOrDefault(category.name(), 0L))).toList();

        var trend = dailyTrend(startDate, endDate.plusDays(1));
        var repair = turnaround("REPAIR_COMPLETED", List.of("PENDING_CONFIRMATION", "COMPLETED"));
        var confirmation = turnaround("CONFIRMED", List.of("COMPLETED"));
        return new AdminStatistics(now.toOffsetDateTime().toString(), clock.getZone().getId(), days,
                startDate.toString(), endDate.toString(), totalOrders, statusCounts, categoryCounts, trend,
                new Durations(repair, confirmation), LOCATION);
    }

    private Map<String, Long> countsByValue(String sql) {
        return jdbc.query(sql, (rs, row) -> Map.entry(rs.getString(1).toUpperCase(Locale.ROOT), rs.getLong(2))).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));
    }

    private List<DailyTrend> dailyTrend(LocalDate startDate, LocalDate endExclusive) {
        var bounds = Map.of("windowStart", startDate.atStartOfDay(), "windowEnd", endExclusive.atStartOfDay());
        var counts = jdbc.query(DAILY_TREND, bounds, (rs, row) -> new DailyTrend(
                rs.getObject(1, LocalDate.class).toString(), rs.getLong(2), rs.getLong(3), rs.getLong(4))).stream()
                .collect(Collectors.toMap(DailyTrend::date, Function.identity()));
        return startDate.datesUntil(endExclusive).map(LocalDate::toString)
                .map(date -> counts.getOrDefault(date, new DailyTrend(date, 0, 0, 0))).toList();
    }

    private Turnaround turnaround(String targetAction, List<String> eligibleStatuses) {
        return jdbc.queryForObject(TURNAROUND, Map.of("targetAction", targetAction, "eligibleStatuses", eligibleStatuses),
                (rs, row) -> {
                    long samples = rs.getLong("sample_count");
                    return new Turnaround(samples, rs.getLong("eligible_count") - samples,
                            minutes(rs.getBigDecimal("average_micros")), minutes(rs.getBigDecimal("min_micros")),
                            minutes(rs.getBigDecimal("max_micros")));
                });
    }

    private static BigDecimal minutes(BigDecimal microseconds) {
        return microseconds == null ? null : microseconds.divide(MICROSECONDS_PER_MINUTE, 1, RoundingMode.HALF_UP);
    }
}
