-- 메인블로그 ERD (docs/04-design/ERD.md와 같은 내용의 MySQL 8 DDL)
-- 근거: specs/001~005, D-84~D-88. 34개 테이블.
-- 문자 길이는 문서에 정한 값이 있으면 그 값, 없으면 넉넉하게 잡았다.
-- enum 값은 VARCHAR + CHECK로 적었다.

-- =========================================================
-- 1. 회원·인증 (001)
-- =========================================================

CREATE TABLE members (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '회원 번호',
  email VARCHAR(255) NULL COMMENT '로그인 아이디. 소문자로 저장, 탈퇴하면 NULL로 비움',
  password_hash VARCHAR(100) NULL COMMENT 'bcrypt 해시 (SEC-01). 탈퇴 30일 뒤 비움',
  name VARCHAR(50) NULL COMMENT '이름. 수정 불가. 탈퇴 30일 뒤 비움',
  nickname VARCHAR(12) NOT NULL COMMENT '2~12자, 한글·영문·숫자, 예약어 불가',
  phone VARCHAR(20) NULL COMMENT '숫자만, 1차 중복 허용 (D-24). 탈퇴 30일 뒤 비움',
  profile_image_id BIGINT NULL COMMENT '프로필 사진',
  bio VARCHAR(500) NULL COMMENT '소개, 선택. 화면은 150자까지',
  show_posts BOOLEAN NOT NULL DEFAULT TRUE COMMENT '내가 쓴 글 다른 사람에게 보여주기 (D-91, V4)',
  show_comments BOOLEAN NOT NULL DEFAULT TRUE COMMENT '쓴 댓글 다른 사람에게 보여주기 (D-94, V6)',
  show_likes BOOLEAN NOT NULL DEFAULT TRUE COMMENT '좋아요 한 글 보여주기 (D-91, V4)',
  show_following BOOLEAN NOT NULL DEFAULT TRUE COMMENT '팔로잉 보여주기 (D-91, V4)',
  show_subscriptions BOOLEAN NOT NULL DEFAULT TRUE COMMENT '구독한 블로그 보여주기 (D-91, V4)',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '상태',
  notification_retention_days TINYINT NOT NULL DEFAULT 30 COMMENT '알림 보관 일수 (30 또는 7)',
  terms_agreed_at DATETIME NOT NULL COMMENT '이용약관 동의 시각',
  privacy_agreed_at DATETIME NOT NULL COMMENT '개인정보 수집·이용 동의 시각',
  age14_confirmed_at DATETIME NOT NULL COMMENT '만 14세 이상 확인 시각',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입일',
  withdrawn_at DATETIME NULL COMMENT '탈퇴 시각. 30일 뒤 개인정보 삭제',
  credentials_changed_at DATETIME NULL COMMENT '비밀번호 변경·탈퇴 시각. 이전에 발급된 Access Token 무효 (T031, V2)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_members_email (email),
  UNIQUE KEY uk_members_nickname (nickname),
  KEY idx_members_name_phone (name, phone),
  CONSTRAINT ck_members_status CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
  CONSTRAINT ck_members_retention CHECK (notification_retention_days IN (7, 30)),
  CONSTRAINT fk_members_profile_image FOREIGN KEY (profile_image_id) REFERENCES images (id)
) COMMENT '회원 (USR-01·05·07)';

CREATE TABLE email_verifications (
  id BIGINT NOT NULL AUTO_INCREMENT,
  email VARCHAR(255) NOT NULL COMMENT '소문자. 회원과 연결하지 않음 (D-28)',
  purpose VARCHAR(20) NOT NULL COMMENT '용도',
  code_hash CHAR(64) NOT NULL COMMENT '6자리 번호의 해시',
  fail_count TINYINT NOT NULL DEFAULT 0 COMMENT '5번이면 무효',
  sent_at DATETIME NOT NULL COMMENT '다시 보내기 1분 제한',
  expires_at DATETIME NOT NULL COMMENT '가입 10분, 재설정 30분',
  verified_at DATETIME NULL COMMENT '가입: 인증 완료 표시',
  PRIMARY KEY (id),
  KEY idx_email_verifications_email (email, purpose),
  CONSTRAINT ck_email_verifications_purpose CHECK (purpose IN ('SIGNUP', 'PASSWORD_RESET'))
) COMMENT '이메일 인증번호. 쓰거나 만료되면 바로 삭제 (USR-02·06, SEC-05)';

