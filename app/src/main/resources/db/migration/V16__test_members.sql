-- 테스트 회원 표시 (D-113). 실제 사이트에 넣어 기능을 확인하는 계정이다.
-- 글·댓글·블로그는 모두에게 그대로 보이고, 닉네임 옆에 "테스트" 표시가 붙는다. 관리자 회원 목록에서 따로 고를 수 있다.
-- 테스트 데이터 지우기는 이 값이 TRUE인 회원과 그 회원이 만든 블로그를 기준으로 고른다.
ALTER TABLE members
  ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT FALSE COMMENT '테스트 회원 (D-113)' AFTER status,
  ADD KEY idx_members_is_test (is_test);
