CREATE TABLE order_attachments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    uploader_id BIGINT NOT NULL,
    uploader_role VARCHAR(16) NOT NULL,
    original_filename VARCHAR(120) NOT NULL,
    content_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_order_attachments_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_order_attachments_order FOREIGN KEY (order_id) REFERENCES repair_orders(id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_attachments_uploader FOREIGN KEY (uploader_id) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_order_attachments_order_created (order_id, created_at, id),
    INDEX idx_order_attachments_order_role (order_id, uploader_role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