CREATE TABLE email_find_tokens (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL COMMENT '대상 회원',
  token_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL COMMENT '10분 (D-26)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_email_find_tokens_token (token_hash),
  CONSTRAINT fk_email_find_tokens_member FOREIGN KEY (member_id) REFERENCES members (id)
) COMMENT '이메일 찾기 뒤 비밀번호 재설정용 임시 토큰 (USR-08)';

CREATE TABLE refresh_tokens (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NULL COMMENT '회원 로그인이면',
  admin_id BIGINT NULL COMMENT '관리자 로그인이면',
  token_hash CHAR(64) NOT NULL,
  remember_me BOOLEAN NOT NULL DEFAULT FALSE COMMENT '로그인 유지',
  last_activity_at DATETIME NOT NULL COMMENT '30분 무활동 판단 (D-62)',
  expires_at DATETIME NOT NULL COMMENT '14일 또는 세션',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_refresh_tokens_token (token_hash),
  CONSTRAINT ck_refresh_tokens_owner CHECK ((member_id IS NULL) <> (admin_id IS NULL)),
  CONSTRAINT fk_refresh_tokens_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT fk_refresh_tokens_admin FOREIGN KEY (admin_id) REFERENCES admins (id)
) COMMENT 'Refresh Token. 로그아웃·비밀번호 변경 때 폐기 (SEC-04)';

CREATE TABLE social_accounts (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  member_id BIGINT NOT NULL COMMENT '회원 번호',
  provider VARCHAR(20) NOT NULL COMMENT '로그인 회사 (google)',
  provider_user_id VARCHAR(255) NOT NULL COMMENT '그 회사가 준 사용자 번호 (구글 sub)',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '연결한 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_social_accounts_provider_user (provider, provider_user_id),
  UNIQUE KEY uk_social_accounts_member_provider (member_id, provider),
  CONSTRAINT fk_social_accounts_member FOREIGN KEY (member_id) REFERENCES members (id)
) COMMENT '회원의 바깥 로그인 계정 (D-103)';

CREATE TABLE login_failures (
  email VARCHAR(255) NOT NULL COMMENT '소문자. 가입 안 된 이메일도 같게 셈',
  fail_count TINYINT NOT NULL DEFAULT 0 COMMENT '3번부터 Turnstile, 5번이면 잠금',
  locked_until DATETIME NULL COMMENT '5분 잠금',
  last_failed_at DATETIME NOT NULL,
  PRIMARY KEY (email)
) COMMENT '로그인 실패와 계정별 잠금 (SEC-03, SEC-13, D-80)';

-- =========================================================
-- 2. 블로그·멤버 (002)
-- =========================================================

CREATE TABLE blogs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  slug VARCHAR(30) NOT NULL COMMENT '주소. 영문 소문자·숫자·- 3~30자, 예약어 불가',
  name VARCHAR(100) NOT NULL COMMENT '이름. 중복 허용',
  description VARCHAR(500) NULL COMMENT '소개',
  cover_image_id BIGINT NULL COMMENT '대표 이미지',
  owner_id BIGINT NOT NULL COMMENT '현재 블로그장',
  visibility VARCHAR(20) NOT NULL COMMENT '공개 범위',
  join_policy VARCHAR(20) NOT NULL COMMENT '참여 방식',
  share_token CHAR(32) NULL COMMENT '일부 공개 링크 값. 새로 만들면 교체',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '운영 상태',
  is_hidden BOOLEAN NOT NULL DEFAULT FALSE COMMENT '관리자 숨김 (ADM-02)',
  close_scheduled_at DATETIME NULL COMMENT '누른 날짜 + 7일 04:00. 철회하면 NULL',
  close_requested_by VARCHAR(20) NULL COMMENT '폐쇄를 건 쪽 (D-86)',
  closed_at DATETIME NULL COMMENT '30일 뒤 완전 삭제',
  notification_retention_days TINYINT NOT NULL DEFAULT 30 COMMENT '블로그 알림 보관',
  theme VARCHAR(20) NOT NULL DEFAULT 'ODYSSEY' COMMENT '테마 틀 7가지 (D-89)',
  theme_accent CHAR(7) NOT NULL DEFAULT '#7C0CB4' COMMENT '강조 색 #RRGGBB',
  theme_font VARCHAR(10) NOT NULL DEFAULT 'SANS' COMMENT '글꼴',
  hero_title VARCHAR(40) NULL COMMENT '머리 사진 위 큰 문구',
  hero_subtitle VARCHAR(100) NULL COMMENT '머리 사진 위 작은 문구',
  purged_at DATETIME NULL COMMENT '폐쇄 30일 뒤 글·사진을 지우고 주소를 풀어 준 시각 (V7)',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_blogs_slug (slug),
  UNIQUE KEY uk_blogs_share_token (share_token),
  KEY idx_blogs_status_close (status, close_scheduled_at),
  FULLTEXT KEY ft_blogs_name_description (name, description) WITH PARSER ngram,
  CONSTRAINT ck_blogs_visibility CHECK (visibility IN ('PUBLIC', 'LINK_ONLY', 'PRIVATE')),
  CONSTRAINT ck_blogs_join_policy CHECK (join_policy IN ('FREE', 'APPROVAL')),
  CONSTRAINT ck_blogs_status CHECK (status IN ('ACTIVE', 'CLOSING', 'CLOSED')),
  CONSTRAINT ck_blogs_close_requested_by CHECK (close_requested_by IN ('OWNER', 'ADMIN', 'OWNER_KICKED')),
  CONSTRAINT ck_blogs_theme CHECK (theme IN ('ODYSSEY', 'POSTER', 'WHATEVER', 'LETTER', 'BOOK_CLUB', 'MAGAZINE', 'PORTFOLIO')),
  CONSTRAINT ck_blogs_theme_font CHECK (theme_font IN ('SANS', 'SERIF', 'MONO')),
  CONSTRAINT ck_blogs_theme_accent CHECK (theme_accent REGEXP '^#[0-9A-Fa-f]{6}$'),
  CONSTRAINT fk_blogs_cover_image FOREIGN KEY (cover_image_id) REFERENCES images (id),
  CONSTRAINT fk_blogs_owner FOREIGN KEY (owner_id) REFERENCES members (id)
) COMMENT '블로그 (BLG-01·09, D-49, D-86)';

