# Implementation Plan: 게시판 — 3·4단계 (글쓰기·읽기, 댓글·검색·피드)

**Branch**: `claude/project-thread-q5t8xs` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: [spec.md](spec.md), [헌법](../../.specify/memory/constitution.md), [ERD](../../docs/04-design/ERD.md), [구현 단계 계획](../구현-단계-계획.md)

## Summary

**3단계**에서 004의 User Story 1(글 쓰고 읽기)·2(수정·삭제 권한)·3(목록·카테고리·태그)·4(사진 올리기)와 글 상세의 og 태그를 만든다. 여기까지 오면 "블로그를 만들고 글을 쓰고 읽는" 기본 블로그가 돌아간다.

User Story 5~8(댓글·공감, 통합 검색, 메인 피드·공지, 링크 복사·조회수)은 4단계에서 만든다 (아래 "4단계"). 관리자 삭제·복구는 관리자 로그인이 생기는 6단계에서 만든다.

## Technical Context

1·2단계와 같다. 새로 쓰는 것:

| 항목 | 내용 |
|---|---|
| 마크다운 → HTML | commonmark-java 0.24 (+ 표·취소선·자동 링크) |
| HTML 거르기 | jsoup 1.18 `Safelist.relaxed()` + 사진은 `/uploads/`만 (헌법 III, D-64) |
| 에디터 | Toast UI Editor 3.2.2. 바깥 CDN 대신 `static/vendor/toastui/`에 한 파일로 묶어 둠(CSP `script-src 'self'`) |
| 사진 저장 | `FileStorage` 인터페이스 + `LocalFileStorage`(`UPLOAD_DIR`, 기본 `./data/uploads`) (D-75, SCL-02) |
| 스키마 변경 | 없음. V1의 `posts`, `categories`, `post_tags`, `images`를 그대로 씀 |

## Constitution Check

| 원칙 | 3단계에서 지키는 방법 | 결과 |
|---|---|---|
| I. 문서가 먼저 | #14(본문 1000자 기준)는 추천안을 기본값으로 쓰고 spec에 "확인 대기"로 적음 | ✅ |
| II. 매 요청 서버가 확인 | 글 쓰기·수정·삭제마다 `blog_memberships`를 다시 읽음. 정지 멤버는 403 `SUSPENDED`. 카테고리 관리는 `@blogAuth.canManagePosts` | ✅ |
| III. XSS·CSRF 한 곳에서 | 본문만 서버가 걸러 `innerHTML`, 나머지는 `textContent`. 사진 올리기도 `api.js`(`api.upload`)로 `X-XSRF-TOKEN` | ✅ |
| IV. 개인정보 | 사진의 EXIF(GPS·기기) 제거, 원래 파일 이름은 DB에만 | ✅ |
| V. 서버 메모리에 상태 없음 | 사진은 `FileStorage` 뒤에 두어 이중화 때 S3로 바꿈. 임시저장은 브라우저에만 | ✅ |
| VI. 숫자는 설정으로 | 사진 3MB(`UPLOAD_MAX_FILE_SIZE`), 한 번에 10MB, 글 하나에 10장(`UPLOAD_MAX_IMAGES_PER_POST`) | ✅ |
| VII. 업로드 세 겹 검사 | 확장자·MIME·시그니처가 모두 같은 형식일 때만 받음 (SEC-08) | ✅ |
| VIII. 1차는 단순하게 | 썸네일을 만들지 않고 원본을 CSS로 줄임. 목록은 SQL 한 번 + 태그 한 번 | ✅ |

## 설계 결정

### 권한 (BRD-01, D-07, D-58, D-71)

- 쓰기: 그 블로그의 멤버. 폐쇄 예정 7일 동안에도 쓴다(D-67). 공지는 "글 관리" 권한(블로그장, 글 관리 권한이 있는 부블로그장).
- 수정: 작성자만. 블로그장도 남의 글은 못 고친다.
- 삭제: 작성자, 또는 글 관리 권한. 남이 지우면 작성자에게 `POST_DELETED` 알림(BLOG 탭)을 저장한다. 관리자 삭제는 6단계.
- 블로그를 나간 멤버의 글은 `author_left`로 "탈퇴한 계정"이 되고, 다시 들어와도 예전 글은 고치지 못한다. 사이트를 탈퇴한 회원은 "탈퇴한 회원".
- 블로그를 볼 수 있는지는 2단계 `BlogService.visibleBlog`로 똑같이 본다. 볼 수 없으면 404.

