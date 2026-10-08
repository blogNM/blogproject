-- 메인블로그 테스트 데이터 (2026-10-07 ERD 작업 때 만든 188행을 지금 스키마에 맞게 옮김)
-- 넣는 방법과 계정별로 보이는 것: docs/05-guides/테스트-데이터.md
-- 주의: 이 파일은 데이터 표를 모두 비우고 다시 채운다. scripts/test-data/load.sh가 먼저 백업한다.
-- 바꾼 점: 이메일은 실제 주소로 메일이 가지 않도록 @example.com, 비밀번호는 모두 Test1234!
--         인증번호·로그인 토큰·로그인 실패·배치 잠금 표는 넣지 않음(로그인을 방해하거나 금방 만료됨)

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE admins;
TRUNCATE TABLE members;
TRUNCATE TABLE email_verifications;
TRUNCATE TABLE email_find_tokens;
TRUNCATE TABLE refresh_tokens;
TRUNCATE TABLE login_failures;
TRUNCATE TABLE blogs;
TRUNCATE TABLE blog_memberships;
TRUNCATE TABLE join_requests;
TRUNCATE TABLE ownership_transfers;
TRUNCATE TABLE member_sanctions;
TRUNCATE TABLE blacklist_entries;
TRUNCATE TABLE blacklist_appeals;
TRUNCATE TABLE blog_closing_notices;
TRUNCATE TABLE blog_tags;
TRUNCATE TABLE categories;
TRUNCATE TABLE posts;
TRUNCATE TABLE tags;
TRUNCATE TABLE post_tags;
TRUNCATE TABLE images;
TRUNCATE TABLE comments;
TRUNCATE TABLE post_likes;
TRUNCATE TABLE post_views;
TRUNCATE TABLE recent_searches;
TRUNCATE TABLE follows;
TRUNCATE TABLE subscriptions;
TRUNCATE TABLE blocks;
TRUNCATE TABLE reports;
TRUNCATE TABLE notifications;
TRUNCATE TABLE notification_settings;
TRUNCATE TABLE admin_action_logs;
TRUNCATE TABLE owner_warnings;
TRUNCATE TABLE notices;
TRUNCATE TABLE shedlock;

-- admins 2행
INSERT INTO admins (id, login_id, password_hash, name, created_at, last_login_at) VALUES
  (1, 'admin_kim', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '김관리', '2026-09-01 09:00:00', '2026-10-07 09:10:00'),
  (2, 'admin_lee', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '이운영', '2026-09-01 09:00:00', NULL);

