-- 5단계 뒤: 글 속 링크 미리보기 카드 (D-92)
CREATE TABLE link_previews (
  id BIGINT NOT NULL AUTO_INCREMENT,
  url_hash CHAR(64) NOT NULL COMMENT '주소의 SHA-256. 주소가 길어 이것으로 찾는다',
  url VARCHAR(2048) NOT NULL COMMENT '글에 쓴 주소',
  status VARCHAR(10) NOT NULL COMMENT 'OK 또는 FAILED',
  title VARCHAR(200) NULL COMMENT 'og:title, 없으면 <title>',
  description VARCHAR(300) NULL COMMENT 'og:description',
  site_name VARCHAR(100) NULL COMMENT 'og:site_name, 없으면 도메인',
  image_id BIGINT NULL COMMENT '서버가 받아 저장한 썸네일. 바깥 사진 주소는 쓰지 않는다',
  fetched_at DATETIME NOT NULL COMMENT '받은 시각. OK는 7일, FAILED는 하루 뒤 다시 받는다',
  PRIMARY KEY (id),
  UNIQUE KEY uk_link_previews_url_hash (url_hash),
  CONSTRAINT ck_link_previews_status CHECK (status IN ('OK', 'FAILED')),
  CONSTRAINT fk_link_previews_image FOREIGN KEY (image_id) REFERENCES images (id)
) COMMENT '링크 미리보기 카드 (D-92)';
