package com.fixcampus.api.v1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.NotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@Profile({"web-slice", "demo", "prod"})
public class NotificationsApi {
    private final NotificationService notifications;
    private final ObjectMapper mapper;

    public NotificationsApi(NotificationService notifications, ObjectMapper mapper) {
        this.notifications = notifications;
        this.mapper = mapper;
    }

    @GetMapping
    public NotificationPageResponse list(@AuthenticationPrincipal CampusPrincipal actor,
            @RequestParam(required = false) String page, @RequestParam(required = false) String size,
            @RequestParam(required = false) String unreadOnly) {
        int pageNumber = parseNumber(page, 0);
        int pageSize = parseNumber(size, 20);
        if (pageNumber < 0 || pageSize < 1 || pageSize > 50) {
            throw new ApiProblem(400, "VALIDATION_ERROR", "page 必须大于等于 0，size 必须在 1 到 50 之间");
        }
        boolean onlyUnread = switch (unreadOnly) {
            case null -> false;
            case "false" -> false;
            case "true" -> true;
            default -> throw new ApiProblem(400, "VALIDATION_ERROR", "unreadOnly 必须是 true 或 false");
        };
        var result = notifications.list(actor.getId(), pageNumber, pageSize, onlyUnread);
        return new NotificationPageResponse(result.items().stream().map(NotificationItem::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages(), result.unreadCount());
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal CampusPrincipal actor) {
        return new UnreadCountResponse(notifications.unreadCount(actor.getId()));
    }

    @PostMapping("/{id}/read")
    public NotificationReadResponse read(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable String id,
            @RequestBody(required = false) byte[] rawBody) {
        requireEmptyBody(rawBody);
        var result = notifications.read(actor.getId(), parseId(id));
        return new NotificationReadResponse(Long.toString(result.id()), true, StudentApi.time(result.readAt()),
                result.unreadCount());
    }

    @PostMapping("/read-all")
    public NotificationReadAllResponse readAll(@AuthenticationPrincipal CampusPrincipal actor,
            @RequestBody(required = false) byte[] rawBody) {
        requireEmptyBody(rawBody);
        var result = notifications.readAll(actor.getId());
        return new NotificationReadAllResponse(result.markedCount(), result.unreadCount());
    }

    private void requireEmptyBody(byte[] rawBody) {
        if (rawBody == null || new String(rawBody, StandardCharsets.UTF_8).isBlank()) {
            return;
        }
        JsonNode body;
        try {
            body = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(rawBody);
        } catch (IOException e) {
            throw new ApiProblem(400, "VALIDATION_ERROR", "请求体必须为空或为空对象");
        }
        if (body == null || !body.isObject() || !body.isEmpty()) {
            throw new ApiProblem(400, "VALIDATION_ERROR", "请求体必须为空或为空对象");
        }
    }

    private static int parseNumber(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ApiProblem(400, "VALIDATION_ERROR", "请求参数无效");
        }
    }

    private static long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ApiProblem(400, "VALIDATION_ERROR", "通知 ID 必须是整数");
        }
    }

    public record NotificationItem(String id, String orderId, String eventType, String title, String body,
                                   boolean read, String readAt, String createdAt) {
        private static NotificationItem from(NotificationService.NotificationItem item) {
            return new NotificationItem(Long.toString(item.id()), Long.toString(item.orderId()), item.eventType(),
                    item.title(), item.body(), item.readAt() != null, StudentApi.time(item.readAt()),
                    StudentApi.time(item.createdAt()));
        }
    }

    public record NotificationPageResponse(List<NotificationItem> items, int page, int size, long totalElements,
                                           int totalPages, long unreadCount) { }

    public record UnreadCountResponse(long unreadCount) { }

    public record NotificationReadResponse(String id, boolean read, String readAt, long unreadCount) { }

    public record NotificationReadAllResponse(int markedCount, long unreadCount) { }
}
