-- Additive history storage. No backfill, UPDATE, deletion, or inferred timestamps.
CREATE TABLE IF NOT EXISTS order_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor_id BIGINT NOT NULL,
    actor_role VARCHAR(16) NOT NULL,
    actor_name VARCHAR(50) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    detail VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_events_order_action UNIQUE (order_id, action),
    CONSTRAINT fk_events_order FOREIGN KEY (order_id) REFERENCES repair_orders (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_events_actor FOREIGN KEY (actor_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_events_action_role CHECK (
        (action IN ('SUBMITTED', 'CONFIRMED', 'CANCELLED') AND actor_role = 'STUDENT')
        OR (action = 'ASSIGNED' AND actor_role = 'ADMIN')
        OR (action IN ('REPAIR_STARTED', 'REPAIR_COMPLETED') AND actor_role = 'WORKER')
    ),
    CONSTRAINT ck_events_actor_name CHECK (CHAR_LENGTH(TRIM(actor_name)) >= 1),
    INDEX idx_events_order_id (order_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
