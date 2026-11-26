package com.fixcampus.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.exception.NotificationNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class NotificationServiceTest {
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 26, 15, 0);
    private static final String ADMINS = "SELECT id FROM users WHERE role = 'ADMIN' ORDER BY id";
    private static final String UPSERT = "ON DUPLICATE KEY UPDATE id = id";
    private static final long STUDENT = 11;
    private static final long WORKER = 12;
    private static final long ADMIN = 13;
    private static final long OTHER_ADMIN = 14;
    private static final long ORDER = 42;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationService service = new NotificationService(jdbc, Clock.systemUTC());

    @Test
    void publishesEveryLifecycleEventWithItsContractTextAndRecipients() {
        when(jdbc.queryForList(ADMINS, Long.class)).thenReturn(List.of(ADMIN, OTHER_ADMIN));
        var order = order("演示宿舍 A101");
        order.assignTo(account(WORKER, Role.WORKER, "维修员甲"));
        service.publish(order, OrderAction.SUBMITTED, STUDENT, OCCURRED_AT);
        service.publish(order, OrderAction.ASSIGNED, ADMIN, OCCURRED_AT);
        service.publish(order, OrderAction.REPAIR_STARTED, WORKER, OCCURRED_AT);
        service.publish(order, OrderAction.REPAIR_COMPLETED, WORKER, OCCURRED_AT);
        service.publish(order, OrderAction.CONFIRMED, STUDENT, OCCURRED_AT);
        service.publish(order, OrderAction.CANCELLED, STUDENT, OCCURRED_AT);

        String reference = "工单 #42（演示宿舍 A101）";
        for (long id : List.of(ADMIN, OTHER_ADMIN)) {
            verifyInsert(id, "ORDER_SUBMITTED", "新报修待分配", reference + "已提交，请及时分配维修人员。");
            verifyInsert(id, "ORDER_CANCELLED", "报修单已取消", reference + "已由学生取消。");
        }
        verifyInsert(STUDENT, "ORDER_ASSIGNED", "报修单已派单", reference + "已分配给维修人员 维修员甲。");
        verifyInsert(WORKER, "ORDER_ASSIGNED", "新维修任务待处理", reference + "已指派给您，请尽快查看处理。");
        verifyInsert(STUDENT, "REPAIR_STARTED", "维修已开始", reference + "已由维修人员开始处理。");
        verifyInsert(STUDENT, "REPAIR_COMPLETED", "维修已完成，请确认", reference + "已完成维修，请核对结果并确认完成。");
        verifyInsert(WORKER, "ORDER_CONFIRMED", "学生已确认完成", reference + "已由学生确认维修完成。");
        verify(jdbc, times(2)).queryForList(ADMINS, Long.class);
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void skipsTheActorAndSkipsConfirmationWithoutAnAssignedWorker() {
        when(jdbc.queryForList(ADMINS, Long.class)).thenReturn(List.of(ADMIN, OTHER_ADMIN));
        var order = order("演示地点");
        service.publish(order, OrderAction.SUBMITTED, ADMIN, OCCURRED_AT);
        service.publish(order, OrderAction.REPAIR_STARTED, STUDENT, OCCURRED_AT);
        service.publish(order, OrderAction.CONFIRMED, STUDENT, OCCURRED_AT);

        verify(jdbc).queryForList(ADMINS, Long.class);
        verifyInsert(OTHER_ADMIN, "ORDER_SUBMITTED", "新报修待分配", "工单 #42（演示地点）已提交，请及时分配维修人员。");
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void truncatesAtAFullCodePointAndReusesTheSameKeyOnReplay() {
        var order = order("🔧".repeat(280));
        service.publish(order, OrderAction.REPAIR_STARTED, WORKER, OCCURRED_AT);
        service.publish(order, OrderAction.REPAIR_STARTED, WORKER, OCCURRED_AT.plusSeconds(1));

        var bodies = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(contains(UPSERT), eq(STUDENT), eq(ORDER), eq("REPAIR_STARTED"),
                eq("维修已开始"), bodies.capture(), eq("order:42:REPAIR_STARTED:recipient:11"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
        assertThat(bodies.getAllValues()).containsExactly("工单 #42（" + "🔧".repeat(248),
                "工单 #42（" + "🔧".repeat(248));
        verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,51"})
    void rejectsOutOfRangePaginationWithoutQuerying(int page, int size) {
        assertThatThrownBy(() -> service.list(STUDENT, page, size, false))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void treatsNonpositiveIdsAsMissing(long id) {
        assertThatThrownBy(() -> service.read(STUDENT, id)).isInstanceOf(NotificationNotFoundException.class);
        verifyNoInteractions(jdbc);
    }

    private void verifyInsert(long recipient, String eventType, String title, String body) {
        verify(jdbc).update(contains(UPSERT), eq(recipient), eq(ORDER), eq(eventType), eq(title), eq(body),
                eq("order:42:" + eventType + ":recipient:" + recipient), eq(OCCURRED_AT));
    }

    private static RepairOrder order(String location) {
        var order = new RepairOrder(account(STUDENT, Role.STUDENT, "学生甲"), location, RepairCategory.OTHER, "演示",
                OCCURRED_AT);
        ReflectionTestUtils.setField(order, "id", ORDER);
        return order;
    }

    private static User account(long id, Role role, String name) {
        var user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getRole()).thenReturn(role);
        when(user.getRealName()).thenReturn(name);
        return user;
    }
}
