INSERT INTO photos (album_id, file_name, file_url, active_photo, created_at, updated_at)
SELECT al.id, CONCAT('photo_', al.id, '_1.jpg'), CONCAT('https://s3.amazonaws.com/photo-app/album', al.id, '/photo1.jpg'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM albums al;
INSERT INTO photos (album_id, file_name, file_url, active_photo, created_at, updated_at)
SELECT al.id, CONCAT('photo_', al.id, '_2.jpg'), CONCAT('https://s3.amazonaws.com/photo-app/album', al.id, '/photo2.jpg'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM albums al;
INSERT INTO photos (album_id, file_name, file_url, active_photo, created_at, updated_at)
SELECT al.id, CONCAT('photo_', al.id, '_3.jpg'), CONCAT('https://s3.amazonaws.com/photo-app/album', al.id, '/photo3.jpg'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM albums al;
INSERT INTO photos (album_id, file_name, file_url, active_photo, created_at, updated_at)
SELECT al.id, CONCAT('photo_', al.id, '_4.jpg'), CONCAT('https://s3.amazonaws.com/photo-app/album', al.id, '/photo4.jpg'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM albums al;
INSERT INTO photos (album_id, file_name, file_url, active_photo, created_at, updated_at)
SELECT al.id, CONCAT('photo_', al.id, '_5.jpg'), CONCAT('https://s3.amazonaws.com/photo-app/album', al.id, '/photo5.jpg'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM albums al;
INSERT INTO photos (album_id, file_name, file_url, active_photo, created_at, updated_at)
SELECT al.id, CONCAT('photo_', al.id, '_6.jpg'), CONCAT('https://s3.amazonaws.com/photo-app/album', al.id, '/photo6.jpg'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM albums al;
INSERT INTO photos (album_id, file_name, file_url, active_photo, created_at, updated_at)
SELECT al.id, CONCAT('photo_', al.id, '_7.jpg'), CONCAT('https://s3.amazonaws.com/photo-app/album', al.id, '/photo7.jpg'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM albums al;
INSERT INTO photos (album_id, file_name, file_url, active_photo, created_at, updated_at)
SELECT al.id, CONCAT('photo_', al.id, '_8.jpg'), CONCAT('https://s3.amazonaws.com/photo-app/album', al.id, '/photo8.jpg'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM albums al;
INSERT INTO photos (album_id, file_name, file_url, active_photo, created_at, updated_at)
SELECT al.id, CONCAT('photo_', al.id, '_9.jpg'), CONCAT('https://s3.amazonaws.com/photo-app/album', al.id, '/photo9.jpg'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM albums al;
INSERT INTO photos (album_id, file_name, file_url, active_photo, created_at, updated_at)
SELECT al.id, CONCAT('photo_', al.id, '_10.jpg'), CONCAT('https://s3.amazonaws.com/photo-app/album', al.id, '/photo10.jpg'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM albums al;
