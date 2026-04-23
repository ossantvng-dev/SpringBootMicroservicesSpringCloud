CREATE TABLE albums
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id   BIGINT       NOT NULL,
    title        VARCHAR(100) NOT NULL,
    description  VARCHAR(255),
    active_album BOOLEAN   DEFAULT TRUE,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES accounts (id)
);
