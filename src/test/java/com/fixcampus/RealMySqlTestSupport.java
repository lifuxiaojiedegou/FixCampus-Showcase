package com.fixcampus;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import com.fixcampus.entity.Role;
import com.fixcampus.repository.OrderAttachmentRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"fixcampus.demo.enabled=false", "fixcampus.rate-limit.enabled=false"})
@AutoConfigureMockMvc
@Import(RealMySqlTestSupport.SafetyConfiguration.class)
abstract class RealMySqlTestSupport {
    static void removeStudentOrderFixtures(JdbcTemplate jdbc, long studentId) {
        jdbc.update("""
                DELETE FROM notifications WHERE order_id IN (SELECT id FROM repair_orders WHERE student_id = ?)
                """, studentId);
        jdbc.update("DELETE FROM repair_orders WHERE student_id = ?", studentId);
    }

    static void removeUserFixture(JdbcTemplate jdbc, long userId) {
        jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SafetyConfiguration {
        private static final long PROBE_ID = 1;
        private static final long MISSING_ID = 2;
        private static final List<String> V5_TABLES = List.of(
                "users", "repair_orders", "order_events", "idempotency_requests", "order_attachments");
        private static final String ATTACHMENT_KEY = "a".repeat(32) + ".png";
        private static final String NOTIFICATION_KEY = "order:1:ORDER_SUBMITTED:recipient:1";

        @Bean
        @Primary
        @Profile("web-slice")
        FlywayMigrationStrategy testNotificationsUpgrade(@Qualifier("isolatedMigration") FlywayMigrationStrategy isolated) {
            return flyway -> {
                var current = flyway.info().current();
                if (current == null) {
                    var jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
                    assertThat(jdbc.queryForList("""
                            SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
                            """, String.class)).as("schema before the first Flyway migration").isEmpty();
                    assertThat(flyway.info().pending())
                            .extracting(migration -> migration.getVersion().getVersion())
                            .containsExactly("1", "2", "3", "4", "5", "6");
                }
                if (current == null || current.getVersion().compareTo(MigrationVersion.fromVersion("5")) <= 0) {
                    // The first test context owns the single CI schema. Stage the real scripts
                    // through V5 before the normal V6 startup, regardless of test class order.
                    var v5 = Flyway.configure().configuration(flyway.getConfiguration()).target("5").load();
                    isolated.migrate(v5);
                    verifyAppliedMigrations(v5, "1", "2", "3", "4", "5");
                    requireVersionAndState(flyway, "5", MigrationState.PENDING);
                    verifyNotificationsUpgrade(flyway, isolated);
                } else {
                    isolated.migrate(flyway);
                }
            };
        }

        private static void verifyNotificationsUpgrade(Flyway flyway, FlywayMigrationStrategy isolated) {
            var source = flyway.getConfiguration().getDataSource();
            var jdbc = new JdbcTemplate(source);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            V5_TABLES.forEach(table -> requireEmpty(jdbc, table));
            try {
                // Commit the V5 probes so Flyway sees the same rows as a real upgrade.
                transactions.executeWithoutResult(status -> insertV5Probe(jdbc));
                var before = readV5Probe(jdbc);
                isolated.migrate(flyway);
                requireVersionAndState(flyway, "6", MigrationState.SUCCESS);
                verifyAppliedMigrations(flyway, "1", "2", "3", "4", "5", "6");
                assertThat(readV5Probe(jdbc)).isEqualTo(before);
                verifyNotificationSchema(jdbc);
                requireEmpty(jdbc, "notifications");
                transactions.executeWithoutResult(status -> {
                    status.setRollbackOnly();
                    verifyAttachmentReferences(jdbc);
                    verifyNotificationConstraints(jdbc);
                });
                requireEmpty(jdbc, "notifications");
            } finally {
                transactions.executeWithoutResult(status -> V5_TABLES.reversed().forEach(
                        table -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", PROBE_ID)));
            }
            V5_TABLES.forEach(table -> requireEmpty(jdbc, table));
        }

        private static void verifyAppliedMigrations(Flyway flyway, String... versions) {
            flyway.validate();
            assertThat(flyway.info().applied()).allSatisfy(migration ->
                            assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS))
                    .extracting(migration -> migration.getVersion().getVersion())
                    .containsExactly(versions);
        }

        private static void verifyNotificationSchema(JdbcTemplate jdbc) {
            assertThat(jdbc.queryForList("""
                    SELECT CONCAT_WS(':', COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
                                     NULLIF(EXTRA, ''), COLLATION_NAME)
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notifications'
                    ORDER BY ORDINAL_POSITION
                    """, String.class)).containsExactly(
                    "id:bigint:NO:auto_increment", "recipient_id:bigint:NO", "order_id:bigint:NO",
                    "event_type:varchar(32):NO:ascii_bin", "title:varchar(100):NO:utf8mb4_0900_ai_ci",
                    "body:varchar(255):NO:utf8mb4_0900_ai_ci", "idempotency_key:varchar(96):NO:ascii_bin",
                    "read_at:datetime(6):YES", "created_at:datetime(6):NO");
            assertThat(jdbc.queryForList("""
                    SELECT CONCAT(INDEX_NAME, ':', NON_UNIQUE, ':', GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX))
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notifications'
                    GROUP BY INDEX_NAME, NON_UNIQUE
                    """, String.class)).containsExactlyInAnyOrder(
                    "PRIMARY:0:id", "uk_notifications_idempotency_key:0:idempotency_key",
                    "idx_notifications_recipient_created:1:recipient_id,created_at,id",
                    "idx_notifications_recipient_unread:1:recipient_id,read_at,id", "idx_notifications_order:1:order_id");
        }

        private static void requireEmpty(JdbcTemplate jdbc, String table) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).as(table).isZero();
        }

        private static void insertV5Probe(JdbcTemplate jdbc) {
            jdbc.update("""
                    INSERT INTO users (id, username, password, real_name, role, created_at)
                    VALUES (?, 'v5_upgrade_probe', 'unused-fixture', 'Upgrade probe', 'STUDENT', '2026-01-01 00:00:00.123456')
                    """, PROBE_ID);
            jdbc.update("""
                    INSERT INTO repair_orders (id, student_id, location, category, description, status, created_at)
                    VALUES (?, ?, 'Test site', 'OTHER', 'V5 data', 'PENDING_ASSIGNMENT', '2026-01-01 00:00:01.234567')
                    """, PROBE_ID, PROBE_ID);
            jdbc.update("""
                    INSERT INTO order_events (id, order_id, action, actor_id, actor_role, actor_name, occurred_at, detail)
                    VALUES (?, ?, 'SUBMITTED', ?, 'STUDENT', 'Upgrade probe', '2026-01-01 00:00:01.234567', 'V5 event')
                    """, PROBE_ID, PROBE_ID, PROBE_ID);
            jdbc.update("""
                    INSERT INTO idempotency_requests (id, actor_id, operation_code, request_key, payload_hash, order_id, created_at)
                    VALUES (?, ?, 'CREATE_REPAIR_ORDER', 'v5_upgrade_key', UNHEX(REPEAT('af', 32)), ?, '2026-01-01 00:00:01.234567')
                    """, PROBE_ID, PROBE_ID, PROBE_ID);
            insertAttachmentProbe(jdbc, PROBE_ID, PROBE_ID, PROBE_ID, ATTACHMENT_KEY);
        }

        private static List<Map<String, Object>> readV5Probe(JdbcTemplate jdbc) {
            return List.of(jdbc.queryForMap("SELECT * FROM users WHERE id = ?", PROBE_ID),
                    jdbc.queryForMap("SELECT * FROM repair_orders WHERE id = ?", PROBE_ID),
                    jdbc.queryForMap("SELECT * FROM order_events WHERE id = ?", PROBE_ID),
                    jdbc.queryForMap("""
                            SELECT id, actor_id, operation_code, request_key, HEX(payload_hash) AS payload_hash, order_id, created_at
                            FROM idempotency_requests WHERE id = ?
                            """, PROBE_ID),
                    jdbc.queryForMap("SELECT * FROM order_attachments WHERE id = ?", PROBE_ID));
        }

        private static void verifyAttachmentReferences(JdbcTemplate jdbc) {
            assertThat(new OrderAttachmentRepository(jdbc).findByOrderId(PROBE_ID)).singleElement()
                    .satisfies(attachment -> {
                        assertThat(attachment.uploaderId()).isEqualTo(PROBE_ID);
                        assertThat(attachment.uploaderRole()).isEqualTo(Role.STUDENT);
                        assertThat(attachment.storageKey()).isEqualTo(ATTACHMENT_KEY);
                    });
            requireInvalidAttachmentReference(jdbc, MISSING_ID, PROBE_ID);
            requireInvalidAttachmentReference(jdbc, PROBE_ID, MISSING_ID);
        }

        private static void requireInvalidAttachmentReference(JdbcTemplate jdbc, long orderId, long uploaderId) {
            assertThatThrownBy(() -> insertAttachmentProbe(jdbc, MISSING_ID, orderId, uploaderId, "b".repeat(32) + ".png"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasCauseInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error.getCause()).getErrorCode()).isEqualTo(1452));
        }

