-- 비밀번호를 바꾸거나 탈퇴한 뒤 남아 있는 Access Token을 다음 요청에서 바로 풀기 위한 시각 (001 T031, SEC-04).
-- 이 시각보다 먼저 발급된 Access Token은 받지 않는다. 초 단위로 잘라 저장한다.
ALTER TABLE members
  ADD COLUMN credentials_changed_at DATETIME NULL COMMENT '비밀번호 변경·탈퇴 시각. 이전에 발급된 Access Token 무효 (T031)' AFTER withdrawn_at;
