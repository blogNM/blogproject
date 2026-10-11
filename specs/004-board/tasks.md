# Tasks: 게시판 (글·댓글·태그·이미지·검색·피드·조회수)

**Input**: [plan.md](plan.md), [spec.md](spec.md)

**Tests**: 각 User Story의 Acceptance Scenario를 MockMvc 통합 테스트로 확인한다. `./gradlew test`.

## Phase 1: Foundational — 3단계 ✅

- [x] T001 엔티티: `Post`(+ `post_tags`), `Category`, `Image`
- [x] T002 [P] `MarkdownRenderer`: commonmark → jsoup, 사진은 `/uploads/`만 (D-64)
- [x] T003 [P] `FileStorage` 인터페이스, `LocalFileStorage`(`UPLOAD_DIR`) (D-75, SCL-02)
- [x] T004 [P] `BlogAuth.canManagePosts`, `BlogService.visibleBlog`(2단계 공개 범위·정지 확인을 글에도)
- [x] T005 화면 주소: `/blog/{주소}/posts/{번호}`, `/write`, `/posts/{번호}/edit`
- [x] T006 Toast UI Editor 3.2.2를 `static/vendor/toastui/`에 한 파일로, 글쓰기 화면만 인라인 style 허용

## Phase 2: User Story 1 - 글 쓰고 읽기 (P1) — 3단계

- [x] T007 [US1] 멤버만 쓰기, 폐쇄 예정에도 쓰기(D-67), 정지 멤버 막기
- [x] T008 [US1] 제목 1~30, 본문 1000자(사진 문법 빼고, 추천안 #14), 태그 10개·정리 규칙(6.4)
- [x] T009 [US1] 본문만 걸러낸 HTML을 `innerHTML`, 나머지는 `textContent` (US1-2·3)
- [x] T010 [US1] 브라우저 자동 임시저장, 다시 열면 불러오기 (US1-4)
- [x] T011 [US1] 삭제 확인 창 "글을 정말 삭제하시겠습니까?" (US1-6)
- [x] T012 [US1] og 태그(제목·요약·첫 사진), 볼 수 없는 글은 비움 (D-66, D-59)
- [x] T013 [US1] `PostTest`, `OgTagTest`

## Phase 3: User Story 2 - 수정·삭제 권한 (P1) — 3단계

- [x] T014 [US2] 수정은 작성자만, 삭제는 작성자·글 관리 권한, 다른 블로그는 거절 (D-07, D-58, D-71)
- [x] T015 [US2] 남이 지우면 작성자에게 알림 저장 (화면은 5단계)
- [x] T016 [US2] 30일 보관 뒤 04:00 완전 삭제 (`PostCleanupJob`)
- [x] T017 [US2] 블로그 나간 멤버 글 "탈퇴한 계정", 사이트 탈퇴 "탈퇴한 회원"
- [ ] T018 [US2] 관리자 삭제·30일 안 복구 → 관리자 로그인이 생기는 6단계

## Phase 4: User Story 3 - 목록·카테고리·태그 (P2) — 3단계

- [x] T019 [US3] 최신순·번호 페이지·10/20/30개, 공지는 위에 따로 (D-06, D-76)
- [x] T020 [US3] 카테고리 관리(글 관리 권한)와 카테고리별 보기, 지우면 "분류 없음"
- [x] T021 [US3] 태그를 누르면 같은 태그 글만
- [x] T022 [US3] 제목에 마우스를 올리면 첫 사진 (원본을 CSS로 줄임)
- [x] T023 [US3] `PostListTest`

## Phase 5: User Story 4 - 사진 올리기 (P2) — 3단계

- [x] T024 [US4] 확장자·MIME·시그니처 모두 확인, svg·문서·압축·동영상 거절 (SEC-08)
- [x] T025 [US4] 3MB·한 번에 10MB·글 하나에 10장 (설정값)
- [x] T026 [US4] EXIF(GPS 등) 제거, 방향만 남김, UUID 이름, 원래 이름은 DB에만
- [x] T027 [US4] 하루 넘게 글에 안 붙은 사진 정리
- [x] T028 [US4] `ImageTest`
- [~] T029 [US4] 블로그 대표 사진은 4단계 테마의 머리 사진으로 올림. 프로필 사진은 5단계 프로필에서

## Phase 6~9: User Story 5~8 — 4단계 ✅

- [x] T030 [US5] 댓글·답글(1단계, 답글의 답글은 첫 댓글에 붙이고 `@닉네임`), 500자, 수정됨 표시, 삭제(답글이 있으면 "삭제된 댓글입니다")
- [x] T031 [US5] 좋아요 켜기·끄기, 1분 30번 제한, 글쓴이 알림(같은 사람 하루 한 번), 댓글·답글 알림
- [x] T032 [US6] 통합 검색: 글 제목·블로그·태그·닉네임 탭과 건수, 관련도·최신·인기순, 두 글자 이상, 최근 검색어 10개
- [x] T033 [US7] 메인 피드: 최신·인기(7일)·팔로우·구독 탭, 메인 공지, 새로 생긴 블로그
- [x] T034 [US8] 링크 복사, 조회수(회원 번호·비회원 IP 해시로 하루 한 번)
- [x] T035 블로그 테마 틀 7가지·강조 색·글꼴·머리 사진·문구, 저장 전 미리보기 (D-89)
- [x] T036 새 디자인: Creat_ 워드마크, 메인·로그인·회원가입·오류 화면, 피드 카드 (D-90)
- [x] T037 테스트: `CommentTest` 5, `BoardTest` 7, `ThemeTest` 3, 없는 화면 주소 404 화면
- [x] T038 테스트 데이터 `activity-4.py`: 테마·머리 사진·사진 글·댓글·좋아요·조회수
