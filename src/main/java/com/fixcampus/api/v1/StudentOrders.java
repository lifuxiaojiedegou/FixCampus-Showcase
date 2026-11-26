package com.fixcampus.api.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import com.fixcampus.form.OrderFilterForm;
import com.fixcampus.form.RepairOrderForm;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.RepairOrderService;
import com.fixcampus.entity.Role;
import com.fixcampus.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

@Service
public class StudentOrders {
    private final RepairOrderService orders;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UserRepository users;
    public StudentOrders(RepairOrderService orders, JdbcTemplate jdbc, PlatformTransactionManager manager, UserRepository users) {
        this.orders = orders; this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager);
        this.users = users;
    }
    public String create(CampusPrincipal actor, RepairOrderForm input, String key) {
        if (actor == null || actor.getId() == null || actor.getRole() != Role.STUDENT)
            throw new AccessDeniedException("此操作仅限学生账号");
        users.findByIdAndRole(actor.getId(), Role.STUDENT)
                .orElseThrow(() -> new AccessDeniedException("学生账号不可用"));
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw new ApiProblem(400, "VALIDATION_ERROR", "需要有效的 Idempotency-Key UUID v4");
        byte[] hash = hash(input);
        String replay = replay(actor.getId(), key, hash);
        if (replay != null) return replay;
        try {
            return transactions.execute(tx -> {
                Long id = orders.createForStudent(actor, input);
                jdbc.update("INSERT INTO idempotency_requests(actor_id,operation_code,request_key,payload_hash,order_id,created_at) "
                        + "VALUES(?,'CREATE_REPAIR_ORDER',?,?,?,CURRENT_TIMESTAMP(6))", actor.getId(), key, hash, id);
                return id.toString();
            });
        } catch (DuplicateKeyException conflict) {
            // The losing transaction (including its order/event) has already rolled back.
            String result = replay(actor.getId(), key, hash);
            if (result != null) return result;
            throw new ApiProblem(503, "TEMPORARILY_UNAVAILABLE", "提交结果暂无法确认，请保留原提交键");
        }
    }
    private String replay(Long actor, String key, byte[] hash) {
        return jdbc.query("SELECT payload_hash,order_id FROM idempotency_requests WHERE actor_id=? "
                + "AND operation_code='CREATE_REPAIR_ORDER' AND request_key=?", rs -> {
                    if (!rs.next()) return null;
                    if (!MessageDigest.isEqual(hash, rs.getBytes(1)))
                        throw new ApiProblem(409, "IDEMPOTENCY_KEY_REUSED", "该提交键已用于其他内容");
                    return Long.toString(rs.getLong(2));
                }, actor, key);
    }
    private byte[] hash(RepairOrderForm input) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            var out = new java.io.DataOutputStream(bytes);
            for (String value : List.of("v1", input.getLocation(), input.getCategory().name(), input.getDescription())) {
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                out.writeInt(encoded.length); out.write(encoded);
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public Map<String, Object> list(CampusPrincipal actor, int page, int size, String sort, OrderFilterForm filter) {
        // Existing service performs the authoritative role check; SQL keeps ownership in the database.
        orders.countAwaitingConfirmation(actor);
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50
                || !List.of("createdAt,desc", "createdAt,asc").contains(sort) || !filter.isStatusValid()
                || filter.getQ().codePointCount(0, filter.getQ().length()) > 120)
            throw new ApiProblem(400, "INVALID_QUERY", "查询参数无效");
        String where = " FROM repair_orders WHERE student_id=?";
        var args = new java.util.ArrayList<Object>(); args.add(actor.getId());
        if (!filter.getStatus().isEmpty()) { where += " AND status=?"; args.add(filter.getStatus()); }
        if (!filter.getQ().isEmpty()) {
            where += " AND (id=? OR location LIKE ? ESCAPE '!')";
            args.add(filter.orderId()); args.add(filter.locationPattern());
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        String direction = sort.endsWith("asc") ? " ASC" : " DESC";
        args.add(size); args.add((long) page * size);
        var items = jdbc.query("SELECT id,location,category,status,created_at" + where + " ORDER BY created_at"
                + direction + ",id" + direction + " LIMIT ? OFFSET ?", (rs, n) -> Map.of(
                "id", Long.toString(rs.getLong(1)), "location", rs.getString(2), "category", rs.getString(3),
                "status", rs.getString(4), "createdAt", StudentApi.time(rs.getTimestamp(5).toLocalDateTime())), args.toArray());
        return Map.of("items", items, "page", page, "size", size, "totalElements", count,
                "totalPages", (count + size - 1) / size, "sort", sort);
    }
}
