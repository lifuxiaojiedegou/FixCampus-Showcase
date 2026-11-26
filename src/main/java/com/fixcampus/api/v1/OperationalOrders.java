package com.fixcampus.api.v1;

import java.util.ArrayList;
import java.util.List;
import com.fixcampus.form.OrderFilterForm;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.entity.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database pagination only; business permissions and transitions remain in the existing service. */
@Service
public class OperationalOrders {
    private final JdbcTemplate jdbc;

    public OperationalOrders(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Summary(String id, String location, String category, String status, String createdAt) { }
    public record Page(List<Summary> items, int page, int size, long totalElements, long totalPages, String sort) { }

    @Transactional(readOnly = true)
    public Page list(CampusPrincipal actor, boolean admin, int page, int size, String sort, OrderFilterForm filter) {
        if (actor == null || actor.getId() == null || actor.getRole() != (admin ? Role.ADMIN : Role.WORKER))
            throw new AccessDeniedException("当前身份无权读取此列表");
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50
                || !List.of("createdAt,asc", "createdAt,desc").contains(sort) || !filter.isStatusValid()
                || filter.getQ().codePointCount(0, filter.getQ().length()) > 120)
            throw new ApiProblem(400, "INVALID_QUERY", "查询参数无效");
        String where = " FROM repair_orders WHERE 1=1";
        var args = new ArrayList<Object>();
        if (!admin) { where += " AND worker_id=?"; args.add(actor.getId()); }
        if (!filter.getStatus().isEmpty()) { where += " AND status=?"; args.add(filter.getStatus()); }
        if (!filter.getQ().isEmpty()) {
            where += " AND (id=? OR location LIKE ? ESCAPE '!')";
            args.add(filter.orderId()); args.add(filter.locationPattern());
        }
        long count = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        String direction = sort.endsWith("asc") ? " ASC" : " DESC";
        long offset = (long) page * size;
        args.add(size);
        args.add(offset);
        String pageSql;
        if (admin && !filter.hasFilters() && offset > 0) {
            // Scan the narrow sort index for the offset, then fetch only the rows on this page.
            pageSql = "SELECT o.id,o.location,o.category,o.status,o.created_at FROM ("
                    + "SELECT id,created_at FROM repair_orders ORDER BY created_at" + direction + ",id" + direction
                    + " LIMIT ? OFFSET ?) page JOIN repair_orders o ON o.id=page.id"
                    + " ORDER BY page.created_at" + direction + ",page.id" + direction;
        } else {
            pageSql = "SELECT id,location,category,status,created_at" + where
                    + " ORDER BY created_at" + direction + ",id" + direction + " LIMIT ? OFFSET ?";
        }
        var items = jdbc.query(pageSql,
                (rs, row) -> new Summary(Long.toString(rs.getLong(1)), rs.getString(2), rs.getString(3),
                        rs.getString(4), StudentApi.time(rs.getTimestamp(5).toLocalDateTime())), args.toArray());
        return new Page(items, page, size, count, (count + size - 1) / size, sort);
    }
}
