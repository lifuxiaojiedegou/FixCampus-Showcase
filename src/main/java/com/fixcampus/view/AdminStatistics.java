package com.fixcampus.view;

import java.math.BigDecimal;
import java.util.List;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;

public record AdminStatistics(String generatedAt, String timezone, int windowDays, String windowStartDate,
        String windowEndDate, long totalOrders, List<StatusCount> statusCounts, List<CategoryCount> categoryCounts,
        List<DailyTrend> dailyTrend, Durations durations, LocationAggregation locationAggregation) {
    public record StatusCount(RepairStatus status, long count) { }
    public record CategoryCount(RepairCategory category, long count) { }
    public record DailyTrend(String date, long createdCount, long repairCompletedCount, long confirmedCount) { }
    public record Durations(Turnaround repairTurnaround, Turnaround confirmationTurnaround) { }
    public record Turnaround(long sampleCount, long missingEventCount, BigDecimal averageMinutes,
            BigDecimal minMinutes, BigDecimal maxMinutes) { }
    public record LocationAggregation(boolean supported, String reason) { }
}
