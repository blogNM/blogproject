-- 6단계: 블로그 운영 제재와 메인 관리자 (002 US4·US5, 005)

-- 관리자 계정: 첫 관리자는 환경변수로 만들고 첫 로그인에서 비밀번호를 바꾼다 (추천안 #16, 확인 대기)
ALTER TABLE admins
  ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT TRUE COMMENT '임시 비밀번호면 TRUE. 바꾸기 전에는 다른 기능을 못 씀' AFTER name,
  ADD COLUMN password_changed_at DATETIME NULL COMMENT '비밀번호를 바꾼 시각. 이전에 받은 로그인은 무효' AFTER must_change_password,
  ADD COLUMN created_by BIGINT NULL COMMENT '만든 관리자. 첫 관리자는 NULL' AFTER password_changed_at;

ALTER TABLE admin_action_logs DROP CHECK ck_admin_action_logs_action;
ALTER TABLE admin_action_logs
  ADD CONSTRAINT ck_admin_action_logs_action CHECK (action IN ('WARN', 'KICK', 'DELETE', 'HIDE', 'UNHIDE', 'CLOSE',
    'RESTORE', 'VIEW_PII', 'REPORT', 'CREATE_ADMIN')),
  ADD CONSTRAINT ck_admin_action_logs_target CHECK (target_type IN ('MEMBER', 'BLOG', 'POST', 'COMMENT', 'REPORT', 'ADMIN'));

-- 알림 문구에 넣을 짧은 값 (정지 기간, 폐쇄 날짜, 신고 처리 결과 등)
ALTER TABLE notifications
  ADD COLUMN detail VARCHAR(100) NULL COMMENT '문구에 넣는 짧은 값' AFTER target_id;

-- 블랙리스트 해제 문의로 풀린 항목. 행은 지우지 않는다 (D-55)
ALTER TABLE blacklist_entries
  ADD COLUMN member_id BIGINT NULL COMMENT '강제 퇴장된 회원. 화면에 닉네임만 보인다' AFTER blog_id,
  ADD COLUMN lifted_at DATETIME NULL COMMENT '해제 문의를 받아들인 시각 (BLG-12)' AFTER created_at,
  ADD CONSTRAINT fk_blacklist_entries_member FOREIGN KEY (member_id) REFERENCES members (id);

-- 폐쇄를 건 쪽: 블로그장, 관리자 강제 폐쇄(D-86), 블로그장 강퇴 뒤 부블로그장이 없어 닫힘(ADM-07)
ALTER TABLE blogs DROP CHECK ck_blogs_close_requested_by;
ALTER TABLE blogs
  ADD CONSTRAINT ck_blogs_close_requested_by CHECK (close_requested_by IN ('OWNER', 'ADMIN', 'OWNER_KICKED')),
  ADD COLUMN purged_at DATETIME NULL COMMENT '폐쇄 30일 뒤 글·사진을 지우고 주소를 풀어 준 시각' AFTER closed_at;

CREATE INDEX idx_ownership_transfers_blog ON ownership_transfers (blog_id, status);
CREATE INDEX idx_blacklist_appeals_blog ON blacklist_appeals (blog_id, status);
CREATE INDEX idx_reports_blog ON reports (blog_id, handler, status, created_at);
