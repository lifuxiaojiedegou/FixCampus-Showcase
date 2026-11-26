CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(100) NOT NULL,
    body VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_notifications_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_notifications_order FOREIGN KEY (order_id) REFERENCES repair_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_notifications_event_type CHECK (event_type IN (
        'ORDER_SUBMITTED', 'ORDER_ASSIGNED', 'REPAIR_STARTED', 'REPAIR_COMPLETED', 'ORDER_CONFIRMED', 'ORDER_CANCELLED'
    ) AND CHAR_LENGTH(event_type) = CHAR_LENGTH(TRIM(event_type))),
    INDEX idx_notifications_recipient_created (recipient_id, created_at, id),
    INDEX idx_notifications_recipient_unread (recipient_id, read_at, id),
    INDEX idx_notifications_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
