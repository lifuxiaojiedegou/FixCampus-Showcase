package com.fixcampus.api.v1;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.form.OrderFilterForm;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.security.SessionLifecycle;
import com.fixcampus.service.RepairOrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@org.springframework.context.annotation.Profile({"web-slice", "demo", "prod"})
public class StudentApi {
    private final AuthenticationManager manager;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository tokens;
    private final RepairOrderService orders;
    private final StudentOrders studentOrders;
    private final Validator validator;
    private final SessionLifecycle lifecycle;
    private final boolean secureSessionCookie;
    public StudentApi(AuthenticationManager manager, HttpSessionSecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository tokens, RepairOrderService orders, StudentOrders studentOrders, Validator validator,
            SessionLifecycle lifecycle, @Value("${server.servlet.session.cookie.secure:true}") boolean secureSessionCookie) {
        this.manager = manager; this.contexts = contexts; this.tokens = tokens;
        this.orders = orders; this.studentOrders = studentOrders; this.validator = validator;
        this.lifecycle = lifecycle;
        this.secureSessionCookie = secureSessionCookie;
    }
    @GetMapping("/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("headerName", token.getHeaderName(), "token", token.getToken()); }
    @PostMapping(value = "/auth/login", consumes = "application/json")
    public Map<String, Object> login(@RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response) {
        fields(body, Set.of("username", "password"));
        String username = text(body, "username"), password = text(body, "password");
        if (username.length() > 100 || password.length() > 512 || username.isBlank() || password.isEmpty())
            throw new ApiProblem(400, "VALIDATION_ERROR", "登录参数无效");
        var current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null && current.getPrincipal() instanceof CampusPrincipal)
            throw new ApiProblem(409, "AUTH_ALREADY_AUTHENTICATED", "请先退出当前身份");
        try {
            var authenticated = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
            new ChangeSessionIdAuthenticationStrategy().onAuthentication(authenticated, request, response);
            var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authenticated);
            SecurityContextHolder.setContext(context); contexts.saveContext(context, request, response);
            lifecycle.authenticationSucceeded(request);
            tokens.saveToken(null, request, response);
            return me((CampusPrincipal) authenticated.getPrincipal());
        } catch (org.springframework.security.authentication.InternalAuthenticationServiceException e) {
            throw new ApiProblem(503, "TEMPORARILY_UNAVAILABLE", "认证服务暂不可用");
        } catch (org.springframework.security.core.AuthenticationException e) {
            throw new ApiProblem(401, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
    }
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal CampusPrincipal principal) {
        return Map.of("id", principal.getId().toString(), "username", principal.getUsername(),
                "displayName", principal.getRealName(), "role", principal.getRole().name());
    }
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        tokens.saveToken(null, request, response);
        new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler().logout(
                request, response, SecurityContextHolder.getContext().getAuthentication());
        var cookie = new jakarta.servlet.http.Cookie("JSESSIONID", "");
        cookie.setPath("/"); cookie.setMaxAge(0); cookie.setHttpOnly(true);
        cookie.setSecure(secureSessionCookie || request.isSecure());
        cookie.setAttribute("SameSite", "Lax"); response.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }
    @PostMapping(value = "/orders", consumes = "application/json")
    public ResponseEntity<?> create(@AuthenticationPrincipal CampusPrincipal actor, @RequestBody JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        fields(body, Set.of("location", "category", "description"));
        var form = new RepairOrderForm(); form.setLocation(text(body, "location")); form.setDescription(text(body, "description"));
        try { form.setCategory(RepairCategory.valueOf(text(body, "category"))); }
        catch (IllegalArgumentException e) { throw new ApiProblem(400, "VALIDATION_ERROR", "故障类型无效"); }
        if (!validator.validate(form).isEmpty()) throw new ApiProblem(400, "VALIDATION_ERROR", "请检查地点及问题描述长度");
        String id = studentOrders.create(actor, form, key);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + id)).body(Map.of("id", id, "status", "PENDING_ASSIGNMENT"));
    }
    @GetMapping("/orders")
    public Map<String, Object> list(@AuthenticationPrincipal CampusPrincipal actor,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String q) {
        var filter = new OrderFilterForm(); filter.setStatus(status); filter.setQ(q);
        return studentOrders.list(actor, page, size, sort, filter);
    }
    @GetMapping("/orders/{id}")
    public Map<String, Object> detail(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable Long id) {
        var detail = orders.getForStudent(actor, id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", detail.id().toString()); result.put("location", detail.location()); result.put("category", detail.category());
        result.put("description", detail.description()); result.put("status", detail.status()); result.put("workerName", detail.workerName());
        result.put("repairResult", detail.repairResult()); result.put("createdAt", time(detail.createdAt()));
        result.put("completedAt", time(detail.completedAt()));
        result.put("availableActions", detail.canCancel() ? java.util.List.of("CANCEL")
                : detail.canConfirm() ? java.util.List.of("CONFIRM") : java.util.List.of());
        result.put("history", Map.of("incomplete", detail.history().incomplete(), "entries", detail.history().entries().stream().map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>(); entry.put("action", e.action()); entry.put("occurredAt", time(e.occurredAt()));
            entry.put("actorRole", e.actorRole()); entry.put("actorName", e.actorName()); entry.put("detail", e.detail()); entry.put("recorded", e.recorded());
            return entry;
        }).toList()));
        return result;
    }
    static String time(LocalDateTime time) { return time == null ? null : time.atZone(ZoneId.of("Asia/Hong_Kong")).toOffsetDateTime().toString(); }
    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw new ApiProblem(400, "VALIDATION_ERROR", "需要 JSON 对象");
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw new ApiProblem(400, "VALIDATION_ERROR", "包含不允许的字段"); });
    }
    private static String text(JsonNode node, String name) {
        if (!node.has(name) || !node.get(name).isTextual()) throw new ApiProblem(400, "VALIDATION_ERROR", "缺少有效字段");
        return node.get(name).textValue();
    }
}
