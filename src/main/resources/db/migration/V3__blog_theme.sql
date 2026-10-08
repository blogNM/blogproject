-- 블로그 테마 틀과 꾸미기 (D-89, 사용자 요청 2026-10-08).
-- 머리 사진은 기존 블로그 대표 이미지(cover_image_id)를 쓴다.
ALTER TABLE blogs
  ADD COLUMN theme VARCHAR(20) NOT NULL DEFAULT 'ODYSSEY' COMMENT '테마 틀 7가지' AFTER notification_retention_days,
  ADD COLUMN theme_accent CHAR(7) NOT NULL DEFAULT '#7C0CB4' COMMENT '강조 색 #RRGGBB' AFTER theme,
  ADD COLUMN theme_font VARCHAR(10) NOT NULL DEFAULT 'SANS' COMMENT '글꼴' AFTER theme_accent,
  ADD COLUMN hero_title VARCHAR(40) NULL COMMENT '머리 사진 위 큰 문구' AFTER theme_font,
  ADD COLUMN hero_subtitle VARCHAR(100) NULL COMMENT '머리 사진 위 작은 문구' AFTER hero_title,
  ADD CONSTRAINT ck_blogs_theme CHECK (theme IN ('ODYSSEY', 'POSTER', 'WHATEVER', 'LETTER', 'BOOK_CLUB', 'MAGAZINE', 'PORTFOLIO')),
  ADD CONSTRAINT ck_blogs_theme_font CHECK (theme_font IN ('SANS', 'SERIF', 'ROUND', 'MONO')),
  ADD CONSTRAINT ck_blogs_theme_accent CHECK (theme_accent REGEXP '^#[0-9A-Fa-f]{6}$');

-- 검색·피드에서 글 제목과 함께 최신순으로 자주 읽는다.
CREATE INDEX idx_posts_status_created ON posts (status, created_at);
CREATE INDEX idx_comments_post ON comments (post_id, status, created_at);
