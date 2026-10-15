-- 릴리스 공지 번호를 사이트에 올라간 순서대로 바꾼다 (2026-10-15, 김채림 요청).
-- 위쪽 메뉴 프로필 사진(v1.11.0)이 다크모드(v1.9.0)보다 먼저 올라가 번호가 거꾸로 보였다.
-- 공지 글(id·올린 시각·알림)은 그대로 두고 제목의 번호만 바꾼다. 내용은 버전 만들기 워크플로가 같은 제목으로 다시 채운다.
-- 한 문장으로 바꿔 서로 번호를 주고받는 제목끼리 겹치지 않는다. 없는 제목은 건드리지 않는다.
UPDATE notices
SET title = CASE title
    WHEN 'Creat_ v1.11.0 업데이트 소식' THEN 'Creat_ v1.9.0 업데이트 소식'
    WHEN 'Creat_ v1.9.0 업데이트 소식' THEN 'Creat_ v1.10.0 업데이트 소식'
    WHEN 'Creat_ v1.10.0 업데이트 소식' THEN 'Creat_ v1.11.0 업데이트 소식'
    WHEN 'Creat_ v1.10.1 업데이트 소식' THEN 'Creat_ v1.11.1 업데이트 소식'
  END
WHERE status = 'NORMAL'
  AND title IN ('Creat_ v1.11.0 업데이트 소식', 'Creat_ v1.9.0 업데이트 소식',
                'Creat_ v1.10.0 업데이트 소식', 'Creat_ v1.10.1 업데이트 소식');
