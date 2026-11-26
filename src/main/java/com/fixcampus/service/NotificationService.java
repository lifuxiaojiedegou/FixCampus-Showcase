package com.fixcampus.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.exception.NotificationNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile({"web-slice", "demo", "prod"})
public class NotificationService {
    private static final String INSERT = """
            INSERT INTO notifications (recipient_id, order_id, event_type, title, body, idempotency_key, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = id
            """;
    private static final String RECIPIENT = " FROM notifications WHERE recipient_id = ?";
    private static final String UNREAD = " AND read_at IS NULL";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(RepairOrder order, OrderAction action, long actorId, LocalDateTime occurredAt) {
        var reference = "工单 #" + order.getId() + "（" + order.getLocation() + "）";
        switch (action) {
            case SUBMITTED -> publishToAdmins(order, actorId, "ORDER_SUBMITTED", "新报修待分配",
                    reference + "已提交，请及时分配维修人员。", occurredAt);
            case ASSIGNED -> {
                var worker = order.getWorker();
                insert(order, actorId, order.getStudent().getId(), "ORDER_ASSIGNED", "报修单已派单",
                        reference + "已分配给维修人员 " + worker.getRealName() + "。", occurredAt);
                insert(order, actorId, worker.getId(), "ORDER_ASSIGNED", "新维修任务待处理",
                        reference + "已指派给您，请尽快查看处理。", occurredAt);
            }
            case REPAIR_STARTED -> insert(order, actorId, order.getStudent().getId(), "REPAIR_STARTED", "维修已开始",
                    reference + "已由维修人员开始处理。", occurredAt);
            case REPAIR_COMPLETED -> insert(order, actorId, order.getStudent().getId(), "REPAIR_COMPLETED",
                    "维修已完成，请确认", reference + "已完成维修，请核对结果并确认完成。", occurredAt);
            case CONFIRMED -> {
                var worker = order.getWorker();
                if (worker != null) {
                    insert(order, actorId, worker.getId(), "ORDER_CONFIRMED", "学生已确认完成",
                            reference + "已由学生确认维修完成。", occurredAt);
                }
            }
            case CANCELLED -> publishToAdmins(order, actorId, "ORDER_CANCELLED", "报修单已取消",
                    reference + "已由学生取消。", occurredAt);
        }
    }

    private void publishToAdmins(RepairOrder order, long actorId, String eventType, String title, String body,
                                 LocalDateTime occurredAt) {
        for (long recipientId : jdbc.queryForList("SELECT id FROM users WHERE role = 'ADMIN' ORDER BY id", Long.class)) {
            insert(order, actorId, recipientId, eventType, title, body, occurredAt);
        }
    }

    private void insert(RepairOrder order, long actorId, long recipientId, String eventType, String title,
                        String body, LocalDateTime occurredAt) {
        if (recipientId == actorId) {
            return;
        }
        if (body.codePointCount(0, body.length()) > 255) {
            body = body.substring(0, body.offsetByCodePoints(0, 255));
        }
        var key = "order:" + order.getId() + ":" + eventType + ":recipient:" + recipientId;
        jdbc.update(INSERT, recipientId, order.getId(), eventType, title, body, key, occurredAt);
    }

    @Transactional(readOnly = true)
    public NotificationPage list(long recipientId, int page, int size, boolean unreadOnly) {
        if (page < 0 || size < 1 || size > 50) {
            throw new IllegalArgumentException("Invalid notification pagination");
        }
        var scope = unreadOnly ? RECIPIENT + UNREAD : RECIPIENT;
        long unread = unreadCount(recipientId);
        long total = unreadOnly ? unread : jdbc.queryForObject("SELECT COUNT(*)" + scope, Long.class, recipientId);
        var items = jdbc.query("""
                SELECT id, order_id, event_type, title, body, read_at, created_at
                """ + scope + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new NotificationItem(rs.getLong("id"), rs.getLong("order_id"),
                        rs.getString("event_type"), rs.getString("title"), rs.getString("body"),
                        rs.getObject("read_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class)),
                recipientId, size, (long) page * size);
        return new NotificationPage(items, page, size, total, (int) ((total + size - 1) / size), unread);
    }

    @Transactional(readOnly = true)
    public long unreadCount(long recipientId) {
        return jdbc.queryForObject("SELECT COUNT(*)" + RECIPIENT + UNREAD, Long.class, recipientId);
    }

    @Transactional
    public ReadResult read(long recipientId, long id) {
        if (id <= 0) {
            throw new NotificationNotFoundException();
        }
        jdbc.update("UPDATE notifications SET read_at = ? WHERE id = ? AND recipient_id = ? AND read_at IS NULL",
                now(), id, recipientId);
        var times = jdbc.query("SELECT read_at" + RECIPIENT + " AND id = ?",
                (rs, row) -> rs.getObject("read_at", LocalDateTime.class), recipientId, id);
        if (times.isEmpty()) {
            throw new NotificationNotFoundException();
        }
        return new ReadResult(id, times.getFirst(), unreadCount(recipientId));
    }

    @Transactional
    public ReadAllResult readAll(long recipientId) {
        int marked = jdbc.update("UPDATE notifications SET read_at = ? WHERE recipient_id = ? AND read_at IS NULL",
                now(), recipientId);
        return new ReadAllResult(marked, unreadCount(recipientId));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public record NotificationItem(long id, long orderId, String eventType, String title, String body,
                                   LocalDateTime readAt, LocalDateTime createdAt) { }

    public record NotificationPage(List<NotificationItem> items, int page, int size, long totalElements,
                                   int totalPages, long unreadCount) { }

    public record ReadResult(long id, LocalDateTime readAt, long unreadCount) { }

    public record ReadAllResult(int markedCount, long unreadCount) { }
}
