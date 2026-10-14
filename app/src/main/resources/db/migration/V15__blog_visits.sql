-- 블로그 통계 (D-112). 블로그 홈·글 화면을 연 기록 한 줄씩.
--
-- IP는 저장하지 않는다. visitor_hash는 HMAC(비밀 키, 날짜 + IP + 브라우저 정보)라 되돌릴 수 없고, 날이 바뀌면 값이 달라져
-- 다른 날의 기록끼리 같은 사람인지 알 수 없다. 그래서 "방문자"는 하루에 한 사람을 한 번으로 센 수다.
-- 블로그·글이 지워져도 통계 기록이 먼저 걸리지 않도록 외래 키는 두지 않는다. 블로그를 완전히 지울 때 함께 지운다.
CREATE TABLE blog_visits (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blog_id BIGINT NOT NULL,
  post_id BIGINT NULL COMMENT '블로그 홈이면 NULL',
  visited_at DATETIME NOT NULL COMMENT '한국 시간',
  visitor_hash CHAR(64) NOT NULL COMMENT '하루마다 바뀌는 방문자 값. IP 원문은 두지 않음',
  source VARCHAR(20) NOT NULL COMMENT '들어온 경로: search, feed, popular, explore, new, comment, profile, external, direct 등',
  ref_host VARCHAR(100) NULL COMMENT '다른 사이트에서 왔을 때 그 사이트 주소(호스트)만',
  keyword VARCHAR(50) NULL COMMENT '검색해서 왔을 때 검색어',
  PRIMARY KEY (id),
  KEY idx_blog_visits_blog_time (blog_id, visited_at),
  KEY idx_blog_visits_repeat (visitor_hash, blog_id, visited_at),
  KEY idx_blog_visits_time (visited_at)
) COMMENT '블로그 방문 기록. 보관 기간(blog.stats.retention-days)이 지나면 새벽 정리 작업이 지운다 (D-112)';