CREATE TABLE blog_memberships (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' COMMENT '블로그별 역할',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '멤버 상태',
  perm_edit_info BOOLEAN NOT NULL DEFAULT FALSE COMMENT '부블로그장: 블로그 정보 수정',
  perm_manage_members BOOLEAN NOT NULL DEFAULT FALSE COMMENT '부블로그장: 멤버 관리',
  perm_manage_posts BOOLEAN NOT NULL DEFAULT FALSE COMMENT '부블로그장: 글 관리',
  sub_owner_since DATETIME NULL COMMENT '부블로그장이 된 시각. 자동 위임 순서 (D-17)',
  suspended_until DATETIME NULL COMMENT '정지 끝. 영구면 9999-12-31',
  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  left_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_blog_memberships_blog_member (blog_id, member_id),
  CONSTRAINT ck_blog_memberships_role CHECK (role IN ('OWNER', 'SUB_OWNER', 'MEMBER')),
  CONSTRAINT ck_blog_memberships_status CHECK (status IN ('ACTIVE', 'LEFT', 'KICKED')),
  CONSTRAINT fk_blog_memberships_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_blog_memberships_member FOREIGN KEY (member_id) REFERENCES members (id)
) COMMENT '블로그 멤버십: 역할, 부블로그장 권한, 정지 (BLG-13, SEC-07, D-71)';

CREATE TABLE join_requests (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL COMMENT '신청자',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at DATETIME NULL COMMENT '거절 뒤 7일 계산',
  processed_by BIGINT NULL COMMENT '처리한 블로그장·부블로그장',
  PRIMARY KEY (id),
  KEY idx_join_requests_blog_member (blog_id, member_id, status),
  CONSTRAINT ck_join_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
  CONSTRAINT fk_join_requests_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_join_requests_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT fk_join_requests_processed_by FOREIGN KEY (processed_by) REFERENCES members (id)
) COMMENT '참여 신청 (BLG-04·05)';

CREATE TABLE ownership_transfers (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  from_member_id BIGINT NOT NULL COMMENT '요청한 블로그장',
  to_member_id BIGINT NOT NULL COMMENT '받는 멤버',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '7일 무응답이면 EXPIRED',
  responded_at DATETIME NULL,
  PRIMARY KEY (id),
  CONSTRAINT ck_ownership_transfers_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
  CONSTRAINT fk_ownership_transfers_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_ownership_transfers_from FOREIGN KEY (from_member_id) REFERENCES members (id),
  CONSTRAINT fk_ownership_transfers_to FOREIGN KEY (to_member_id) REFERENCES members (id)
) COMMENT '블로그장 위임 요청 (BLG-08, D-16, D-31)';