-- members 11행
INSERT INTO members (id, email, password_hash, name, nickname, phone, profile_image_id, bio, status, notification_retention_days, terms_agreed_at, privacy_agreed_at, age14_confirmed_at, created_at, withdrawn_at) VALUES
  (1, 'chaerim.dev@example.com', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '김채림', '채림', '01012345678', 6, '블로그 플랫폼을 만드는 개발자', 'ACTIVE', 30, '2026-09-02 10:00:00', '2026-09-02 10:00:00', '2026-09-02 10:00:00', '2026-09-02 10:00:00', NULL),
  (2, 'minsu.park@example.com', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '박민수', '민수네밥상', '01023456789', NULL, '맛집 기록', 'ACTIVE', 30, '2026-09-03 10:00:00', '2026-09-03 10:00:00', '2026-09-03 10:00:00', '2026-09-03 10:00:00', NULL),
  (3, 'jiyoung.lee@example.com', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '이지영', '지영여행', '01034567890', NULL, '주말마다 떠나는 여행', 'ACTIVE', 30, '2026-09-04 10:00:00', '2026-09-04 10:00:00', '2026-09-04 10:00:00', '2026-09-04 10:00:00', NULL),
  (4, 'hyunwoo.choi@example.com', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '최현우', '현우코딩', '01045678901', NULL, '자바·스프링 공부', 'ACTIVE', 30, '2026-09-05 10:00:00', '2026-09-05 10:00:00', '2026-09-05 10:00:00', '2026-09-05 10:00:00', NULL),
  (5, 'soyeon.jung@example.com', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '정소연', '소연', '01056789012', NULL, NULL, 'ACTIVE', 30, '2026-09-06 10:00:00', '2026-09-06 10:00:00', '2026-09-06 10:00:00', '2026-09-06 10:00:00', NULL),
  (6, 'donghyun.kang@example.com', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '강동현', '동현', '01067890123', NULL, '사진 찍는 사람', 'ACTIVE', 30, '2026-09-07 10:00:00', '2026-09-07 10:00:00', '2026-09-07 10:00:00', '2026-09-07 10:00:00', NULL),
  (7, 'yuna.yoon@example.com', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '윤유나', '유나책방', '01078901234', NULL, '읽은 책 정리', 'ACTIVE', 30, '2026-09-08 10:00:00', '2026-09-08 10:00:00', '2026-09-08 10:00:00', '2026-09-08 10:00:00', NULL),
  (8, 'seojun.han@example.com', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '한서준', '서준', '01089012345', NULL, NULL, 'ACTIVE', 30, '2026-09-09 10:00:00', '2026-09-09 10:00:00', '2026-09-09 10:00:00', '2026-09-09 10:00:00', NULL),
  (9, 'haeun.oh@example.com', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '오하은', '하은', '01090123456', NULL, '고양이 집사', 'ACTIVE', 30, '2026-09-10 10:00:00', '2026-09-10 10:00:00', '2026-09-10 10:00:00', '2026-09-10 10:00:00', NULL),
  (10, 'jaewon.shin@example.com', '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', '신재원', '재원', '01001234567', NULL, NULL, 'ACTIVE', 7, '2026-09-11 10:00:00', '2026-09-11 10:00:00', '2026-09-11 10:00:00', '2026-09-11 10:00:00', NULL),
  (11, NULL, '$2a$10$fMbOdhlmxkas/E3fgYOsXe.VAPCtQI7.PLEr..rSuq5qailHZlzTy', NULL, '탈퇴회원11', NULL, NULL, NULL, 'WITHDRAWN', 30, '2026-09-12 10:00:00', '2026-09-12 10:00:00', '2026-09-12 10:00:00', '2026-09-12 10:00:00', '2026-10-01 18:00:00');

-- tags 10행
INSERT INTO tags (id, name) VALUES
  (1, '맛집'),
  (2, '서울'),
  (3, '여행'),
  (4, '제주'),
  (5, '자바'),
  (6, '스프링'),
  (7, '독서'),
  (8, '고양이'),
  (9, '사진'),
  (10, '카페');

-- blogs 5행
INSERT INTO blogs (id, slug, name, description, owner_id, visibility, join_policy, share_token, status, close_scheduled_at, close_requested_by, created_at, cover_image_id, is_hidden, closed_at, notification_retention_days) VALUES
  (1, 'foodie-seoul', '서울 맛집 탐방', '서울 구석구석 맛집 기록', 2, 'PUBLIC', 'FREE', NULL, 'ACTIVE', NULL, NULL, '2026-09-05 12:00:00', 7, FALSE, NULL, 30),
  (2, 'weekend-trip', '주말 여행 클럽', '함께 다닌 여행 이야기', 3, 'PUBLIC', 'APPROVAL', NULL, 'ACTIVE', NULL, NULL, '2026-09-06 12:00:00', NULL, FALSE, NULL, 30),
  (3, 'java-study', '자바 스터디', '스프링 부트 스터디 기록', 4, 'LINK_ONLY', 'APPROVAL', '9f2c4a7b1e6d3f80a5c2b7e4d1f6a3c9', 'ACTIVE', NULL, NULL, '2026-09-07 12:00:00', NULL, FALSE, NULL, 30),
  (4, 'book-club', '유나의 책방', '한 달에 두 권', 7, 'PRIVATE', 'APPROVAL', NULL, 'CLOSING', '2026-10-13 04:00:00', 'OWNER', '2026-09-08 12:00:00', NULL, FALSE, NULL, 30),
  (5, 'cat-diary', '고양이 일기', '우리 집 고양이들', 9, 'PUBLIC', 'FREE', NULL, 'ACTIVE', NULL, NULL, '2026-09-09 12:00:00', NULL, FALSE, NULL, 30);

