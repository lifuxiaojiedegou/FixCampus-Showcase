package com.fixcampus.config;

import com.fixcampus.service.DemoResetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = {"fixcampus.demo.enabled", "fixcampus.demo.reset.enabled"}, havingValue = "true")
public class DemoResetScheduler {
    private static final Logger log = LoggerFactory.getLogger(DemoResetScheduler.class);
    private final DemoResetService resetService;

    public DemoResetScheduler(DemoResetService resetService) {
        this.resetService = resetService;
    }

    @Scheduled(cron = "${fixcampus.demo.reset.cron:0 0 4 * * *}", zone = "Asia/Hong_Kong")
    public void resetOnSchedule() {
        try {
            log.info("Demo reset completed: {}", resetService.resetDemoData());
        } catch (RuntimeException failure) {
            log.error("Demo reset failed ({})", failure.getClass().getSimpleName());
        }
    }
}