        private static void insertAttachmentProbe(JdbcTemplate jdbc, long id, long orderId, long uploaderId, String key) {
            jdbc.update("""
                    INSERT INTO order_attachments (id, order_id, uploader_id, uploader_role, original_filename, content_type,
                                                   size_bytes, width, height, sha256, storage_key, created_at)
                    VALUES (?, ?, ?, 'student', 'upgrade.png', 'image/png', 1, 1, 1, REPEAT('0', 64), ?, '2026-01-01 00:00:02.345678')
                    """, id, orderId, uploaderId, key);
        }

        private static void verifyNotificationConstraints(JdbcTemplate jdbc) {
            assertThat(jdbc.queryForList("""
                    SELECT CONCAT(CONSTRAINT_NAME, ':', REFERENCED_TABLE_NAME, ':', DELETE_RULE, ':', UPDATE_RULE)
                    FROM information_schema.REFERENTIAL_CONSTRAINTS
                    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'notifications'
                    """, String.class)).containsExactlyInAnyOrder(
                    "fk_notifications_recipient:users:RESTRICT:RESTRICT",
                    "fk_notifications_order:repair_orders:RESTRICT:RESTRICT");

            assertThatThrownBy(() -> insertNotificationProbe(jdbc, MISSING_ID, PROBE_ID,
                    "ORDER_SUBMITTED", "Missing recipient", "missing-recipient"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasCauseInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error.getCause()).getErrorCode()).isEqualTo(1452));
            assertThatThrownBy(() -> insertNotificationProbe(jdbc, PROBE_ID, MISSING_ID,
                    "ORDER_SUBMITTED", "Missing order", "missing-order"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasCauseInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error.getCause()).getErrorCode()).isEqualTo(1452));
            for (String eventType : List.of("order_submitted", "ORDER_SUBMITTED ")) {
                assertThatThrownBy(() -> insertNotificationProbe(jdbc, PROBE_ID, PROBE_ID,
                        eventType, "Invalid event", "invalid-event"))
                        .hasCauseInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error.getCause()).getErrorCode()).isEqualTo(3819));
            }

            insertNotificationProbe(jdbc, PROBE_ID, PROBE_ID, "ORDER_SUBMITTED", "Original", NOTIFICATION_KEY);
            jdbc.update("UPDATE notifications SET read_at = '2026-01-01 00:00:04.567890' WHERE idempotency_key = ?",
                    NOTIFICATION_KEY);
            var before = jdbc.queryForMap("SELECT * FROM notifications WHERE idempotency_key = ?", NOTIFICATION_KEY);
            insertNotificationProbe(jdbc, PROBE_ID, PROBE_ID, "ORDER_SUBMITTED", "Duplicate", NOTIFICATION_KEY);
            assertThat(jdbc.queryForList("SELECT * FROM notifications")).containsExactly(before);
        }

        private static void insertNotificationProbe(JdbcTemplate jdbc, long recipientId, long orderId,
                                                    String eventType, String title, String key) {
            jdbc.update("""
                    INSERT INTO notifications (recipient_id, order_id, event_type, title, body, idempotency_key, created_at)
                    VALUES (?, ?, ?, ?, 'Upgrade probe', ?, '2026-01-01 00:00:03.456789')
                    ON DUPLICATE KEY UPDATE id = id
                    """, recipientId, orderId, eventType, title, key);
        }

        private static void requireVersionAndState(Flyway flyway, String current, MigrationState state) {
            var info = flyway.info();
            if (info.current() == null || !current.equals(info.current().getVersion().getVersion())
                    || Arrays.stream(info.all()).noneMatch(item ->
                            item.getVersion() != null && "6".equals(item.getVersion().getVersion())
                                    && item.getState() == state)) {
                throw new IllegalStateException("Unexpected Flyway state in the isolated V5 to V6 test.");
            }
        }

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
