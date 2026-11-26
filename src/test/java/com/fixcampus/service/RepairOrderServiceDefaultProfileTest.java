package com.fixcampus.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.OrderEvent;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.form.CompleteRepairForm;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.repository.OrderEventRepository;
import com.fixcampus.repository.RepairOrderRepository;
import com.fixcampus.repository.UserRepository;
import com.fixcampus.security.CampusPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RepairOrderServiceDefaultProfileTest {
    @Test
    void createsAndTransitionsOrdersWithoutNotificationService() {
        var orders = mock(RepairOrderRepository.class);
        var users = mock(UserRepository.class);
        var events = mock(OrderEventRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var clock = Clock.systemUTC();
        var student = account(1L, Role.STUDENT);
        var admin = account(2L, Role.ADMIN);
        var worker = account(3L, Role.WORKER);
        when(users.findByIdAndRole(1L, Role.STUDENT)).thenReturn(Optional.of(student));
        when(users.findByIdAndRole(3L, Role.WORKER)).thenReturn(Optional.of(worker));
        when(users.getReferenceById(1L)).thenReturn(student);
        when(users.getReferenceById(2L)).thenReturn(admin);
        when(users.getReferenceById(3L)).thenReturn(worker);
        var orderIds = new AtomicLong();
        when(orders.saveAndFlush(any(RepairOrder.class))).thenAnswer(call -> {
            RepairOrder order = call.getArgument(0);
            long id = orderIds.incrementAndGet();
            ReflectionTestUtils.setField(order, "id", id);
            when(orders.findByIdForUpdate(id)).thenReturn(Optional.of(order));
            return order;
        });

        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=")
                .withUserConfiguration(RepairOrderService.class, NotificationService.class)
                .withInitializer(context -> context.getBeanFactory().registerSingleton("jdbcTemplate", jdbc))
                .withBean(RepairOrderRepository.class, () -> orders)
                .withBean(UserRepository.class, () -> users)
                .withBean(OrderEventRepository.class, () -> events)
                .withBean(Clock.class, () -> clock)
                .run(context -> {
                    assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
                    assertThat(context).hasSingleBean(RepairOrderService.class).doesNotHaveBean(NotificationService.class);
                    var service = context.getBean(RepairOrderService.class);
                    var studentActor = new CampusPrincipal(student);
                    var workerActor = new CampusPrincipal(worker);
                    var form = new RepairOrderForm();
                    form.setLocation("Fixture");
                    form.setCategory(RepairCategory.OTHER);
                    form.setDescription("Needs repair");
                    var completion = new CompleteRepairForm();
                    completion.setRepairResult("Repaired");

                    var completedId = service.createForStudent(studentActor, form);
                    service.assign(new CampusPrincipal(admin), completedId, 3L);
                    service.start(workerActor, completedId);
                    service.complete(workerActor, completedId, completion);
                    service.confirm(studentActor, completedId);
                    var cancelledId = service.createForStudent(studentActor, form);
                    service.cancel(studentActor, cancelledId);

                    assertThat(orders.findByIdForUpdate(completedId)).get()
                            .extracting(RepairOrder::getStatus).isEqualTo(RepairStatus.COMPLETED);
                    assertThat(orders.findByIdForUpdate(cancelledId)).get()
                            .extracting(RepairOrder::getStatus).isEqualTo(RepairStatus.CANCELLED);
                    var savedEvents = ArgumentCaptor.forClass(OrderEvent.class);
                    verify(events, times(7)).saveAndFlush(savedEvents.capture());
                    assertThat(savedEvents.getAllValues()).extracting(OrderEvent::getAction).containsExactly(
                            OrderAction.SUBMITTED, OrderAction.ASSIGNED, OrderAction.REPAIR_STARTED,
                            OrderAction.REPAIR_COMPLETED, OrderAction.CONFIRMED, OrderAction.SUBMITTED,
                            OrderAction.CANCELLED);
                    verifyNoInteractions(jdbc);
                });
    }

    private static User account(long id, Role role) {
        var user = new User("fixture" + id, "", "Fixture", null, role, LocalDateTime.now(ZoneOffset.UTC));
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
