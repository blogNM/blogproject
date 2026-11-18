-- 이메일 한 곳에 하루 보낸 인증번호 수 (D-130). 비밀번호 찾기·가입 인증번호를 이메일마다 하루 RATE_MAIL_PER_EMAIL_PER_DAY(10)번까지.
-- 번호마다 5번씩 맞혀 볼 수 있어, 이 제한이 없으면 1분마다 새 번호를 받아 한 계정에 시간당 약 300번 맞혀 볼 수 있었고
-- 받는 사람은 메일 폭탄을 받았다. 서버 두 대가 같이 세도록 DB에 둔다 (SCL-01). 지난 날짜 행은 새벽 정리 작업이 지운다.
CREATE TABLE email_daily_sends (
  email VARCHAR(255) NOT NULL COMMENT '소문자. 회원과 연결하지 않음 (가입 전 이메일도 셈)',
  purpose VARCHAR(20) NOT NULL COMMENT 'SIGNUP 또는 PASSWORD_RESET',
  send_date DATE NOT NULL COMMENT '한국 날짜',
  sent_count INT NOT NULL COMMENT '그날 보낸(또는 보낸 것처럼 답한) 인증번호 수',
  PRIMARY KEY (email, purpose, send_date),
  KEY idx_email_daily_sends_date (send_date)
) COMMENT '이메일별 하루 인증번호 발송 수 (D-130)';

-- 관리자가 로그아웃한 시각 (D-130). 이보다 먼저 받은 관리자 토큰은 2시간이 남았어도 쓰지 못한다.
ALTER TABLE admins
  ADD COLUMN tokens_revoked_at DATETIME NULL COMMENT '로그아웃한 시각. 이전에 받은 관리자 로그인은 무효 (D-130)' AFTER password_changed_at;
