-- 관리자가 공지에 넣는 사진 (D-102, 사용자 요청 2026-10-12).
-- 관리자는 회원이 아니므로 올린 회원(uploader_id)을 비우고 올린 관리자(admin_uploader_id)를 남긴다.
-- 공지 사진은 글에 붙지 않으므로, 글에 붙지 않은 사진을 지우는 정리 작업이 건드리지 않는다.
ALTER TABLE images DROP FOREIGN KEY fk_images_uploader;
ALTER TABLE images
  MODIFY uploader_id BIGINT NULL COMMENT '올린 회원. 관리자가 올린 공지 사진은 NULL',
  ADD COLUMN admin_uploader_id BIGINT NULL COMMENT '공지 사진을 올린 관리자' AFTER uploader_id,
  ADD CONSTRAINT fk_images_uploader FOREIGN KEY (uploader_id) REFERENCES members (id),
  ADD CONSTRAINT fk_images_admin_uploader FOREIGN KEY (admin_uploader_id) REFERENCES admins (id);
