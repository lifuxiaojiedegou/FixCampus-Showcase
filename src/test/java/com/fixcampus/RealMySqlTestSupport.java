package com.fixcampus;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fixcampus.demo.enabled=false")
@AutoConfigureMockMvc
@Import(RealMySqlTestSupport.SafetyConfiguration.class)
abstract class RealMySqlTestSupport {
    @TestConfiguration(proxyBeanMethods = false)
    static class SafetyConfiguration {
        @Bean
        @Order(0)
        ApplicationRunner requireIsolatedDatabase(DataSource source) {
            return args -> {
                try (var connection = source.getConnection()) {
                    String catalog = connection.getCatalog();
                    if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())
                            || !catalog.startsWith("fixcampus_") || !catalog.endsWith("_test")) {
                        throw new IllegalStateException("Integration tests require an isolated fixcampus_*_test MySQL schema.");
                    }
                }
            };
        }
    }
}
