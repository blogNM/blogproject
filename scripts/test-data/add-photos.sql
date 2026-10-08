-- 이미 테스트 데이터를 넣은 DB에 3단계용 사진만 더한다. 회원·블로그는 건드리지 않는다.
-- 사용법: mysql -u blog -p blog < scripts/test-data/add-photos.sql 후 images 폴더를 UPLOAD_DIR에 복사
SET NAMES utf8mb4;
UPDATE posts SET content_md = '## 을지로 국밥

새벽에도 문을 여는 국밥집. **깍두기**가 맛있다.

![국밥](/uploads/00000000-0000-0000-0000-000000000001)

![깍두기](/uploads/00000000-0000-0000-0000-000000000002)' WHERE id = 1 AND content_md NOT LIKE '%/uploads/%';
UPDATE posts SET content_md = '성수동에서 들른 카페 세 곳을 정리했다.

![카페1](/uploads/00000000-0000-0000-0000-000000000003)' WHERE id = 2 AND content_md NOT LIKE '%/uploads/%';
UPDATE posts SET content_md = '성산일출봉부터 우도까지

![성산](/uploads/00000000-0000-0000-0000-000000000004)' WHERE id = 5 AND content_md NOT LIKE '%/uploads/%';
UPDATE posts SET content_md = '치즈 고양이 ''보리''

![보리](/uploads/00000000-0000-0000-0000-000000000005)' WHERE id = 10 AND content_md NOT LIKE '%/uploads/%';
UPDATE images SET size_bytes = 30928 WHERE stored_name = '00000000-0000-0000-0000-000000000001';
UPDATE images SET size_bytes = 31010 WHERE stored_name = '00000000-0000-0000-0000-000000000002';
UPDATE images SET size_bytes = 11552 WHERE stored_name = '00000000-0000-0000-0000-000000000003';
UPDATE images SET size_bytes = 6343 WHERE stored_name = '00000000-0000-0000-0000-000000000004';
UPDATE images SET size_bytes = 68780 WHERE stored_name = '00000000-0000-0000-0000-000000000005';
UPDATE images SET size_bytes = 6408 WHERE stored_name = '00000000-0000-0000-0000-000000000006';
UPDATE images SET size_bytes = 30809 WHERE stored_name = '00000000-0000-0000-0000-000000000007';
UPDATE blogs SET cover_image_id = 7 WHERE slug = 'foodie-seoul' AND cover_image_id IS NULL;
UPDATE members SET profile_image_id = 6 WHERE email = 'chaerim.dev@example.com' AND profile_image_id IS NULL;