CREATE TABLE member_sanctions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL COMMENT '대상 멤버',
  actor_id BIGINT NOT NULL COMMENT '처리한 블로그장·부블로그장',
  report_id BIGINT NULL COMMENT '신고에서 시작했으면',
  type VARCHAR(20) NOT NULL COMMENT '조치 종류',
  duration VARCHAR(10) NULL COMMENT '정지 기간',
  reason_code VARCHAR(30) NULL COMMENT '제재 사유 항목. 정지 풀기는 NULL (D-95, V8)',
  reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '최근 1년 정지 3번이면 표시 (D-87). 1년 보관 (D-60)',
  PRIMARY KEY (id),
  KEY idx_member_sanctions_count (blog_id, member_id, type, created_at),
  CONSTRAINT ck_member_sanctions_type CHECK (type IN ('WARNING', 'SUSPEND', 'LIFT', 'KICK')),
  CONSTRAINT ck_member_sanctions_duration CHECK (duration IN ('D3', 'D14', 'D30', 'FOREVER')),
  CONSTRAINT ck_member_sanctions_reason_code CHECK (reason_code IN ('SPAM', 'ABUSE', 'OBSCENE', 'PRIVACY', 'COPYRIGHT', 'FLOOD', 'IMPERSONATION', 'ETC')),
  CONSTRAINT fk_member_sanctions_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_member_sanctions_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT fk_member_sanctions_actor FOREIGN KEY (actor_id) REFERENCES members (id),
  CONSTRAINT fk_member_sanctions_report FOREIGN KEY (report_id) REFERENCES reports (id)
) COMMENT '블로그 안 멤버 경고·정지·해제·강제 퇴장 기록 (BLG-13, D-57, D-87)';

CREATE TABLE blacklist_entries (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  member_id BIGINT NULL COMMENT '강제 퇴장된 회원. 화면에 닉네임만 (V7)',
  name_hash CHAR(64) NOT NULL COMMENT 'HMAC-SHA256',
  email_hash CHAR(64) NOT NULL COMMENT 'HMAC-SHA256',
  phone_hash CHAR(64) NOT NULL COMMENT 'HMAC-SHA256',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정·삭제 없음 (D-55)',
  lifted_at DATETIME NULL COMMENT '해제 문의를 받아들인 시각 (BLG-12, V7)',
  PRIMARY KEY (id),
  KEY idx_blacklist_entries_email (blog_id, email_hash),
  KEY idx_blacklist_entries_phone (blog_id, phone_hash),
  CONSTRAINT fk_blacklist_entries_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_blacklist_entries_member FOREIGN KEY (member_id) REFERENCES members (id)
) COMMENT '블로그 블랙리스트 (BLG-11, D-34)';

CREATE TABLE blacklist_appeals (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  entry_id BIGINT NOT NULL COMMENT '걸린 블랙리스트 항목',
  member_id BIGINT NOT NULL COMMENT '문의한 회원',
  message VARCHAR(1000) NULL,
  name_matched BOOLEAN NOT NULL COMMENT '서버가 비교한 결과',
  phone_matched BOOLEAN NOT NULL COMMENT '서버가 비교한 결과',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  processed_by BIGINT NULL COMMENT '처리한 블로그장',
  processed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT ck_blacklist_appeals_status CHECK (status IN ('PENDING', 'LIFTED', 'REJECTED')),
  CONSTRAINT fk_blacklist_appeals_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_blacklist_appeals_entry FOREIGN KEY (entry_id) REFERENCES blacklist_entries (id),
  CONSTRAINT fk_blacklist_appeals_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT fk_blacklist_appeals_processed_by FOREIGN KEY (processed_by) REFERENCES members (id)
) COMMENT '블랙리스트 해제 문의 (BLG-12)';

CREATE TABLE blog_closing_notices (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  close_scheduled_at DATETIME NOT NULL,
  stage VARCHAR(20) NOT NULL COMMENT '알림 단계',
  sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '같은 알림 두 번 방지',
  PRIMARY KEY (id),
  UNIQUE KEY uk_blog_closing_notices (blog_id, close_scheduled_at, stage),
  CONSTRAINT ck_blog_closing_notices_stage CHECK (stage IN ('IMMEDIATE', 'D3', 'D1', 'CANCELLED')),
  CONSTRAINT fk_blog_closing_notices_blog FOREIGN KEY (blog_id) REFERENCES blogs (id)
) COMMENT '폐쇄 알림 발송 기록 (BLG-09)';

CREATE TABLE blog_tags (
  blog_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  PRIMARY KEY (blog_id, tag_id),
  CONSTRAINT fk_blog_tags_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_blog_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id)
) COMMENT '블로그에 단 태그. 확인 필요 (BLG-03)';

-- =========================================================
-- 3. 게시판 (004)
-- =========================================================

CREATE TABLE categories (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  name VARCHAR(50) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_categories_blog_name (blog_id, name),
  CONSTRAINT fk_categories_blog FOREIGN KEY (blog_id) REFERENCES blogs (id)
) COMMENT '블로그별 카테고리 (BRD-03)';

