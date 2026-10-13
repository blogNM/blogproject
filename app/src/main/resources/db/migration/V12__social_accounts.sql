-- 구글 로그인 (D-103, 사용자 요청 2026-10-13).
-- 회원 한 명에 바깥 로그인 계정을 연결해 둔다. 회원은 지우지 않고 비우므로, 탈퇴하면 연결 행을 지운다.
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
