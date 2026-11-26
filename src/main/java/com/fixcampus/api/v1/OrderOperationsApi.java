package com.fixcampus.api.v1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fixcampus.form.CompleteRepairForm;
import com.fixcampus.form.OrderFilterForm;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.service.UserService;
import com.fixcampus.view.OrderHistory;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Profile({"web-slice", "demo", "prod"})
public class OrderOperationsApi {
    private final RepairOrderService orders;
    private final OperationalOrders pages;
    private final Validator validator;
    private final UserService users;

    public OrderOperationsApi(RepairOrderService orders, OperationalOrders pages, Validator validator, UserService users) {
        this.orders = orders; this.pages = pages; this.validator = validator; this.users = users;
    }

    public record Worker(String id, String username, String displayName) { }

    @GetMapping("/admin/workers")
    public List<Worker> workers(@AuthenticationPrincipal CampusPrincipal actor) {
        return users.listWorkers(actor).stream()
                .map(w -> new Worker(w.id().toString(), w.username(), w.realName())).toList();
    }

    @GetMapping({"/admin/orders", "/worker/orders"})
    public OperationalOrders.Page list(@AuthenticationPrincipal CampusPrincipal actor,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String q) {
        var filter = new OrderFilterForm(); filter.setStatus(status); filter.setQ(q);
        return pages.list(actor, request.getRequestURI().equals(request.getContextPath() + "/api/v1/admin/orders"),
                page, size, sort, filter);
    }

    @GetMapping("/admin/orders/{id}")
    public Map<String, Object> adminDetail(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id) {
        var d = orders.getForAdmin(actor, id);
        var result = detail(d.id(), d.location(), d.category(), d.description(), d.status(), d.repairResult(),
                d.createdAt(), d.completedAt(), d.history(), d.canAssign() ? List.of("ASSIGN") : List.of());
        result.put("studentUsername", d.studentUsername()); result.put("studentName", d.studentName());
        result.put("workerUsername", d.workerUsername()); result.put("workerName", d.workerName());
        return result;
    }

    @GetMapping("/worker/orders/{id}")
    public Map<String, Object> workerDetail(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id) {
        var d = orders.getForWorker(actor, id);
        var result = detail(d.id(), d.location(), d.category(), d.description(), d.status(), d.repairResult(),
                d.createdAt(), d.completedAt(), d.history(), d.canStart() ? List.of("START")
                        : d.canComplete() ? List.of("COMPLETE") : List.of());
        result.put("studentName", d.studentName()); result.put("studentPhone", d.studentPhone());
        return result;
    }

    @PostMapping(value = "/orders/{id}/assign", consumes = "application/json")
    public ResponseEntity<Void> assign(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id,
            @RequestBody JsonNode body) {
        fields(body, Set.of("workerId"));
        var worker = body.get("workerId");
        if (worker == null || !worker.isTextual() || !worker.textValue().matches("[1-9][0-9]{0,18}"))
            throw new ApiProblem(400, "VALIDATION_ERROR", "workerId 必须是正整数 ID 字符串");
        long workerId;
        try { workerId = Long.parseLong(worker.textValue()); }
        catch (NumberFormatException e) { throw new ApiProblem(400, "VALIDATION_ERROR", "workerId 超出范围"); }
        orders.assign(actor, id, workerId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/orders/{id}/start")
    public ResponseEntity<Void> start(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id,
            @RequestBody(required = false) JsonNode body) {
        empty(body); orders.start(actor, id); return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/orders/{id}/complete", consumes = "application/json")
    public ResponseEntity<Void> complete(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id,
            @RequestBody JsonNode body) {
        fields(body, Set.of("repairResult"));
        var value = body.get("repairResult");
        if (value != null && !value.isNull() && !value.isTextual())
            throw new ApiProblem(400, "VALIDATION_ERROR", "维修说明类型无效");
        var form = new CompleteRepairForm(); form.setRepairResult(value == null || value.isNull() ? null : value.textValue());
        if (!validator.validate(form).isEmpty()) throw new ApiProblem(400, "VALIDATION_ERROR", "维修说明最多 1000 个字符");
        orders.complete(actor, id, form); return ResponseEntity.noContent().build();
    }

    @PostMapping("/orders/{id}/confirm")
    public ResponseEntity<Void> confirm(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id,
            @RequestBody(required = false) JsonNode body) {
        empty(body); orders.confirm(actor, id); return ResponseEntity.noContent().build();
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id,
            @RequestBody(required = false) JsonNode body) {
        empty(body); orders.cancel(actor, id); return ResponseEntity.noContent().build();
    }

    private static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw new ApiProblem(400, "VALIDATION_ERROR", "需要 JSON 对象");
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new ApiProblem(400, "VALIDATION_ERROR", "包含不允许的字段");
        });
    }
    static void empty(JsonNode body) { if (body != null) fields(body, Set.of()); }

    private static Map<String, Object> detail(Long id, String location, com.fixcampus.entity.RepairCategory category, String description,
            com.fixcampus.entity.RepairStatus status, String repairResult, java.time.LocalDateTime createdAt, java.time.LocalDateTime completedAt,
            OrderHistory history, List<String> actions) {
        var result = new LinkedHashMap<String, Object>(); result.put("id", id.toString()); result.put("location", location);
        result.put("category", category); result.put("description", description); result.put("status", status);
        result.put("repairResult", repairResult); result.put("createdAt", StudentApi.time(createdAt));
        result.put("completedAt", StudentApi.time(completedAt)); result.put("availableActions", actions);
        result.put("history", Map.of("incomplete", history.incomplete(), "entries", history.entries().stream().map(e -> {
            var entry = new LinkedHashMap<String, Object>(); entry.put("action", e.action());
            entry.put("occurredAt", StudentApi.time(e.occurredAt())); entry.put("actorRole", e.actorRole());
            entry.put("actorName", e.actorName()); entry.put("detail", e.detail()); entry.put("recorded", e.recorded()); return entry;
        }).toList())); return result;
    }
}