CREATE TABLE posts (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  author_id BIGINT NOT NULL COMMENT '작성자',
  category_id BIGINT NULL COMMENT '없을 수 있음',
  title VARCHAR(30) NOT NULL COMMENT '1~30자',
  content_md TEXT NOT NULL COMMENT '마크다운 원문, 최대 1000자',
  is_notice BOOLEAN NOT NULL DEFAULT FALSE COMMENT '블로그 공지',
  author_left BOOLEAN NOT NULL DEFAULT FALSE COMMENT '블로그 탈퇴·강퇴로 "탈퇴한 계정" 표시 (D-33)',
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  deleted_by VARCHAR(20) NULL COMMENT '삭제한 쪽',
  deleted_at DATETIME NULL COMMENT '30일 뒤 완전 삭제',
  view_count INT NOT NULL DEFAULT 0,
  like_count INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_posts_blog_created (blog_id, status, created_at),
  KEY idx_posts_status_created (status, created_at),
  FULLTEXT KEY ft_posts_title (title) WITH PARSER ngram,
  CONSTRAINT ck_posts_status CHECK (status IN ('NORMAL', 'HIDDEN', 'DELETED')),
  CONSTRAINT ck_posts_deleted_by CHECK (deleted_by IN ('AUTHOR', 'OWNER', 'ADMIN')),
  CONSTRAINT fk_posts_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_posts_author FOREIGN KEY (author_id) REFERENCES members (id),
  CONSTRAINT fk_posts_category FOREIGN KEY (category_id) REFERENCES categories (id)
) COMMENT '글 (BRD-01, SEC-06, D-66)';

CREATE TABLE tags (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(20) NOT NULL COMMENT '소문자 정규화, 1~20자, 한글·영문·숫자·_',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tags_name (name)
) COMMENT '태그 (BRD-04, 6.4)';

CREATE TABLE post_tags (
  post_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  PRIMARY KEY (post_id, tag_id),
  CONSTRAINT fk_post_tags_post FOREIGN KEY (post_id) REFERENCES posts (id),
  CONSTRAINT fk_post_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id)
) COMMENT '글 × 태그 (uk_post_tags, 글 하나에 최대 10개)';

CREATE TABLE images (
  id BIGINT NOT NULL AUTO_INCREMENT,
  uploader_id BIGINT NULL COMMENT '올린 회원. 관리자가 올린 공지 사진은 NULL',
  admin_uploader_id BIGINT NULL COMMENT '공지 사진을 올린 관리자 (D-102)',
  post_id BIGINT NULL COMMENT '글 이미지일 때. 저장 전에는 NULL',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '0번이 미리보기 이미지',
  stored_name CHAR(36) NOT NULL COMMENT 'UUID 파일 이름',
  original_name VARCHAR(255) NOT NULL COMMENT '원래 이름은 DB에만',
  mime_type VARCHAR(30) NOT NULL,
  size_bytes INT NOT NULL COMMENT '3MB 이하',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_images_stored_name (stored_name),
  CONSTRAINT ck_images_mime CHECK (mime_type IN ('image/jpeg', 'image/png', 'image/gif', 'image/webp')),
  CONSTRAINT ck_images_size CHECK (size_bytes <= 3145728),
  CONSTRAINT fk_images_uploader FOREIGN KEY (uploader_id) REFERENCES members (id),
  CONSTRAINT fk_images_admin_uploader FOREIGN KEY (admin_uploader_id) REFERENCES admins (id),
  CONSTRAINT fk_images_post FOREIGN KEY (post_id) REFERENCES posts (id)
) COMMENT '이미지: 글·프로필·블로그 대표·공지 (BRD-05, SEC-08, D-75, D-102)';

CREATE TABLE comments (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  author_id BIGINT NOT NULL COMMENT '작성자',
  parent_id BIGINT NULL COMMENT '대댓글이면 부모 댓글. 1단계',
  reply_to_member_id BIGINT NULL COMMENT '@닉네임 대상',
  content VARCHAR(500) NOT NULL COMMENT '1~500자',
  is_edited BOOLEAN NOT NULL DEFAULT FALSE COMMENT '수정됨 표시',
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_comments_post (post_id, status, created_at),
  KEY idx_comments_author (author_id, created_at) COMMENT '쓴 댓글 목록 (D-94, V6)',
  CONSTRAINT ck_comments_status CHECK (status IN ('NORMAL', 'HIDDEN', 'DELETED')),
  CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES posts (id),
  CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES members (id),
  CONSTRAINT fk_comments_parent FOREIGN KEY (parent_id) REFERENCES comments (id),
  CONSTRAINT fk_comments_reply_to FOREIGN KEY (reply_to_member_id) REFERENCES members (id)
) COMMENT '댓글·대댓글 (BRD-06, D-78)';

