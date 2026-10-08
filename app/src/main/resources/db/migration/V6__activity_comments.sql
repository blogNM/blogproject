-- [내 활동] 쓴 댓글 목록 (D-94)
ALTER TABLE members
  ADD COLUMN show_comments BOOLEAN NOT NULL DEFAULT TRUE COMMENT '쓴 댓글 다른 사람에게 보여주기 (D-94)' AFTER show_posts;

CREATE INDEX idx_comments_author ON comments (author_id, created_at);
