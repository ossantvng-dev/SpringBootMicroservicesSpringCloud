CREATE TABLE account_roles
(
    account_id BIGINT NOT NULL,
    role_id    BIGINT NOT NULL,
    PRIMARY KEY (account_id, role_id),
    FOREIGN KEY (account_id) REFERENCES accounts (id),
    FOREIGN KEY (role_id) REFERENCES roles (id)
);
