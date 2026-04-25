INSERT INTO user_roles (user_id, role_id)
SELECT id, (SELECT id FROM roles WHERE name = 'ROLE_ADMIN') FROM users WHERE username = 'admin';

INSERT INTO user_roles (user_id, role_id)
SELECT id, (SELECT id FROM roles WHERE name = 'ROLE_USER') FROM users WHERE username <> 'admin';