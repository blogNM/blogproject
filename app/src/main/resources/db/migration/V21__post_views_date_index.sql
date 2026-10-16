-- 지난 글 조회 기록을 날마다 지울 때(VisitCleanupJob) 표 전체를 훑지 않게 날짜 색인을 둔다.
-- 조회 기록은 "같은 사람·같은 날 한 번"을 가리는 데만 쓰므로 어제보다 오래된 것은 필요 없다 (D-77).
CREATE INDEX idx_post_views_date ON post_views (view_date);
