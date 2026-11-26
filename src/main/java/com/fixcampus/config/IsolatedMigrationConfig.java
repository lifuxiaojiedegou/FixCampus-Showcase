package com.fixcampus.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The first slice only owns a freshly provisioned, disposable schema. */
@Configuration
@Profile("web-slice")
public class IsolatedMigrationConfig {
    @Bean
    FlywayMigrationStrategy isolatedMigration() {
        return flyway -> {
            try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
                String catalog = connection.getCatalog();
                if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())
                        || catalog == null || !catalog.matches("fixcampus_m421_[a-z0-9_]+_test")) {
                    throw new IllegalStateException("Migration requires the separately provisioned M4.2.1 isolated test schema.");
                }
                // Existing unmanaged schemas must never be silently taken over.
                var metadata = connection.getMetaData();
                try (var tables = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                    boolean history = false, any = false;
                    while (tables.next()) {
                        any = true;
                        history |= "flyway_schema_history".equals(tables.getString("TABLE_NAME"));
                    }
                    if (any && !history) throw new IllegalStateException("Existing unmanaged schema: migration refused.");
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Cannot verify isolated migration target.");
            }
            flyway.migrate();
        };
    }
}
