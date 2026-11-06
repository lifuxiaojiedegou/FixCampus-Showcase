-- Stage 4.2. Matches the approved design; no account or existing data is reset.
CREATE TABLE IF NOT EXISTS repair_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_id BIGINT NOT NULL,
    worker_id BIGINT NULL,
    location VARCHAR(120) NOT NULL,
    category VARCHAR(32) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    repair_result VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_orders_student FOREIGN KEY (student_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_orders_worker FOREIGN KEY (worker_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_orders_status CHECK (status IN (
        'PENDING_ASSIGNMENT', 'PENDING_REPAIR', 'IN_PROGRESS',
        'PENDING_CONFIRMATION', 'COMPLETED', 'CANCELLED'
    )),
    CONSTRAINT ck_orders_category CHECK (category IN (
        'WATER_ELECTRICITY', 'DOORS_FURNITURE', 'NETWORK', 'AIR_CONDITIONING', 'OTHER'
    )),
    CONSTRAINT ck_orders_worker_state CHECK (
        (status IN ('PENDING_ASSIGNMENT', 'CANCELLED') AND worker_id IS NULL)
        OR (status IN ('PENDING_REPAIR', 'IN_PROGRESS', 'PENDING_CONFIRMATION', 'COMPLETED')
            AND worker_id IS NOT NULL)
    ),
    CONSTRAINT ck_orders_completed CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL)
    ),
    CONSTRAINT ck_orders_time CHECK (completed_at IS NULL OR completed_at >= created_at),
    CONSTRAINT ck_orders_location CHECK (CHAR_LENGTH(TRIM(location)) >= 1),
    CONSTRAINT ck_orders_description CHECK (CHAR_LENGTH(TRIM(description)) >= 1),
    INDEX idx_orders_student_created (student_id, created_at DESC, id DESC),
    INDEX idx_orders_worker_created (worker_id, created_at DESC, id DESC),
    INDEX idx_orders_status_created (status, created_at DESC, id DESC),
    INDEX idx_orders_created (created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
