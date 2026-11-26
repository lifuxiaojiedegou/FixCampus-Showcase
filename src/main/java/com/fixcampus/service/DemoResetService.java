package com.fixcampus.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.OrderEvent;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.RepairStatus;
import com.fixcampus.entity.Role;
import com.fixcampus.entity.User;
import com.fixcampus.repository.OrderEventRepository;
import com.fixcampus.repository.RepairOrderRepository;
import com.fixcampus.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = {"fixcampus.demo.enabled", "fixcampus.demo.reset.enabled"}, havingValue = "true")
public class DemoResetService {
    private record Sample(String location, RepairCategory category, String description,
                          RepairStatus status, String repairResult) { }

    // Keep the fictional orders aligned with database/003-demo-orders.sql.
    private static final List<Sample> SAMPLES = List.of(
            new Sample("演示宿舍 A101", RepairCategory.AIR_CONDITIONING, "【课程演示】空调启动后不制冷，请检查。",
                    RepairStatus.PENDING_ASSIGNMENT, null),
            new Sample("演示教学楼 B202", RepairCategory.NETWORK, "【课程演示】教室网络插口无法连接。",
                    RepairStatus.PENDING_REPAIR, null),
            new Sample("演示图书馆 C303", RepairCategory.DOORS_FURNITURE, "【课程演示】阅览室门锁卡顿。",
                    RepairStatus.IN_PROGRESS, null),
            new Sample("演示实验楼 D404", RepairCategory.WATER_ELECTRICITY, "【课程演示】照明灯闪烁。",
                    RepairStatus.PENDING_CONFIRMATION, "已更换灯管，等待学生核对。"),
            new Sample("演示体育馆 E505", RepairCategory.OTHER, "【课程演示】储物柜把手松动。",
                    RepairStatus.COMPLETED, "已紧固把手并检查使用情况。"),
            new Sample("演示食堂 F606", RepairCategory.WATER_ELECTRICITY, "【课程演示】水龙头滴水，已自行确认无需报修。",
                    RepairStatus.CANCELLED, null));

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final RepairOrderRepository orders;
    private final OrderEventRepository events;
    private final Clock clock;
    private final AttachmentStorage attachments;
    private final ObjectProvider<NotificationService> notifications;
    private final TransactionTemplate writeTransaction;

    public DemoResetService(JdbcTemplate jdbc, UserRepository users, RepairOrderRepository orders,
                            OrderEventRepository events, Clock clock, AttachmentStorage attachments,
                            PlatformTransactionManager transactions, ObjectProvider<NotificationService> notifications) {
        this.jdbc = jdbc;
        this.users = users;
        this.orders = orders;
        this.events = events;
        this.clock = clock;
        this.attachments = attachments;
        this.notifications = notifications;
        writeTransaction = new TransactionTemplate(transactions);
    }

    public ResetSummary resetDemoData() {
        return attachments.serializeWrite(() -> writeTransaction.execute(status -> replaceDemoData()));
    }

    private ResetSummary replaceDemoData() {
        var student = requireAccount("student01", Role.STUDENT);
        var admin = requireAccount("admin01", Role.ADMIN);
        var worker = requireAccount("worker01", Role.WORKER);
        var notificationService = notifications.getIfAvailable();

        int deletedRequests = jdbc.update("DELETE FROM idempotency_requests");
        if (notificationService != null) {
            jdbc.update("DELETE FROM notifications");
        }
        int deletedEvents = jdbc.update("DELETE FROM order_events");
        var storageKeys = jdbc.queryForList("SELECT storage_key FROM order_attachments", String.class);
        jdbc.update("DELETE FROM order_attachments");
        int deletedOrders = jdbc.update("DELETE FROM repair_orders");

        var createdAt = LocalDateTime.now(clock).minusMinutes(4);
        int seededEvents = 0;
        for (var sample : SAMPLES) {
            var order = new RepairOrder(student, sample.location(), sample.category(), sample.description(), createdAt);
            var timeline = new ArrayList<OrderEvent>();
            for (var action : OrderAction.expectedFor(sample.status())) {
                var occurredAt = createdAt.plusMinutes(timeline.size());
                timeline.add(applyAction(order, action, student, admin, worker, occurredAt, sample.repairResult()));
            }
            orders.saveAndFlush(order);
            events.saveAllAndFlush(timeline);
            if (notificationService != null) {
                for (var event : timeline) {
                    notificationService.publish(order, event.getAction(), event.getActorId(), event.getOccurredAt());
                }
            }
            seededEvents += timeline.size();
        }
        attachments.clearAfterCommit(storageKeys);
        return new ResetSummary(deletedRequests, deletedEvents, deletedOrders, SAMPLES.size(), seededEvents);
    }

    private User requireAccount(String username, Role expectedRole) {
        return users.findByUsername(username).filter(user -> user.getRole() == expectedRole)
                .orElseThrow(() -> new IllegalStateException("Required demo account is missing or has an unexpected role."));
    }

    private static OrderEvent applyAction(RepairOrder order, OrderAction action, User student, User admin, User worker,
                                          LocalDateTime occurredAt, String repairResult) {
        var actor = switch (action) {
            case SUBMITTED, CONFIRMED, CANCELLED -> student;
            case ASSIGNED -> admin;
            case REPAIR_STARTED, REPAIR_COMPLETED -> worker;
        };
        String detail = null;
        switch (action) {
            case SUBMITTED -> { }
            case ASSIGNED -> {
                order.assignTo(worker);
                detail = "分配给维修人员：" + worker.getRealName();
            }
            case REPAIR_STARTED -> order.startRepair();
            case REPAIR_COMPLETED -> {
                order.submitRepair(repairResult);
                detail = repairResult;
            }
            case CONFIRMED -> order.confirmCompletion(occurredAt);
            case CANCELLED -> order.cancel();
        }
        return new OrderEvent(order, action, actor, actor.getRole(), actor.getRealName(), occurredAt, detail);
    }

    public record ResetSummary(int deletedRequests, int deletedEvents, int deletedOrders,
                               int seededOrders, int seededEvents) { }
}
