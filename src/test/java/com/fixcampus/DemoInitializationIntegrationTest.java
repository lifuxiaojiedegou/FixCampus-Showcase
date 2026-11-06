package com.fixcampus;

import java.time.Clock;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.service.DemoAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class DemoInitializationIntegrationTest extends RealMySqlTestSupport {
    @Autowired DemoAccountService initializer;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    private boolean ownsReservedNames;

    @BeforeEach
    void claimOnlyEmptyTestFixtures() {
        for (String name : new String[]{"admin01", "worker01", "student01"}) {
            assertThat(users.existsByUsername(name)).as("Do not overwrite pre-existing test fixtures").isFalse();
        }
        ownsReservedNames = true;
    }

    @AfterEach
    void cleanOnlyClaimedNames() {
        if (ownsReservedNames) {
            for (String name : new String[]{"admin01", "worker01", "student01"}) {
                jdbc.update("DELETE FROM users WHERE username = ?", name);
            }
        }
    }

    private Map<Role, String> independentPasswords() {
        return Map.of(Role.ADMIN, UUID.randomUUID() + "Admin", Role.WORKER, UUID.randomUUID() + "Worker",
                Role.STUDENT, UUID.randomUUID() + "Student");
    }

    @Test
    void initializationIsRepeatableAndDoesNotResetExistingAccountData() {
        var initialPasswords = independentPasswords();
        initializer.initialize(initialPasswords);
        var admin = users.findByUsername("admin01").orElseThrow();
        var originalHash = admin.getPassword();
        var originalId = admin.getId();
        initializer.initialize(independentPasswords());
        var unchanged = users.findByUsername("admin01").orElseThrow();
        assertThat(unchanged.getId()).isEqualTo(originalId);
        assertThat(unchanged.getPassword()).isEqualTo(originalHash);
        assertThat(passwords.matches(initialPasswords.get(Role.ADMIN), unchanged.getPassword())).isTrue();
        assertThat(users.findByUsername("worker01").orElseThrow().getRole()).isEqualTo(Role.WORKER);
        assertThat(users.findByUsername("student01").orElseThrow().getRole()).isEqualTo(Role.STUDENT);
        initializer.initialize(Map.of()); // Existing same-role accounts do not require new passwords.
        assertThat(users.findByUsername("admin01").orElseThrow().getPassword()).isEqualTo(originalHash);
    }

    @Test
    void roleConflictStopsInitializationBeforeAnyNewAccountIsWritten() {
        users.saveAndFlush(new User("worker01", passwords.encode(UUID.randomUUID().toString()), "原有测试学生",
                null, Role.STUDENT, LocalDateTime.now(clock)));
        assertThatThrownBy(() -> initializer.initialize(independentPasswords()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("角色冲突");
        assertThat(users.findByUsername("worker01").orElseThrow().getRole()).isEqualTo(Role.STUDENT);
        assertThat(users.existsByUsername("admin01")).isFalse();
        assertThat(users.existsByUsername("student01")).isFalse();
    }

    @Test
    void missingOrSharedPasswordsDoNotPartiallyCreateAccounts() {
        assertThatThrownBy(() -> initializer.initialize(Map.of(Role.ADMIN, UUID.randomUUID().toString())))
                .isInstanceOf(IllegalStateException.class);
        String shared = UUID.randomUUID().toString();
        assertThatThrownBy(() -> initializer.initialize(Map.of(Role.ADMIN, shared, Role.WORKER, shared, Role.STUDENT, shared)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.existsByUsername("admin01")).isFalse();
        assertThat(users.existsByUsername("worker01")).isFalse();
        assertThat(users.existsByUsername("student01")).isFalse();
    }

    @Test
    void realMySqlEnforcesRoleLowercaseAndRequiredNameConstraints() {
        String hash = passwords.encode(UUID.randomUUID().toString());
        for (var values : new String[][]{{"admin01", "ROOT", "测试"}, {"Admin01", "ADMIN", "测试"}, {"admin01", "ADMIN", " "}}) {
            Throwable failure = catchThrowable(() -> jdbc.update(
                    "INSERT INTO users (username,password,real_name,role,created_at) VALUES (?,?,?,?,?)",
                    values[0], hash, values[2], values[1], LocalDateTime.now(clock)));
            assertThat(failure).isInstanceOf(DataAccessException.class);
            assertThat(failure.getCause()).isInstanceOf(SQLException.class);
            assertThat(((SQLException) failure.getCause()).getErrorCode()).isEqualTo(3819);
        }
        assertThat(users.existsByUsername("admin01")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'username'", String.class))
                .isEqualTo("ascii_bin");
    }
}
