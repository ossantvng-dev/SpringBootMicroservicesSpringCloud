INSERT INTO account_roles (account_id, role_id)
SELECT id, 2 FROM accounts WHERE account_name = 'admin_account';

INSERT INTO account_roles (account_id, role_id)
SELECT id, 1 FROM accounts WHERE account_name <> 'admin_account';