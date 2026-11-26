package com.fixcampus.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import com.fixcampus.AttachmentFixtures;
import com.fixcampus.config.DemoResetScheduler;
import com.fixcampus.config.AttachmentProperties;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.repository.OrderEventRepository;
import com.fixcampus.repository.RepairOrderRepository;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.service.DemoResetService.ResetSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class DemoResetServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RepairOrderRepository orders = mock(RepairOrderRepository.class);
    private final OrderEventRepository events = mock(OrderEventRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PlatformTransactionManager transactions = AttachmentFixtures.transactions();
    @TempDir Path root;

    private DemoResetService service() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("notificationService", notifications);
        return new DemoResetService(jdbc, users, orders, events, clock,
                storage(root.resolve("reset_db"), "reset_db"), transactions,
                beans.getBeanProvider(NotificationService.class));
    }

    private static AttachmentStorage storage(Path directory, String database) {
        var properties = new AttachmentProperties(directory, 2048, 32, 32, 1024, 3, 2, 4096);
        return new AttachmentStorage(properties, database);
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(DemoResetService.class, DemoResetScheduler.class)
            .withPropertyValues("fixcampus.demo.reset.cron=0 0 4 29 2 *")
            .withBean(JdbcTemplate.class, () -> jdbc)
            .withBean(UserRepository.class, () -> users)
            .withBean(RepairOrderRepository.class, () -> orders)
            .withBean(OrderEventRepository.class, () -> events)
            .withBean(AttachmentStorage.class, () -> storage(root.resolve("reset_db"), "reset_db"))
            .withBean(PlatformTransactionManager.class, () -> transactions)
            .withBean(Clock.class, () -> clock);

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void registersResetAndSchedulingOnlyWhenBothFlagsAreTrue(boolean demoEnabled, boolean resetEnabled) {
        contextRunner.withPropertyValues("fixcampus.demo.enabled=" + demoEnabled,
                "fixcampus.demo.reset.enabled=" + resetEnabled).run(context -> {
                    if (demoEnabled && resetEnabled) {
                        assertThat(context).hasSingleBean(DemoResetService.class)
                                .hasSingleBean(DemoResetScheduler.class)
                                .hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                        assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                                .hasSize(1);
                    } else {
                        assertThat(context).doesNotHaveBean(DemoResetService.class)
                                .doesNotHaveBean(DemoResetScheduler.class)
                                .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    }
                });
    }

    @Test
    void absentFlagsDisableResetAndScheduling() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(DemoResetService.class)
                .doesNotHaveBean(DemoResetScheduler.class)
                .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void resetWithoutNotificationServiceSkipsNotificationSql() {
        account("student01", Role.STUDENT);
        account("admin01", Role.ADMIN);
        account("worker01", Role.WORKER);
        contextRunner.withPropertyValues("fixcampus.demo.enabled=true", "fixcampus.demo.reset.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(NotificationService.class);
                    var summary = context.getBean(DemoResetService.class).resetDemoData();
                    assertThat(summary.seededOrders()).isEqualTo(6);
                    assertThat(summary.seededEvents()).isEqualTo(17);
                    verify(jdbc, never()).update("DELETE FROM notifications");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidWorkerAccountStopsAllWrites(boolean wrongRole) {
        account("student01", Role.STUDENT);
        account("admin01", Role.ADMIN);
        if (wrongRole) {
            account("worker01", Role.STUDENT);
        }

        assertThatThrownBy(service()::resetDemoData).isInstanceOf(IllegalStateException.class);

        var preflight = inOrder(users);
        preflight.verify(users).findByUsername("student01");
        preflight.verify(users).findByUsername("admin01");
        preflight.verify(users).findByUsername("worker01");
        preflight.verifyNoMoreInteractions();
        verifyNoInteractions(jdbc, orders, events, notifications);
    }

    @Test
    void seedingFailurePropagatesAfterDeletesInForeignKeyOrder() {
        account("student01", Role.STUDENT);
        account("admin01", Role.ADMIN);
        account("worker01", Role.WORKER);
        var failure = new DataAccessResourceFailureException("Fixture storage failure");
        when(events.saveAllAndFlush(anyList())).thenThrow(failure);

        assertThatThrownBy(service()::resetDemoData).isSameAs(failure);

        var writes = inOrder(jdbc, orders, events);
        writes.verify(jdbc).update("DELETE FROM idempotency_requests");
        writes.verify(jdbc).update("DELETE FROM notifications");
        writes.verify(jdbc).update("DELETE FROM order_events");
        writes.verify(jdbc).queryForList("SELECT storage_key FROM order_attachments", String.class);
        writes.verify(jdbc).update("DELETE FROM order_attachments");
        writes.verify(jdbc).update("DELETE FROM repair_orders");
        writes.verify(orders).saveAndFlush(any(RepairOrder.class));
        writes.verify(events).saveAllAndFlush(anyList());
        writes.verifyNoMoreInteractions();
        verifyNoInteractions(notifications);
    }

    @Test
    void resetRemovesManagedFilesOnlyAfterCommitAndKeepsTheStorageRoot() throws Exception {
        account("student01", Role.STUDENT);
        account("admin01", Role.ADMIN);
        account("worker01", Role.WORKER);
        var directory = root.resolve("reset_db");
        var otherDirectory = root.resolve("another_db");
        var prepared = new AttachmentImages.Prepared("fixture.png", "image/png", 1, 1, new byte[]{1}, "hash");
        var key = storage(directory, "reset_db").store(prepared, ignored -> false);
        var file = directory.resolve(key);
        var orphan = Files.write(directory.resolve("a".repeat(32) + ".png"), new byte[]{1});
        var unrelated = Files.writeString(directory.resolve("unrelated.txt"), "keep");
        var otherStorage = storage(otherDirectory, "another_db");
        otherStorage.store(prepared, ignored -> false);
        var sameKeyInOtherDb = Files.write(otherDirectory.resolve(key), new byte[]{2});
        when(jdbc.queryForList("SELECT storage_key FROM order_attachments", String.class)).thenReturn(List.of(key));
        when(events.saveAllAndFlush(anyList())).thenAnswer(call -> {
            assertThat(file).exists();
            assertThat(orphan).exists();
            return call.getArgument(0);
        });

        service().resetDemoData();

        var writes = inOrder(jdbc);
        writes.verify(jdbc).update("DELETE FROM notifications");
        writes.verify(jdbc).queryForList("SELECT storage_key FROM order_attachments", String.class);
        writes.verify(jdbc).update("DELETE FROM order_attachments");
        writes.verify(jdbc).update("DELETE FROM repair_orders");
        assertThat(directory).isDirectory();
        assertThat(file).doesNotExist();
        assertThat(orphan).doesNotExist();
        assertThat(unrelated).exists();
        assertThat(Files.readAllBytes(sameKeyInOtherDb)).containsExactly((byte) 2);
        verify(notifications, times(17)).publish(any(RepairOrder.class), any(OrderAction.class), anyLong(),
                any(LocalDateTime.class));
    }

    @Test
    void schedulerLogsOnlySanitizedDetailsAndCanRunAgain(CapturedOutput output) {
        var reset = mock(DemoResetService.class);
        when(reset.resetDemoData()).thenThrow(new DataAccessResourceFailureException("Sensitive fixture detail"))
                .thenReturn(new ResetSummary(1, 2, 3, 6, 17));
        var scheduler = new DemoResetScheduler(reset);

        scheduler.resetOnSchedule();
        scheduler.resetOnSchedule();

        assertThat(output.getAll()).contains("Demo reset failed (DataAccessResourceFailureException)",
                "Demo reset completed:", "seededOrders=6", "seededEvents=17")
                .doesNotContain("Sensitive fixture detail", "at com.fixcampus");
    }

    private void account(String username, Role role) {
        var user = new User(username, "", "演示测试账号", null, role, LocalDateTime.now(clock));
        ReflectionTestUtils.setField(user, "id", (long) role.ordinal() + 1);
        when(users.findByUsername(username)).thenReturn(Optional.of(user));
    }
}
