-- 메인 공지사항 카테고리 (D-98). 관리자가 쓰는 일반 공지는 NOTICE, 깃허브 릴리스로 올라오는 공지는 UPDATE
ALTER TABLE notices ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'NOTICE' AFTER title;
ALTER TABLE notices ADD CONSTRAINT ck_notices_category CHECK (category IN ('NOTICE', 'UPDATE'));

-- 이미 올라간 릴리스 공지 (제목이 "Creat_ v1.0.0 ..." 모양)
UPDATE notices SET category = 'UPDATE' WHERE title LIKE 'Creat\_ v%';