CREATE TABLE post_likes (
  member_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id, post_id),
  KEY idx_post_likes_member (member_id, created_at) COMMENT '좋아요 한 글 목록 (V4)',
  CONSTRAINT fk_post_likes_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT fk_post_likes_post FOREIGN KEY (post_id) REFERENCES posts (id)
) COMMENT '좋아요 (BRD-06)';

CREATE TABLE post_views (
  post_id BIGINT NOT NULL,
  viewer_key VARCHAR(100) NOT NULL COMMENT '회원 번호 또는 비회원 식별값',
  view_date DATE NOT NULL,
  PRIMARY KEY (post_id, viewer_key, view_date),
  CONSTRAINT fk_post_views_post FOREIGN KEY (post_id) REFERENCES posts (id)
) COMMENT '조회 기록. 같은 사람·같은 날·같은 글은 한 번 (BRD-11, D-77)';

CREATE TABLE recent_searches (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  keyword VARCHAR(20) NOT NULL COMMENT '2~20자',
  searched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '회원당 최근 10개',
  PRIMARY KEY (id),
  UNIQUE KEY uk_recent_searches_member_keyword (member_id, keyword),
  CONSTRAINT fk_recent_searches_member FOREIGN KEY (member_id) REFERENCES members (id)
) COMMENT '최근 검색어 (BRD-08, 6.1)';

-- =========================================================
-- 4. 소셜·알림·신고 (003)
-- =========================================================

CREATE TABLE follows (
  follower_id BIGINT NOT NULL COMMENT '팔로우하는 회원',
  followee_id BIGINT NOT NULL COMMENT '팔로우받는 회원',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (follower_id, followee_id),
  KEY idx_follows_followee (followee_id, created_at) COMMENT '팔로워 목록 (V4)',
  CONSTRAINT fk_follows_follower FOREIGN KEY (follower_id) REFERENCES members (id),
  CONSTRAINT fk_follows_followee FOREIGN KEY (followee_id) REFERENCES members (id)
) COMMENT '팔로우 (SOC-01, D-02)';

CREATE TABLE subscriptions (
  member_id BIGINT NOT NULL,
  blog_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id, blog_id),
  CONSTRAINT fk_subscriptions_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT fk_subscriptions_blog FOREIGN KEY (blog_id) REFERENCES blogs (id)
) COMMENT '블로그 구독 (SOC-01)';

CREATE TABLE blocks (
  blocker_id BIGINT NOT NULL COMMENT '차단한 회원',
  blocked_id BIGINT NOT NULL COMMENT '차단당한 회원',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (blocker_id, blocked_id),
  CONSTRAINT fk_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES members (id),
  CONSTRAINT fk_blocks_blocked FOREIGN KEY (blocked_id) REFERENCES members (id)
) COMMENT '차단 (SOC-05, D-36)';

CREATE TABLE reports (
  id BIGINT NOT NULL AUTO_INCREMENT,
  reporter_id BIGINT NOT NULL COMMENT '신고한 회원',
  target_type VARCHAR(20) NOT NULL COMMENT '신고 대상 종류',
  target_id BIGINT NOT NULL COMMENT '신고 대상 번호',
  blog_id BIGINT NULL COMMENT '블로그 안의 일이면. 메인 프로필 신고는 NULL',
  handler VARCHAR(20) NOT NULL COMMENT '접수처. 신고할 때 정함 (D-85)',
  reason_code VARCHAR(30) NOT NULL COMMENT 'SPAM, ABUSE, OBSCENE, PRIVACY, COPYRIGHT, ETC (추천안 #9, V4)',
  detail VARCHAR(1000) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  result VARCHAR(20) NULL COMMENT '처리 결과',
  sanction_reason VARCHAR(30) NULL COMMENT '제재 사유 항목. 문제 없음이면 NULL (D-95, V8)',
  processed_by_member_id BIGINT NULL COMMENT '블로그장이 처리하면',
  processed_by_admin_id BIGINT NULL COMMENT '관리자가 처리하면',
  processed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '2주 제한 계산, 1년 보관',
  PRIMARY KEY (id),
  KEY idx_reports_dup (reporter_id, target_type, target_id, created_at),
  KEY idx_reports_handler (handler, status, blog_id),
  CONSTRAINT ck_reports_target_type CHECK (target_type IN ('MEMBER', 'BLOG_OWNER', 'BLOG', 'POST', 'COMMENT')),
  CONSTRAINT ck_reports_handler CHECK (handler IN ('BLOG_OWNER', 'ADMIN')),
  CONSTRAINT ck_reports_reason CHECK (reason_code IN ('SPAM', 'ABUSE', 'OBSCENE', 'PRIVACY', 'COPYRIGHT', 'ETC')),
  CONSTRAINT ck_reports_status CHECK (status IN ('PENDING', 'DONE')),
  CONSTRAINT ck_reports_result CHECK (result IN ('NONE', 'WARNING', 'SUSPEND', 'KICK', 'OWNER_WARNING', 'OWNER_KICK', 'BLOG_CLOSE')),
  CONSTRAINT ck_reports_sanction_reason CHECK (sanction_reason IN ('SPAM', 'ABUSE', 'OBSCENE', 'PRIVACY', 'COPYRIGHT', 'FLOOD', 'IMPERSONATION', 'ETC')),
  CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES members (id),
  CONSTRAINT fk_reports_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_reports_processed_by_member FOREIGN KEY (processed_by_member_id) REFERENCES members (id),
  CONSTRAINT fk_reports_processed_by_admin FOREIGN KEY (processed_by_admin_id) REFERENCES admins (id)
) COMMENT '신고 (SOC-06, D-45, D-85)';

