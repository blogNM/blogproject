-- 제재 사유 항목 (D-95, 사용자 요청 2026-10-08). 신고 처리와 제재마다 사유를 골라 사유별로 나눠 본다.
ALTER TABLE reports
  ADD COLUMN sanction_reason VARCHAR(30) NULL COMMENT '제재 사유 항목. 문제 없음이면 NULL' AFTER result,
  ADD CONSTRAINT ck_reports_sanction_reason CHECK (sanction_reason IN ('SPAM', 'ABUSE', 'OBSCENE', 'PRIVACY',
    'COPYRIGHT', 'FLOOD', 'IMPERSONATION', 'ETC'));

ALTER TABLE member_sanctions
  ADD COLUMN reason_code VARCHAR(30) NULL COMMENT '제재 사유 항목. 정지 풀기는 NULL' AFTER duration,
  ADD CONSTRAINT ck_member_sanctions_reason_code CHECK (reason_code IN ('SPAM', 'ABUSE', 'OBSCENE', 'PRIVACY',
    'COPYRIGHT', 'FLOOD', 'IMPERSONATION', 'ETC'));

ALTER TABLE owner_warnings
  ADD COLUMN reason_code VARCHAR(30) NULL COMMENT '제재 사유 항목' AFTER report_id,
  ADD CONSTRAINT ck_owner_warnings_reason_code CHECK (reason_code IN ('SPAM', 'ABUSE', 'OBSCENE', 'PRIVACY',
    'COPYRIGHT', 'FLOOD', 'IMPERSONATION', 'ETC'));
