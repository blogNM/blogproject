# 메인블로그 (티스토리형 블로그 플랫폼)

회원이 블로그를 만들고 다른 회원과 함께 운영하는 블로그 플랫폼입니다.
메인블로그는 회원가입·로그인, 블로그 생성·참여, 소셜, 전체 관리를 맡고, 글쓰기는 회원이 만든 개별 블로그에서 합니다.

## 작업 전에 읽을 것

- 문서 지도: [docs/README.md](docs/README.md)
- 프로젝트 원칙: [.specify/memory/constitution.md](.specify/memory/constitution.md) (GitHub Spec Kit 헌법)
- 기능 명세: [specs/README.md](specs/README.md). 기능마다 `specs/00N-*/spec.md`가 있고, 요구사항 ID 접두어로 찾습니다 (USR→001, BLG→002, SOC→003, BRD→004, ADM→005).
- 기능을 만들거나 고칠 때는 해당 spec의 요구사항 ID(예: `USR-02`)와 Clarifications의 결정 기록(예: `D-66`)을 먼저 확인합니다.
- 6장 세부 정책에서 상태가 **보류**인 항목과 spec의 `[NEEDS CLARIFICATION]`은 임의로 정하지 말고 사람에게 물어봅니다.

## Spec Kit으로 개발하기

`/speckit-clarify`(남은 질문 정리) → `/speckit-plan`(헌법 확인, data-model·contracts) → `/speckit-tasks` → `/speckit-implement` 순서로 진행합니다. 새 기능은 `/speckit-specify`로 `specs/`에 추가합니다. PDF(`docs/01-requirements`, `docs/02-policies`)는 2026-10-02 원본이고, 이후 바뀌는 내용은 spec과 결정 기록에만 반영합니다 (D-82).

## 기술 스택 (요구사항분석서 7장, 모두 확정)

- 백엔드: Spring Boot, Spring Security (보안 헤더, CSRF, 인증·인가), MySQL
- 로그인: JWT + HttpOnly 쿠키, "로그인 유지" 체크박스. 구글·네이버·카카오 로그인은 Spring Security OAuth2 Client로 받고 같은 JWT 쿠키를 내려줌 (D-103, D-107, D-108)
- 화면: 정적 HTML + JS. 글 상세·블로그 홈·사이트 첫 화면의 og 태그만 서버가 채움 (D-96, D-100)
- 에디터: Toast UI Editor (마크다운)
- 메일: Gmail SMTP, 6자리 인증번호를 Spring에서 직접 발송·검증
- 검색: MySQL FULLTEXT + ngram
- 블로그 주소: `/blog/{주소}`

## 빌드·실행·테스트

- 폴더: 소스코드는 `app/`(Gradle 프로젝트, 서버에는 이 폴더만 올림), 문서는 `docs/`·`specs/`·`.specify/`. 아래 명령은 `app/` 안에서 실행합니다.
- 실행: `./gradlew bootRun --args='--spring.profiles.active=local'` (메일은 콘솔에 찍힘)
- 테스트: `./gradlew test` (MySQL `blog_test` 필요)
- 자세한 준비 방법: [docs/05-guides/로컬-실행.md](docs/05-guides/로컬-실행.md)

## 꼭 지킬 규칙

- XSS: 글 본문만 서버가 마크다운→HTML 변환 후 jsoup으로 걸러서 보내고, 화면은 본문에만 `innerHTML`을 씁니다. 닉네임·댓글·태그 등 나머지는 모두 `textContent`.
- CSRF: 모든 JS 요청에 `X-XSRF-TOKEN` 헤더를 붙입니다. 공통 `api.js` 한 곳에서 처리합니다.
- 비밀번호는 bcrypt로만 저장합니다.
- 서버 이중화 대비: 세션 외에는 서버 메모리에 상태를 두지 않습니다 (SCL-01).
- 개수 제한 같은 값은 설정 파일 + 환경변수로 둡니다. 예: `blog.limit.public=${BLOG_LIMIT_PUBLIC:3}`
- 업로드 허용 형식은 jpg·jpeg·png·gif·webp뿐이고, 확장자·MIME·파일 시그니처를 모두 검사합니다 (SEC-08).

## 변경 기록 (꼭 지킬 것)

- 기능 추가, 화면·디자인 변경, 버그 수정, 설정·문서 변경은 **모두** [CHANGELOG.md](CHANGELOG.md)에 남깁니다. 작은 문구·버튼 하나라도 남깁니다.
- 변경을 커밋할 때 같은 커밋(또는 같은 PR)에 CHANGELOG.md 항목을 함께 넣습니다. 형식은 파일 맨 위 설명을 따릅니다.

## 문서를 고칠 때

- 새 결정은 `docs/03-decisions/`에 기록하고 `D-번호`를 이어서 붙입니다 (현재 마지막 번호는 D-110, D-83은 IP별 요청 제한 결정용으로 비워 둠). 관련 spec의 Clarifications에도 한 줄 남깁니다.
- "꼭 지킬 규칙"을 고치면 헌법(`.specify/memory/constitution.md`)도 같이 고칩니다.
- 문서나 spec을 추가하면 `docs/README.md`와 `specs/README.md`의 목록도 함께 고칩니다.