CREATE TABLE notifications (
  id BIGINT NOT NULL AUTO_INCREMENT,
  recipient_id BIGINT NOT NULL COMMENT '받는 회원',
  type VARCHAR(40) NOT NULL COMMENT 'COMMENT, REPLY, LIKE, BLOG_CLOSING 등',
  tab VARCHAR(20) NOT NULL COMMENT '사이드바 탭',
  blog_id BIGINT NULL COMMENT '블로그 관련이면',
  actor_id BIGINT NULL COMMENT '알림을 만든 회원',
  target_type VARCHAR(20) NULL COMMENT 'POST, COMMENT, BLOG, NOTICE 등',
  target_id BIGINT NULL,
  detail VARCHAR(100) NULL COMMENT '문구에 넣는 짧은 값: 정지 기간, 폐쇄 날짜, 신고 처리 결과 (V7)',
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '30일 또는 7일 뒤 삭제',
  PRIMARY KEY (id),
  KEY idx_notifications_unread (recipient_id, is_read, created_at),
  KEY idx_notifications_tab (recipient_id, tab, created_at) COMMENT '탭별 목록 (V4)',
  CONSTRAINT ck_notifications_tab CHECK (tab IN ('COMMENT', 'LIKE', 'FOLLOW', 'BLOG', 'ADMIN')),
  CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES members (id),
  CONSTRAINT fk_notifications_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_notifications_actor FOREIGN KEY (actor_id) REFERENCES members (id)
) COMMENT '알림. 30초 폴링 (SOC-04, D-72)';

CREATE TABLE notification_settings (
  member_id BIGINT NOT NULL,
  type VARCHAR(40) NOT NULL COMMENT '끌 수 있는 종류만',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (member_id, type),
  CONSTRAINT fk_notification_settings_member FOREIGN KEY (member_id) REFERENCES members (id)
) COMMENT '알림 켜기·끄기. 행이 없으면 켜짐 (SOC-04, D-56)';

-- =========================================================
-- 5. 관리자·운영 (005)
-- =========================================================

CREATE TABLE admins (
  id BIGINT NOT NULL AUTO_INCREMENT,
  login_id VARCHAR(50) NOT NULL COMMENT '회원 이메일과 별개',
  password_hash VARCHAR(100) NOT NULL COMMENT 'bcrypt',
  name VARCHAR(50) NOT NULL,
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE COMMENT '임시 비밀번호면 TRUE. 바꾸기 전에는 다른 기능 못 씀 (V7, 추천안 #16)',
  password_changed_at DATETIME NULL COMMENT '이전에 받은 관리자 로그인 무효 (V7)',
  created_by BIGINT NULL COMMENT '만든 관리자. 첫 관리자(환경변수)는 NULL (V7)',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_admins_login_id (login_id)
) COMMENT '관리자 계정. 회원과 분리, 블로그 활동 안 함 (SEC-09, D-84)';

