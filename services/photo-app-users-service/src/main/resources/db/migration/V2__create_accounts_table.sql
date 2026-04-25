CREATE TABLE accounts
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    account_name   VARCHAR(100) NOT NULL,
    account_type   VARCHAR(50)  NOT NULL DEFAULT 'BASIC',
    active_account BOOLEAN   DEFAULT TRUE,
    version        BIGINT    DEFAULT 0 NOT NULL,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id)
);
