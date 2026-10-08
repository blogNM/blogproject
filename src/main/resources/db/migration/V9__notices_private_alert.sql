-- 메인 공지사항 쓰기 (BRD-10) — 관리자 활동 기록에 공지 작성·수정을 남긴다
ALTER TABLE admin_action_logs DROP CHECK ck_admin_action_logs_action;
ALTER TABLE admin_action_logs DROP CHECK ck_admin_action_logs_target;
ALTER TABLE admin_action_logs
  ADD CONSTRAINT ck_admin_action_logs_action CHECK (action IN ('WARN', 'KICK', 'DELETE', 'HIDE', 'UNHIDE', 'CLOSE',
    'RESTORE', 'VIEW_PII', 'REPORT', 'CREATE_ADMIN', 'NOTICE')),
  ADD CONSTRAINT ck_admin_action_logs_target CHECK (target_type IN ('MEMBER', 'BLOG', 'POST', 'COMMENT', 'REPORT',
    'ADMIN', 'NOTICE'));

CREATE INDEX idx_notices_list ON notices (status, created_at);
