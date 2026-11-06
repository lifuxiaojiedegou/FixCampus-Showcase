-- Stage 4.1 only. Run explicitly with a database administrator, not the application account.
CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password VARCHAR(255) NOT NULL,
    real_name VARCHAR(50) NOT NULL,
    phone VARCHAR(30) NULL,
    role VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT ck_users_role CHECK (role IN ('STUDENT', 'WORKER', 'ADMIN')),
    CONSTRAINT ck_users_username CHECK (
        username = LOWER(username) AND CHAR_LENGTH(username) BETWEEN 4 AND 30
    ),
    CONSTRAINT ck_users_real_name CHECK (CHAR_LENGTH(TRIM(real_name)) >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