-- blog_memberships 20행
INSERT INTO blog_memberships (id, blog_id, member_id, role, status, perm_edit_info, perm_manage_members, perm_manage_posts, sub_owner_since, suspended_until, joined_at, left_at) VALUES
  (1, 1, 2, 'OWNER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-05 12:00:00', NULL),
  (2, 2, 3, 'OWNER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-06 12:00:00', NULL),
  (3, 3, 4, 'OWNER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-07 12:00:00', NULL),
  (4, 4, 7, 'OWNER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-08 12:00:00', NULL),
  (5, 5, 9, 'OWNER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-09 12:00:00', NULL),
  (6, 1, 1, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', NULL),
  (7, 1, 5, 'SUB_OWNER', 'ACTIVE', TRUE, TRUE, TRUE, '2026-09-15 10:00:00', NULL, '2026-09-10 10:00:00', NULL),
  (8, 1, 6, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', NULL),
  (9, 1, 8, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, '2026-10-10 10:00:00', '2026-09-10 10:00:00', NULL),
  (10, 1, 10, 'MEMBER', 'KICKED', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', '2026-10-02 11:00:00'),
  (11, 2, 1, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', NULL),
  (12, 2, 6, 'SUB_OWNER', 'ACTIVE', FALSE, TRUE, FALSE, '2026-09-20 10:00:00', NULL, '2026-09-10 10:00:00', NULL),
  (13, 2, 9, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', NULL),
  (14, 3, 1, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', NULL),
  (15, 3, 10, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', NULL),
  (16, 4, 3, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', NULL),
  (17, 4, 5, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', NULL),
  (18, 5, 2, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', NULL),
  (19, 5, 7, 'MEMBER', 'ACTIVE', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', NULL),
  (20, 5, 11, 'MEMBER', 'LEFT', FALSE, FALSE, FALSE, NULL, NULL, '2026-09-10 10:00:00', '2026-09-30 10:00:00');

-- categories 6행
INSERT INTO categories (id, blog_id, name, sort_order) VALUES
  (1, 1, '한식', 1),
  (2, 1, '카페', 2),
  (3, 2, '국내', 1),
  (4, 3, '스프링', 1),
  (5, 4, '소설', 1),
  (6, 5, '일상', 1);

-- posts 13행
INSERT INTO posts (id, blog_id, author_id, category_id, title, content_md, is_notice, author_left, status, deleted_by, deleted_at, view_count, like_count, comment_count, created_at, updated_at) VALUES
  (1, 1, 2, 1, '을지로 노포 국밥집', '## 을지로 국밥

새벽에도 문을 여는 국밥집. **깍두기**가 맛있다.

![국밥](/uploads/00000000-0000-0000-0000-000000000001)

![깍두기](/uploads/00000000-0000-0000-0000-000000000002)', FALSE, FALSE, 'NORMAL', NULL, NULL, 4, 3, 3, '2026-10-02 09:00:00', '2026-10-02 09:00:00'),
  (2, 1, 1, 2, '성수동 카페 세 곳', '성수동에서 들른 카페 세 곳을 정리했다.

![카페1](/uploads/00000000-0000-0000-0000-000000000003)', FALSE, FALSE, 'NORMAL', NULL, NULL, 0, 0, 0, '2026-10-03 09:00:00', '2026-10-03 09:00:00'),
  (3, 1, 5, 1, '맛집 블로그 이용 안내', '글을 쓰기 전에 꼭 읽어 주세요.', TRUE, FALSE, 'NORMAL', NULL, NULL, 0, 0, 0, '2026-10-04 09:00:00', '2026-10-04 09:00:00'),
  (4, 1, 6, 2, '연남동 브런치', '주말 브런치 사진 모음', FALSE, FALSE, 'DELETED', 'OWNER', '2026-10-05 15:00:00', 0, 0, 0, '2026-10-05 09:00:00', '2026-10-05 09:00:00'),
  (5, 2, 3, 3, '제주 동쪽 2박 3일', '성산일출봉부터 우도까지

![성산](/uploads/00000000-0000-0000-0000-000000000004)', FALSE, FALSE, 'NORMAL', NULL, NULL, 1, 3, 1, '2026-10-06 09:00:00', '2026-10-06 09:00:00'),
  (6, 2, 1, 3, '강릉 당일치기', 'KTX로 다녀온 강릉', FALSE, FALSE, 'NORMAL', NULL, NULL, 0, 0, 0, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
  (7, 3, 4, 4, '스프링 시큐리티 CSRF 정리', 'X-XSRF-TOKEN 헤더를 쓰는 이유', FALSE, FALSE, 'NORMAL', NULL, NULL, 0, 2, 1, '2026-10-02 09:00:00', '2026-10-02 09:00:00'),
  (8, 3, 1, 4, 'JPA N+1 문제', 'fetch join으로 해결한 기록', FALSE, FALSE, 'NORMAL', NULL, NULL, 0, 0, 0, '2026-10-03 09:00:00', '2026-10-03 09:00:00'),
  (9, 4, 7, 5, '10월에 읽은 책', '소설 두 권 후기', FALSE, FALSE, 'NORMAL', NULL, NULL, 0, 0, 0, '2026-10-04 09:00:00', '2026-10-04 09:00:00'),
  (10, 5, 9, 6, '첫째 고양이 소개', '치즈 고양이 ''보리''

![보리](/uploads/00000000-0000-0000-0000-000000000005)', FALSE, FALSE, 'NORMAL', NULL, NULL, 1, 2, 1, '2026-10-05 09:00:00', '2026-10-05 09:00:00'),
  (11, 5, 2, 6, '고양이 간식 추천', '직접 먹여 본 간식', FALSE, FALSE, 'NORMAL', NULL, NULL, 0, 0, 0, '2026-10-06 09:00:00', '2026-10-06 09:00:00'),
  (12, 5, 11, 6, '우리 동네 길고양이', '탈퇴한 회원이 남긴 글', FALSE, TRUE, 'NORMAL', NULL, NULL, 0, 0, 0, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
  (13, 1, 10, 1, '광장시장 빈대떡', '강퇴된 멤버가 쓴 글', FALSE, TRUE, 'NORMAL', NULL, NULL, 0, 0, 0, '2026-10-02 09:00:00', '2026-10-02 09:00:00');

-- images 7행
INSERT INTO images (id, uploader_id, post_id, sort_order, stored_name, original_name, mime_type, size_bytes, created_at) VALUES
  (1, 2, 1, 0, '00000000-0000-0000-0000-000000000001', '국밥.jpg', 'image/jpeg', 30928, '2026-10-02 09:00:00'),
  (2, 2, 1, 1, '00000000-0000-0000-0000-000000000002', '깍두기.jpg', 'image/jpeg', 31010, '2026-10-02 09:00:00'),
  (3, 1, 2, 0, '00000000-0000-0000-0000-000000000003', '카페1.webp', 'image/webp', 11552, '2026-10-02 09:00:00'),
  (4, 3, 5, 0, '00000000-0000-0000-0000-000000000004', '성산.png', 'image/png', 6343, '2026-10-02 09:00:00'),
  (5, 9, 10, 0, '00000000-0000-0000-0000-000000000005', '보리.gif', 'image/gif', 68780, '2026-10-02 09:00:00'),
  (6, 1, NULL, 0, '00000000-0000-0000-0000-000000000006', 'profile.png', 'image/png', 6408, '2026-10-02 09:00:00'),
  (7, 2, NULL, 0, '00000000-0000-0000-0000-000000000007', 'blog_cover.jpg', 'image/jpeg', 30809, '2026-10-02 09:00:00');

-- post_tags 13행
INSERT INTO post_tags (post_id, tag_id) VALUES
  (1, 1),
  (1, 2),
  (2, 10),
  (2, 2),
  (5, 3),
  (5, 4),
  (6, 3),
  (7, 5),
  (7, 6),
  (8, 5),
  (9, 7),
  (10, 8),
  (11, 8);

-- blog_tags 6행
INSERT INTO blog_tags (blog_id, tag_id) VALUES
  (1, 1),
  (1, 2),
  (2, 3),
  (3, 5),
  (3, 6),
  (5, 8);

-- comments 8행
INSERT INTO comments (id, post_id, author_id, parent_id, reply_to_member_id, content, is_edited, status, created_at, updated_at) VALUES
  (1, 1, 1, NULL, NULL, '저도 가 봤어요! 국밥 진짜 맛있죠', FALSE, 'NORMAL', '2026-10-06 11:00:00', '2026-10-06 11:00:00'),
  (2, 1, 2, 1, 1, '감사합니다 :)', FALSE, 'NORMAL', '2026-10-06 12:00:00', '2026-10-06 12:00:00'),
  (3, 1, 6, 1, 1, '위치가 어디인가요?', TRUE, 'NORMAL', '2026-10-06 13:00:00', '2026-10-06 13:00:00'),
  (4, 5, 1, NULL, NULL, '우도 땅콩아이스크림 먹어야죠', FALSE, 'NORMAL', '2026-10-06 14:00:00', '2026-10-06 14:00:00'),
  (5, 7, 10, NULL, NULL, '정리 감사합니다', FALSE, 'DELETED', '2026-10-06 15:00:00', '2026-10-06 15:00:00'),
  (6, 7, 4, 5, 10, '도움이 됐다니 다행이에요', FALSE, 'NORMAL', '2026-10-06 16:00:00', '2026-10-06 16:00:00'),
  (7, 10, 2, NULL, NULL, '보리 너무 귀여워요', FALSE, 'NORMAL', '2026-10-06 17:00:00', '2026-10-06 17:00:00'),
  (8, 10, 8, NULL, NULL, '광고성 댓글', FALSE, 'HIDDEN', '2026-10-06 18:00:00', '2026-10-06 18:00:00');

-- post_likes 10행
INSERT INTO post_likes (member_id, post_id, created_at) VALUES
  (1, 1, '2026-10-06 20:00:00'),
  (5, 1, '2026-10-06 20:00:00'),
  (6, 1, '2026-10-06 20:00:00'),
  (1, 5, '2026-10-06 20:00:00'),
  (6, 5, '2026-10-06 20:00:00'),
  (9, 5, '2026-10-06 20:00:00'),
  (2, 10, '2026-10-06 20:00:00'),
  (7, 10, '2026-10-06 20:00:00'),
  (1, 7, '2026-10-06 20:00:00'),
  (10, 7, '2026-10-06 20:00:00');

-- post_views 6행
INSERT INTO post_views (post_id, viewer_key, view_date) VALUES
  (1, 'm:1', '2026-10-06'),
  (1, 'm:5', '2026-10-06'),
  (1, 'a:3f9c1b', '2026-10-06'),
  (1, 'm:1', '2026-10-07'),
  (5, 'm:1', '2026-10-06'),
  (10, 'a:7d2e44', '2026-10-07');

-- recent_searches 3행
INSERT INTO recent_searches (id, member_id, keyword, searched_at) VALUES
  (1, 1, '맛집', '2026-10-07 08:00:00'),
  (2, 1, '제주 여행', '2026-10-07 08:05:00'),
  (3, 4, '스프링', '2026-10-06 22:00:00');

-- follows 8행
INSERT INTO follows (follower_id, followee_id, created_at) VALUES
  (1, 2, '2026-10-01 10:00:00'),
  (1, 3, '2026-10-01 10:00:00'),
  (1, 4, '2026-10-01 10:00:00'),
  (2, 1, '2026-10-01 10:00:00'),
  (5, 2, '2026-10-01 10:00:00'),
  (6, 3, '2026-10-01 10:00:00'),
  (7, 9, '2026-10-01 10:00:00'),
  (9, 7, '2026-10-01 10:00:00');

-- subscriptions 6행
INSERT INTO subscriptions (member_id, blog_id, created_at) VALUES
  (1, 5, '2026-10-01 10:00:00'),
  (3, 1, '2026-10-01 10:00:00'),
  (4, 1, '2026-10-01 10:00:00'),
  (8, 2, '2026-10-01 10:00:00'),
  (10, 5, '2026-10-01 10:00:00'),
  (6, 4, '2026-10-01 10:00:00');

-- blocks 2행
INSERT INTO blocks (blocker_id, blocked_id, created_at) VALUES
  (3, 8, '2026-10-03 10:00:00'),
  (1, 10, '2026-10-03 10:00:00');

-- reports 5행
INSERT INTO reports (id, reporter_id, target_type, target_id, blog_id, handler, reason_code, detail, status, result, processed_by_member_id, processed_by_admin_id, processed_at, created_at) VALUES
  (1, 1, 'POST', 13, 1, 'BLOG_OWNER', 'SPAM', '광고 글입니다', 'DONE', 'KICK', 2, NULL, '2026-10-02 10:30:00', '2026-10-02 09:00:00'),
  (2, 5, 'COMMENT', 8, 5, 'BLOG_OWNER', 'ABUSE', '광고성 댓글', 'PENDING', NULL, NULL, NULL, NULL, '2026-10-06 21:00:00'),
  (3, 8, 'BLOG_OWNER', 3, 2, 'ADMIN', 'ABUSE', '블로그장이 부당하게 신청을 거절', 'DONE', 'OWNER_WARNING', NULL, 1, '2026-10-05 10:00:00', '2026-10-04 09:00:00'),
  (4, 6, 'MEMBER', 10, NULL, 'ADMIN', 'ETC', '메인 프로필 신고', 'PENDING', NULL, NULL, NULL, NULL, '2026-10-06 09:00:00'),
  (5, 1, 'MEMBER', 8, 1, 'BLOG_OWNER', 'ABUSE', '욕설', 'DONE', 'SUSPEND', 5, NULL, '2026-10-03 11:00:00', '2026-10-03 09:00:00');

-- member_sanctions 6행
INSERT INTO member_sanctions (id, blog_id, member_id, actor_id, report_id, type, duration, reason, created_at) VALUES
  (1, 1, 10, 2, NULL, 'SUSPEND', 'D3', '광고 글 반복', '2026-09-20 10:00:00'),
  (2, 1, 10, 2, NULL, 'SUSPEND', 'D14', '광고 글 반복', '2026-09-25 10:00:00'),
  (3, 1, 10, 5, NULL, 'SUSPEND', 'D3', '광고 글 반복', '2026-09-30 10:00:00'),
  (4, 1, 10, 2, 1, 'KICK', NULL, '정지 3회 뒤 강제 퇴장', '2026-10-02 10:30:00'),
  (5, 1, 8, 5, 5, 'SUSPEND', 'D14', '욕설', '2026-10-03 11:00:00'),
  (6, 2, 9, 3, NULL, 'WARNING', NULL, '주제와 맞지 않는 글', '2026-10-04 11:00:00');

-- owner_warnings 1행
INSERT INTO owner_warnings (id, blog_id, member_id, admin_id, report_id, reason, created_at) VALUES
  (1, 2, 3, 1, 3, '부당한 참여 거절', '2026-10-05 10:00:00');

-- join_requests 4행
INSERT INTO join_requests (id, blog_id, member_id, status, requested_at, processed_at, processed_by) VALUES
  (1, 2, 8, 'REJECTED', '2026-10-01 09:00:00', '2026-10-01 12:00:00', 3),
  (2, 2, 5, 'PENDING', '2026-10-06 09:00:00', NULL, NULL),
  (3, 3, 10, 'APPROVED', '2026-09-20 09:00:00', '2026-09-20 10:00:00', 4),
  (4, 2, 9, 'APPROVED', '2026-09-21 09:00:00', '2026-09-21 11:00:00', 6);

-- ownership_transfers 2행
INSERT INTO ownership_transfers (id, blog_id, from_member_id, to_member_id, status, requested_at, responded_at) VALUES
  (1, 1, 2, 5, 'PENDING', '2026-10-06 10:00:00', NULL),
  (2, 4, 7, 3, 'CANCELLED', '2026-10-04 10:00:00', '2026-10-05 15:00:00');

-- blacklist_entries 1행
INSERT INTO blacklist_entries (id, blog_id, name_hash, email_hash, phone_hash, created_at) VALUES
  (1, 1, 'a3c3385aef3dcfa23cb4bc80b677a8fe5fa13932521f5be2f38ad37df5dc1c9f', '3a5f641d8e03e7ffaacfdfebf92c34b036232222aa490a1a5f0fb0f5ee578509', '52c88cd6e29bdd1d4f4090934d72a2263e04424771270713a808b7ce88678b29', '2026-10-02 10:30:00');

-- blacklist_appeals 1행
INSERT INTO blacklist_appeals (id, blog_id, entry_id, member_id, message, name_matched, phone_matched, status, processed_by, processed_at, created_at) VALUES
  (1, 1, 1, 10, '번호를 새로 받았는데 막혀 있어요', TRUE, TRUE, 'REJECTED', 2, '2026-10-04 10:00:00', '2026-10-03 10:00:00');

-- blog_closing_notices 1행
INSERT INTO blog_closing_notices (id, blog_id, close_scheduled_at, stage, sent_at) VALUES
  (1, 4, '2026-10-13 04:00:00', 'IMMEDIATE', '2026-10-06 15:00:00');

-- notifications 14행
INSERT INTO notifications (id, recipient_id, type, tab, blog_id, actor_id, target_type, target_id, is_read, created_at) VALUES
  (1, 2, 'COMMENT', 'COMMENT', 1, 1, 'POST', 1, FALSE, '2026-10-06 21:00:00'),
  (2, 1, 'REPLY', 'COMMENT', 1, 2, 'COMMENT', 1, TRUE, '2026-10-06 22:00:00'),
  (3, 2, 'LIKE', 'LIKE', 1, 1, 'POST', 1, FALSE, '2026-10-06 23:00:00'),
  (4, 2, 'NEW_FOLLOWER', 'FOLLOW', NULL, 1, 'MEMBER', 1, TRUE, '2026-10-06 20:00:00'),
  (5, 9, 'BLOG_SUBSCRIBED', 'FOLLOW', 5, 1, 'BLOG', 5, FALSE, '2026-10-06 21:00:00'),
  (6, 3, 'JOIN_REQUESTED', 'BLOG', 2, 5, 'BLOG', 2, FALSE, '2026-10-06 22:00:00'),
  (7, 5, 'OWNERSHIP_TRANSFER_REQUESTED', 'BLOG', 1, 2, 'BLOG', 1, FALSE, '2026-10-06 23:00:00'),
  (8, 3, 'BLOG_CLOSING', 'BLOG', 4, NULL, 'BLOG', 4, FALSE, '2026-10-06 20:00:00'),
  (9, 5, 'BLOG_CLOSING', 'BLOG', 4, NULL, 'BLOG', 4, FALSE, '2026-10-06 21:00:00'),
  (10, 6, 'BLOG_CLOSING', 'BLOG', 4, NULL, 'BLOG', 4, FALSE, '2026-10-06 22:00:00'),
  (11, 10, 'SANCTION_KICKED', 'ADMIN', 1, NULL, 'BLOG', 1, TRUE, '2026-10-06 23:00:00'),
  (12, 1, 'REPORT_RESULT', 'ADMIN', 1, NULL, 'POST', 13, TRUE, '2026-10-06 20:00:00'),
  (13, 3, 'OWNER_WARNED', 'ADMIN', 2, NULL, 'BLOG', 2, FALSE, '2026-10-06 21:00:00'),
  (14, 8, 'JOIN_REJECTED', 'BLOG', 2, NULL, 'BLOG', 2, TRUE, '2026-10-06 22:00:00');

-- notification_settings 3행
INSERT INTO notification_settings (member_id, type, enabled) VALUES
  (1, 'LIKE', FALSE),
  (1, 'NOTICE', FALSE),
  (9, 'NEW_FOLLOWER', FALSE);

-- admin_action_logs 3행
INSERT INTO admin_action_logs (id, admin_id, action, target_type, target_id, reason, created_at) VALUES
  (1, 1, 'WARN', 'BLOG', 2, '신고 3번 처리: 블로그장 경고', '2026-10-05 10:00:00'),
  (2, 1, 'VIEW_PII', 'MEMBER', 3, '신고 확인용 개인정보 열람', '2026-10-05 09:50:00'),
  (3, 2, 'HIDE', 'COMMENT', 8, '광고성 댓글', '2026-10-06 22:00:00');

-- notices 2행
INSERT INTO notices (id, admin_id, title, content_md, status, created_at, updated_at) VALUES
  (1, 1, '메인블로그 오픈 안내', '메인블로그가 문을 열었습니다.', 'NORMAL', '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
  (2, 2, '개인정보 처리방침 변경', '10월 15일부터 적용됩니다.', 'NORMAL', '2026-10-06 09:00:00', '2026-10-06 09:00:00');

SET FOREIGN_KEY_CHECKS = 1;
