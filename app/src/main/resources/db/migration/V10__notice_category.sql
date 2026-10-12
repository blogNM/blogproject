-- 메인 공지사항 카테고리 (D-98). 일반 NOTICE, 업데이트 UPDATE(깃허브 릴리스), 수정 FIX, 점검 MAINTENANCE
ALTER TABLE notices ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'NOTICE' AFTER title;
ALTER TABLE notices ADD CONSTRAINT ck_notices_category CHECK (category IN ('NOTICE', 'UPDATE', 'FIX', 'MAINTENANCE'));

-- 이미 올라간 릴리스 공지 (제목이 "Creat_ v1.0.0 ..." 모양)
UPDATE notices SET category = 'UPDATE' WHERE title LIKE 'Creat\_ v%';