CREATE TABLE admin_action_logs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  admin_id BIGINT NOT NULL,
  action VARCHAR(20) NOT NULL COMMENT '조치 종류',
  target_type VARCHAR(20) NOT NULL COMMENT '대상 종류',
  target_id BIGINT NOT NULL,
  reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '1년 보관 (D-60)',
  PRIMARY KEY (id),
  KEY idx_admin_action_logs_created (created_at),
  CONSTRAINT ck_admin_action_logs_action CHECK (action IN ('WARN', 'KICK', 'DELETE', 'HIDE', 'UNHIDE', 'CLOSE',
    'RESTORE', 'VIEW_PII', 'REPORT', 'CREATE_ADMIN', 'NOTICE')),
  CONSTRAINT ck_admin_action_logs_target CHECK (target_type IN ('MEMBER', 'BLOG', 'POST', 'COMMENT', 'REPORT', 'ADMIN',
    'NOTICE')),
  CONSTRAINT fk_admin_action_logs_admin FOREIGN KEY (admin_id) REFERENCES admins (id)
) COMMENT '관리자 활동 기록, 개인정보 전체 보기 포함 (ADM-06, SEC-09)';

CREATE TABLE owner_warnings (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL COMMENT '경고받은 블로그장',
  admin_id BIGINT NOT NULL,
  report_id BIGINT NULL COMMENT '신고에서 시작했으면',
  reason_code VARCHAR(30) NULL COMMENT '제재 사유 항목 (D-95, V8)',
  reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '블로그별 최근 1년 3번이면 권한 박탈 (D-87)',
  PRIMARY KEY (id),
  KEY idx_owner_warnings_count (blog_id, member_id, created_at),
  CONSTRAINT ck_owner_warnings_reason_code CHECK (reason_code IN ('SPAM', 'ABUSE', 'OBSCENE', 'PRIVACY', 'COPYRIGHT', 'FLOOD', 'IMPERSONATION', 'ETC')),
  CONSTRAINT fk_owner_warnings_blog FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_owner_warnings_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT fk_owner_warnings_admin FOREIGN KEY (admin_id) REFERENCES admins (id),
  CONSTRAINT fk_owner_warnings_report FOREIGN KEY (report_id) REFERENCES reports (id)
) COMMENT '블로그장 경고 (ADM-07, D-30, D-87)';

CREATE TABLE notices (
  id BIGINT NOT NULL AUTO_INCREMENT,
  admin_id BIGINT NOT NULL COMMENT '작성한 관리자',
  title VARCHAR(100) NOT NULL,
  category VARCHAR(20) NOT NULL DEFAULT 'NOTICE' COMMENT '일반 NOTICE, 업데이트 UPDATE(릴리스), 수정 FIX, 점검 MAINTENANCE (D-98)',
  content_md TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_notices_list (status, created_at),
  CONSTRAINT ck_notices_status CHECK (status IN ('NORMAL', 'DELETED')),
  CONSTRAINT ck_notices_category CHECK (category IN ('NOTICE', 'UPDATE', 'FIX', 'MAINTENANCE')),
  CONSTRAINT fk_notices_admin FOREIGN KEY (admin_id) REFERENCES admins (id)
) COMMENT '메인 공지사항 (BRD-10)';

CREATE TABLE shedlock (
  name VARCHAR(64) NOT NULL COMMENT '배치 이름',
  lock_until DATETIME(3) NOT NULL,
  locked_at DATETIME(3) NOT NULL,
  locked_by VARCHAR(255) NOT NULL,
  PRIMARY KEY (name)
) COMMENT '배치 중복 실행 방지, ShedLock 기본 테이블 (SCL-03)';

CREATE TABLE link_previews (
  id BIGINT NOT NULL AUTO_INCREMENT,
  url_hash CHAR(64) NOT NULL COMMENT '주소의 SHA-256. 주소가 길어 이것으로 찾는다',
  url VARCHAR(2048) NOT NULL COMMENT '글에 쓴 주소',
  status VARCHAR(10) NOT NULL COMMENT 'OK 또는 FAILED',
  title VARCHAR(200) NULL COMMENT 'og:title, 없으면 <title>',
  description VARCHAR(300) NULL COMMENT 'og:description',
  site_name VARCHAR(100) NULL COMMENT 'og:site_name, 없으면 도메인',
  image_id BIGINT NULL COMMENT '서버가 받아 저장한 썸네일. 바깥 사진 주소는 쓰지 않는다',
  fetched_at DATETIME NOT NULL COMMENT '받은 시각. OK는 7일, FAILED는 하루 뒤 다시 받는다',
  PRIMARY KEY (id),
  UNIQUE KEY uk_link_previews_url_hash (url_hash),
  CONSTRAINT ck_link_previews_status CHECK (status IN ('OK', 'FAILED')),
  CONSTRAINT fk_link_previews_image FOREIGN KEY (image_id) REFERENCES images (id)
) COMMENT '링크 미리보기 카드 (D-92, V5)';
