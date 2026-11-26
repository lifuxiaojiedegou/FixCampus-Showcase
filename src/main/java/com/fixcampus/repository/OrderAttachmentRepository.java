package com.fixcampus.repository;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import com.fixcampus.entity.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class OrderAttachmentRepository {
    private static final String SELECT = """
            SELECT a.id, a.order_id, a.uploader_id, a.uploader_role, u.real_name, a.original_filename,
                   a.content_type, a.size_bytes, a.width, a.height, a.storage_key, a.created_at
            FROM order_attachments a JOIN users u ON u.id = a.uploader_id
            """;
    private static final RowMapper<Attachment> ROW = (rs, row) -> new Attachment(rs.getLong("id"),
            rs.getLong("order_id"), rs.getLong("uploader_id"),
            Role.valueOf(rs.getString("uploader_role").toUpperCase(Locale.ROOT)),
            rs.getString("real_name"), rs.getString("original_filename"), rs.getString("content_type"),
            rs.getLong("size_bytes"), rs.getInt("width"), rs.getInt("height"), rs.getString("storage_key"),
            rs.getObject("created_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public OrderAttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Attachment> findByOrderId(long orderId) {
        return jdbc.query(SELECT + " WHERE a.order_id = ? ORDER BY a.created_at, a.id", ROW, orderId);
    }

    public Optional<Attachment> findByIdAndOrderId(long id, long orderId) {
        return jdbc.query(SELECT + " WHERE a.id = ? AND a.order_id = ?", ROW, id, orderId).stream().findFirst();
    }

    public boolean existsByStorageKey(String storageKey) {
        return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM order_attachments WHERE storage_key = ?)",
                Boolean.class, storageKey);
    }

    public long countByOrderIdAndUploaderRole(long orderId, Role role) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_attachments WHERE order_id = ? AND uploader_role = ?",
                Long.class, orderId, role.name());
    }

    public long sumSizeBytes() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(size_bytes), 0) FROM order_attachments", Long.class);
    }

    public Attachment insert(long orderId, long uploaderId, Role role, String filename, String contentType,
                             long size, int width, int height, String sha256, String key, LocalDateTime createdAt) {
        var id = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO order_attachments (order_id, uploader_id, uploader_role, original_filename,
                                                   content_type, size_bytes, width, height, sha256, storage_key, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, orderId);
            statement.setLong(2, uploaderId);
            statement.setString(3, role.name());
            statement.setString(4, filename);
            statement.setString(5, contentType);
            statement.setLong(6, size);
            statement.setInt(7, width);
            statement.setInt(8, height);
            statement.setString(9, sha256);
            statement.setString(10, key);
            statement.setObject(11, createdAt);
            return statement;
        }, id);
        return findByIdAndOrderId(id.getKey().longValue(), orderId).orElseThrow();
    }

    public void delete(long id, long orderId) {
        jdbc.update("DELETE FROM order_attachments WHERE id = ? AND order_id = ?", id, orderId);
    }

    public record Attachment(long id, long orderId, long uploaderId, Role uploaderRole, String uploaderName,
                             String originalFilename, String contentType, long sizeBytes, int width, int height,
                             String storageKey, LocalDateTime createdAt) { }
}
