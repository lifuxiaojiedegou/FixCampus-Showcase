package com.fixcampus.api.v1;

import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.AdminStatisticsService;
import com.fixcampus.view.AdminStatistics;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Profile({"web-slice", "demo", "prod"})
public class AdminStatisticsApi {
    private final AdminStatisticsService statistics;

    public AdminStatisticsApi(AdminStatisticsService statistics) {
        this.statistics = statistics;
    }

    @GetMapping("/admin/statistics")
    public AdminStatistics statistics(@AuthenticationPrincipal CampusPrincipal actor,
            @RequestParam(name = "days", required = false) String daysParam) {
        return statistics.query(actor, parseDays(daysParam));
    }

    private static int parseDays(String daysParam) {
        if (daysParam == null) {
            return 14;
        }

        int days;
        try {
            days = Integer.parseInt(daysParam);
        } catch (NumberFormatException e) {
            throw invalidDays();
        }
        if (!AdminStatisticsService.supportsDays(days)) {
            throw invalidDays();
        }
        return days;
    }

    private static ApiProblem invalidDays() {
        return new ApiProblem(400, "VALIDATION_ERROR", "统计天数仅支持 7、14 或 30");
    }
}
