-- 검색 속도 (D-109, v1.4.1). 검색 결과는 그대로이고, MySQL이 읽는 순서만 바뀐다.
--
-- 공개 여부·상태 칸의 분포(히스토그램)를 MySQL에 알려 준다.
-- 분포를 모르면 MySQL은 "공개·숨김 아님·운영 중" 블로그가 1개뿐이라고 어림잡고(칸마다 10~20%씩 곱함),
-- 공개 블로그의 글을 모두 훑은 뒤 글마다 태그·차단 여부를 맞춰 본다. 글 3만 개에서 태그 검색이 300ms 넘게 걸리던 원인이다.
-- 분포를 알면 태그에서 출발해 그 태그가 달린 글만 읽는다.
-- 히스토그램은 이 시점의 분포를 저장해 두고 저절로 바뀌지 않는다. 이 칸들은 값 종류가 적고 비율이 거의 바뀌지 않아 그대로 둬도 된다.
ANALYZE TABLE blogs UPDATE HISTOGRAM ON visibility, is_hidden, status WITH 16 BUCKETS;
ANALYZE TABLE posts UPDATE HISTOGRAM ON status WITH 8 BUCKETS;
