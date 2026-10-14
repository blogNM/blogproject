-- 로그인 실패 표에 관리자 아이디와 IP도 센다 (SEC-03, SEC-13, D-115).
-- 회원은 이메일 그대로, 관리자는 'admin:아이디', IP는 'ip:주소'. 이메일에는 늘 @가 있어 겹치지 않는다.
ALTER TABLE login_failures
  MODIFY email VARCHAR(255) NOT NULL COMMENT '회원 이메일(소문자) · admin:관리자아이디 · ip:주소',
  MODIFY fail_count TINYINT NOT NULL DEFAULT 0 COMMENT '계정은 3번부터 Turnstile·5번이면 잠금, IP는 1시간 안에 5번부터 Turnstile',
  COMMENT = '로그인 실패와 계정별 잠금, IP별 실패 (SEC-03, SEC-13, D-80, D-115)';
