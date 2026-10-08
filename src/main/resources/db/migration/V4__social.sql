-- 5단계 소셜·알림.

-- [내 활동] 목록마다 다른 사람에게 보여줄지 (D-91). 기본은 보여줌.
ALTER TABLE members
  ADD COLUMN show_posts BOOLEAN NOT NULL DEFAULT TRUE COMMENT '내가 쓴 글 공개' AFTER bio,
  ADD COLUMN show_likes BOOLEAN NOT NULL DEFAULT TRUE COMMENT '좋아요 한 글 공개' AFTER show_posts,
  ADD COLUMN show_following BOOLEAN NOT NULL DEFAULT TRUE COMMENT '팔로잉 공개' AFTER show_likes,
  ADD COLUMN show_subscriptions BOOLEAN NOT NULL DEFAULT TRUE COMMENT '구독한 블로그 공개' AFTER show_following;

-- 신고 사유 (추천안 #9). 기타는 detail에 직접 적는다.
UPDATE reports SET reason_code = 'ETC' WHERE reason_code NOT IN ('SPAM', 'ABUSE', 'OBSCENE', 'PRIVACY', 'COPYRIGHT', 'ETC');
ALTER TABLE reports
  ADD CONSTRAINT ck_reports_reason CHECK (reason_code IN ('SPAM', 'ABUSE', 'OBSCENE', 'PRIVACY', 'COPYRIGHT', 'ETC'));

-- 내가 받은 알림을 탭별 최신순으로 읽는다. 팔로워 목록은 팔로우받는 쪽에서 찾는다.
CREATE INDEX idx_notifications_tab ON notifications (recipient_id, tab, created_at);
CREATE INDEX idx_follows_followee ON follows (followee_id, created_at);
CREATE INDEX idx_post_likes_member ON post_likes (member_id, created_at);
