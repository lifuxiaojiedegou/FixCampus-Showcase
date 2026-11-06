package com.fixcampus.config;

import java.util.Map;
import com.fixcampus.entity.Role;
import com.fixcampus.service.DemoAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Order(20)
@ConditionalOnProperty(name = "fixcampus.demo.enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);
    private final DemoAccountService accounts;
    private final Environment environment;

    public DemoDataInitializer(DemoAccountService accounts, Environment environment) {
        this.accounts = accounts;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        accounts.initialize(Map.of(
                Role.ADMIN, environment.getProperty("fixcampus.demo.admin-password", ""),
                Role.WORKER, environment.getProperty("fixcampus.demo.worker-password", ""),
                Role.STUDENT, environment.getProperty("fixcampus.demo.student-password", "")));
        log.info("FixCampus demo accounts initialized; existing accounts preserved.");
    }
}
