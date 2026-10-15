-- 관심 없음 (SOC-08, D-123): 회원·블로그를 나에게만 숨긴다. 상대에게는 알리지 않고 상대 화면도 그대로다.
-- 회원이나 블로그가 지워지면 함께 지운다.
CREATE TABLE member_mutes (
  member_id BIGINT NOT NULL COMMENT '숨긴 회원',
  muted_id BIGINT NOT NULL COMMENT '숨겨진 회원',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id, muted_id),
  CONSTRAINT fk_member_mutes_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
  CONSTRAINT fk_member_mutes_muted FOREIGN KEY (muted_id) REFERENCES members (id) ON DELETE CASCADE
) COMMENT '관심 없는 회원 (SOC-08, D-123)';

CREATE TABLE blog_mutes (
  member_id BIGINT NOT NULL COMMENT '숨긴 회원',
  blog_id BIGINT NOT NULL COMMENT '숨겨진 블로그',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id, blog_id),
  CONSTRAINT fk_blog_mutes_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
  CONSTRAINT fk_blog_mutes_blog FOREIGN KEY (blog_id) REFERENCES blogs (id) ON DELETE CASCADE
) COMMENT '관심 없는 블로그 (SOC-08, D-123)';
