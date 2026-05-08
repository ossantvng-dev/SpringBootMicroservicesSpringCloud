INSERT INTO albums (account_id, title, description, active_album, created_at, updated_at)
SELECT a.id, CONCAT('album_', a.id, '_1'), CONCAT('Álbum de la cuenta ', a.account_name, ' número 1'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM accounts a;
INSERT INTO albums (account_id, title, description, active_album, created_at, updated_at)
SELECT a.id, CONCAT('album_', a.id, '_2'), CONCAT('Álbum de la cuenta ', a.account_name, ' número 2'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM accounts a;
INSERT INTO albums (account_id, title, description, active_album, created_at, updated_at)
SELECT a.id, CONCAT('album_', a.id, '_3'), CONCAT('Álbum de la cuenta ', a.account_name, ' número 3'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM accounts a;