### 본문과 XSS (헌법 III, D-64)

- 마크다운 원문을 저장하고, 읽을 때마다 commonmark → jsoup으로 거른다. `<script>`, `on*` 속성, `style`, `iframe`, `javascript:` 링크는 남지 않는다. 링크에는 `rel="nofollow noopener noreferrer"`.
- 사진은 이 사이트에 올린 `/uploads/...`만 남긴다. 바깥 사진은 지운다(CSP `img-src 'self'`와 맞춤, 바깥 서버가 독자 IP를 모으지 못하게).
- 본문 글자 수는 이미지 문법 `![](주소)`을 뺀 원문으로 센다(추천안 #14, 확인 대기).
- 글쓰기 화면만 CSP에 `style-src 'unsafe-inline'`을 더한다. Toast UI Editor가 style 속성으로 화면을 그리기 때문이다. 스크립트 규칙은 모든 화면이 같다.

### 사진 (BRD-05, 6.3, SEC-08)

- 올리면 바로 UUID 이름으로 저장하고 `images.post_id`는 비워 둔다. 글을 저장할 때 본문의 `/uploads/{UUID}`를 순서대로 찾아 그 글에 붙이고, 첫 사진이 목록 미리보기·og 이미지가 된다.
- 내가 올렸거나 이미 이 글에 붙은 사진만 붙는다. 남의 사진 주소를 넣으면 보이기는 하지만 내 글에 붙지 않는다.
- EXIF 제거는 다시 압축하지 않고 메타데이터 덩어리만 뺀다(JPEG APP1·APP13 등, PNG eXIf·텍스트, WebP EXIF·XMP). 휴대폰 사진이 눕지 않도록 JPEG의 방향(Orientation) 값만 새 EXIF로 다시 넣는다.
- 하루 넘게 글에 붙지 않은 사진, 지운 지 30일 지난 글은 04:00 `PostCleanupJob`이 DB를 먼저 지우고 파일을 지운다(ShedLock).
- 사진 주소는 추측할 수 없는 UUID라 따로 권한을 보지 않는다. 비공개 블로그 사진의 주소가 밖으로 퍼지면 볼 수 있다는 점은 1차에 감수한다.

### 목록 (BRD-02·03·04)

- 최신순, 번호 페이지, 10·20·30개(그 밖의 값은 10개). 공지는 첫 페이지 위에 따로 5개까지.
- 카테고리 이름은 1~20자, 블로그 안에서 중복 불가(이 단계에서 정한 기본값). 지우면 그 글은 "분류 없음".
- 태그는 6.4대로 정리한다. 2단계 블로그 태그도 같은 함수라 이제 가운데 공백이 `_`로 바뀐다.

### og 태그 (D-66, D-59)

- `/blog/{주소}/posts/{번호}`는 서버가 `post.html`의 `<!--OG-->` 자리에 og 태그를 채워 보낸다. 값은 모두 HTML 이스케이프.
- 비회원이 볼 수 없는 글(비공개, 키 없는 일부 공개)은 채우지 않는다. 일부 공개는 `?key=`가 맞으면 채운다.
- 이미지는 글의 첫 사진, 없으면 블로그 대표 이미지. 대표 이미지를 올리는 화면은 아직 없다(2단계 T010과 함께 남음).

## API

| 메서드 | 주소 | 권한 | 내용 |
|---|---|---|---|
| GET | `/api/blogs/{slug}/posts?key=&page=&size=&category=&tag=` | 블로그를 볼 수 있는 사람 | 공지 + 글 목록 |
| POST | `/api/blogs/{slug}/posts` | 멤버 | `{title, content, categoryId, tags, notice}` |
| GET | `/api/posts/{id}?key=` | 블로그를 볼 수 있는 사람 | 걸러낸 HTML, 작성자에게만 마크다운 원문 |
| PUT | `/api/posts/{id}` | 작성자 | 수정 |
| DELETE | `/api/posts/{id}` | 작성자, 글 관리 권한 | 삭제(30일 보관) |
| GET | `/api/blogs/{slug}/categories?key=` | 블로그를 볼 수 있는 사람 | 카테고리와 글 수 |
| POST·PUT·DELETE | `/api/blogs/{slug}/categories[/{id}]` | 글 관리 권한 | 추가·이름 바꾸기·삭제 |
| POST | `/api/blogs/{slug}/images` | 멤버 | `file` 하나. `{id, url}` |
| GET | `/uploads/{UUID}` | 누구나 | 사진 |

화면: `/blog/{주소}`(글 목록이 붙음), `/blog/{주소}/posts/{번호}`(글 상세, og), `/blog/{주소}/write`, `/blog/{주소}/posts/{번호}/edit`, 관리 화면의 카테고리.

## 4단계 (댓글·좋아요·검색·피드·조회수, 테마)

User Story 5~8과 블로그 테마 틀(D-89), 새 디자인(D-90)을 만든다.

- 댓글: `comments.parent_id`는 첫 댓글만 가리키고(1단계), 답글의 답글은 `reply_to_member_id`로 `@닉네임`을 붙인다 (D-78). 지우면 `status = DELETED`, 답글이 남아 있으면 자리만 남긴다. `posts.comment_count`를 같이 고친다.
- 좋아요: `post_likes`(회원, 글) 하나로 켜고 끈다. 알림은 `notifications`에 같은 사람·같은 글 하루 한 번.
- 조회수: `post_views`에 `INSERT IGNORE`(글, 보는 사람, 날짜). 비회원은 IP를 HMAC으로 바꾼 값만 둔다 (D-77, 추천안 #13). 서버 메모리에 아무것도 두지 않는다 (SCL-01).
- 검색: `MATCH … AGAINST ('"검색어"' IN BOOLEAN MODE)` + ngram. 연산자 문자는 공백으로 바꿔 사용자가 검색식을 못 넣게 한다. 공개·숨기지 않은 블로그의 글만, 차단한 사이는 뺀다.
- 피드: 최신·인기(최근 7일, 좋아요 → 조회수)·팔로우·구독. 볼 때마다 모아 온다 (D-73).
- 테마: `blogs.theme·theme_accent·theme_font·hero_title·hero_subtitle`(V3). 머리 사진은 `cover_image_id`. 화면은 블로그 글 목록 API 하나로 틀마다 칸을 다르게 그리고, 색은 CSSOM으로만 넣는다(CSP `style-src 'self'` 유지).
- 숫자 설정: `board.*` (`BOARD_REACTIONS_PER_MINUTE` 등).

| 메서드 | 주소 | 권한 | 내용 |
|---|---|---|---|
| GET·POST | `/api/posts/{id}/comments?key=` | 읽기: 글을 볼 수 있는 사람 / 쓰기: 로그인 | 댓글 목록(답글 포함), `{content, parentId}` |
| PUT·DELETE | `/api/comments/{id}` | 작성자 (삭제는 글 관리 권한도) | 수정·삭제 |
| POST | `/api/posts/{id}/like` | 로그인 | 켜기·끄기, `{liked, likes}` |
| GET | `/api/feed?tab=LATEST\|POPULAR\|FOLLOWING\|SUBSCRIBED` | 누구나 (팔로우·구독은 로그인) | 피드 카드 |
| GET | `/api/notices` | 누구나 | 메인 공지 |
| GET | `/api/search?q=&type=POST\|BLOG\|TAG\|NICKNAME&sort=` | 누구나 | 건수와 결과 |
| GET·DELETE | `/api/me/recent-searches[/{id}]` | 로그인 | 최근 검색어 |
| PUT | `/api/blogs/{slug}/theme` | 정보 수정 권한 | `{theme, accent, font, heroTitle, heroSubtitle}` |
| POST·DELETE | `/api/blogs/{slug}/cover` | 정보 수정 권한 | 머리 사진 올리기(`file`)·빼기 |

## Complexity Tracking

| 예외 | 왜 필요한가 | 더 단순한 대안을 안 쓴 이유 |
|---|---|---|
| 글쓰기 화면만 `style-src 'unsafe-inline'` | Toast UI Editor가 style 속성을 씀 | 에디터를 바꾸면 D-74를 바꿔야 함. 스크립트는 막혀 있어 위험은 CSS에 그침 |
