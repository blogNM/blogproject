# Implementation Plan: 회원관리 (가입·로그인·계정)

**Branch**: `claude/project-thread-q5t8xs` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: [spec.md](spec.md), [헌법](../../.specify/memory/constitution.md), [ERD](../../docs/04-design/ERD.md)

## Summary

회원관리를 두 번에 나눠 만든다. 전체 구현 단계(6단계) 중 **1단계**는 프로젝트 뼈대와 User Story 1(가입)·2(로그인·로그아웃)이고, User Story 3~5(비밀번호 찾기, 이메일 찾기, 정보 수정·탈퇴)는 **2단계**에서 블로그 기능과 함께 만든다.

1단계에서 공통 규칙(보안 헤더, CSRF, JWT 쿠키, bcrypt, IP별 요청 제한, ShedLock)을 한 번 깔아 두고, 이후 단계는 그 위에 기능만 얹는다.

## Technical Context

| 항목 | 내용 |
|---|---|
| Language/Version | Java 21 |
| Primary Dependencies | Spring Boot 4.1.1 (Web MVC, Security, Data JPA, Validation, Mail, Flyway), jjwt 0.13, ShedLock 7.10 |
| Storage | MySQL 8. 스키마는 Flyway `V1__init_schema.sql` = `docs/04-design/schema.sql` 전체 34개 테이블 |
| Testing | JUnit 5 + MockMvc 통합 테스트, 실제 MySQL `blog_test` 데이터베이스 사용 |
| Target Platform | Linux 서버 1대 (1차) |
| Project Type | 웹 서비스: Spring Boot가 API와 정적 HTML·JS를 함께 제공 (D-66) |
| Performance Goals | 메인 화면 1초 목표, 늦어도 2초 (D-48) |
| Constraints | 세션 외 서버 메모리에 상태를 두지 않음 (IP별 요청 제한만 예외, D-83 대기) |

## Constitution Check

| 원칙 | 1단계에서 지키는 방법 | 결과 |
|---|---|---|
| I. 문서가 먼저 | 코드 주석·테스트 이름에 요구사항 ID와 D-번호를 적음. 미정 항목 #4·#5는 추천안을 기본값으로 쓰되 설정값으로 빼고 확인을 받음 | ✅ |
| II. 매 요청 서버가 확인 | 토큰에는 회원 번호만 담음. `/api/me`는 매번 DB에서 회원 상태를 읽음. 블로그별 역할 확인은 2단계 | ✅ |
| III. XSS·CSRF 한 곳에서 | `api.js` 한 곳에서 `X-XSRF-TOKEN`을 붙임. 화면은 `textContent`만 씀(1단계에 본문 없음). 보안 헤더 5종을 `SecurityConfig`에서 설정. 토큰은 HttpOnly·Secure 쿠키에만 | ✅ |
| IV. 개인정보 | bcrypt, 가입 여부를 드러내지 않는 같은 문구(D-28), 인증번호·Refresh Token은 해시로 저장하고 쓰면 바로 지움 | ✅ |
| V. 서버 메모리에 상태 없음 | 인증번호·로그인 실패·잠금·Refresh Token은 DB. 배치는 ShedLock + `zone = "Asia/Seoul"` | ⚠️ IP별 요청 제한만 메모리 (아래 Complexity Tracking) |
| VI. 숫자는 설정으로 | 만료 시간, 실패 횟수, 요청 제한 수치를 모두 `application.yml` + 환경변수로 둠 | ✅ |
| VII. 업로드 세 겹 검사 | 1단계에 업로드 없음 | 해당 없음 |
| VIII. 1차는 단순하게 | Redis·세션 서버 없이 DB만 씀 | ✅ |

## 설계 결정

### 로그인 토큰 (SEC-04, D-53, D-61, D-62)

- **Access Token**: JWT(HS256), 회원 번호만 담음, 5분. 쿠키 `ACCESS_TOKEN`(HttpOnly, Secure, SameSite=Lax, Path=/).
- **Refresh Token**: 임의 32바이트. 원문은 쿠키 `REFRESH_TOKEN`(Path=/api/auth)에만, DB `refresh_tokens`에는 SHA-256 해시만.
- **로그인 유지 체크**: 두 쿠키에 Max-Age 14일. DB 만료도 14일.
- **로그인 유지 미체크**: Max-Age 없는 세션 쿠키라 브라우저를 닫으면 사라짐. 서버는 `last_activity_at`에서 30분이 지나면 Refresh를 거절.
- **무활동 판단 (D-62)**: 화면(`api.js`)이 클릭·키 입력이 있었는지(`active`)를 Refresh 요청에 실어 보냄. 스크롤·마우스 움직임과 알림 폴링 같은 자동 요청은 `active=false`라 30분을 다시 시작하지 않음. 클릭·입력이 있으면 4분마다 Refresh를 보내 오래 글을 쓰는 동안 끊기지 않게 함.
- **만료 5분 전 "연장할까요?" 창**(US2-4)은 글쓰기 화면이 생기는 3단계에서 붙임.

### 가입 (USR-01, USR-02, D-28)

- 인증번호는 `SecureRandom` 6자리, DB에는 `HMAC-SHA256(서버 비밀 키, 용도:이메일:번호)`만 저장.
- ~~이미 가입된 이메일도 같은 응답(D-28)~~ → 가입 화면은 [중복 확인] 버튼으로 가입 여부를 알려 주고, 인증번호 요청도 409 `EMAIL_TAKEN` (D-99). 확인은 IP별 1분 20번.
- 메일은 `@Async`로 보내 응답 시간 차이도 없앰.
- 인증을 마치면 30분 안에 ②·③단계를 끝내야 함(`SIGNUP_VERIFIED_TTL`). 마지막 요청에서 서버가 인증 완료를 다시 확인함(US1-6).

### 로그인 실패 (SEC-03, SEC-13, D-79, D-80)

- `login_failures`에 이메일별로 셈(가입 안 된 이메일도 똑같이). 같은 이메일 동시 요청은 행 잠금(`SELECT … FOR UPDATE`)으로 셈을 빠뜨리지 않음.
- 3번 실패부터 Turnstile 토큰을 서버가 Cloudflare에 확인. 5번이면 5분 잠금. 잠금이 풀리면 처음부터 다시 셈.
- 가입 안 된 이메일도 bcrypt 비교를 한 번 해서 응답 시간이 같게 함.

### IP별 요청 제한 (D-80, 추천안 #2·#4)

- `RateLimiter` 인터페이스 + 메모리 구현(`InMemoryRateLimiter`). 로그인 IP당 1분 20번, 인증 메일 IP당 1시간 10통.
- 서버를 늘릴 때 Redis 구현으로 바꿈.

### 화면 (D-66)

- 정적 HTML + ES 모듈 JS. 인라인 스크립트 없이 CSP `script-src 'self'`(Turnstile만 추가 허용).
- 공통: `js/api.js`(CSRF·토큰 갱신·활동 추적), `js/layout.js`(위쪽 메뉴, 아래쪽 개인정보 처리방침 링크).
- 화면: `index.html`, `signup.html`(세 단계를 한 화면에서, SC-001), `login.html`, `terms.html`·`privacy.html`(초안).

### 1단계 기본값으로 쓴 추천안 (확인 대기)

| 항목 | 기본값 | 바꾸는 곳 |
|---|---|---|
| #4 IP별 요청 제한 | 로그인 1분 20번, 인증 메일 1시간 10통 | `RATE_LOGIN_PER_MINUTE`, `RATE_MAIL_PER_HOUR` |
| #5 이름 형식 | 1~20자, 한글·영문·공백 | `MemberRules.NAME` |
| #5 전화번호 형식 | 숫자만 10~11자리, 0으로 시작 | `MemberRules.PHONE` |
| 닉네임 예약어 | 예약어가 **들어간** 닉네임도 막음(예: "관리자2") | `MemberRules.RESERVED_NICKNAMES` |

## Project Structure

```text
app/build.gradle, settings.gradle, gradlew
app/src/main/java/com/blognm/mainblog/
├── config/      # AppProperties, SecurityConfig, SchedulingConfig(ShedLock·@Async), TimeConfig, CsrfController
├── common/      # ApiException, 오류 응답, Hashing, ClientIp
├── ratelimit/   # RateLimiter 인터페이스, 메모리 구현
├── mail/        # MailGateway(SMTP·개발용 콘솔), AuthMailService
├── member/      # Member, MemberRepository, MemberRules(입력 규칙), MeController
└── auth/        # 가입·로그인 서비스와 API, JWT, 쿠키, Turnstile, 인증 데이터 정리 배치
app/src/main/resources/
├── application.yml, application-local.yml
├── db/migration/V1__init_schema.sql
└── static/      # HTML, css/common.css, js/api.js, js/layout.js, js/pages/*.js
app/src/test/java/…  # 통합 테스트(SignupTest, LoginTest, SecurityHeadersTest), 단위 테스트(MemberRulesTest)
```

**Structure Decision**: 프로젝트 하나에 서버와 화면을 함께 둔다(D-66, 정적 HTML). 기능별 패키지로 나누고, 2단계부터 `blog/` 패키지를 더한다.

## API (1단계)

| 메서드·주소 | 설명 | 근거 |
|---|---|---|
| `GET /api/csrf` | XSRF-TOKEN 쿠키 발급 | SEC-10 |
| `GET /api/auth/signup/email-available?email=` | 이메일 중복 확인 → `{available}` | USR-01, D-99 |
| `POST /api/auth/signup/code` | 인증번호 요청 `{email}`. 이미 가입된 이메일은 409 `EMAIL_TAKEN` | USR-02, D-99 |
| `POST /api/auth/signup/verify` | 인증번호 확인 `{email, code}` | USR-02 |
| `GET /api/auth/signup/nickname-available?nickname=` | 닉네임 중복 확인 | USR-01 |
| `POST /api/auth/signup` | 가입 `{email, password, name, nickname, phone, agreeTerms, agreePrivacy, age14}` | USR-01 |
| `GET /api/auth/login-config` | Turnstile site key | SEC-13 |
| `POST /api/auth/login` | `{email, password, rememberMe, turnstileToken}` → 토큰 쿠키 | USR-03 |
| `POST /api/auth/refresh` | `{active}` → 새 Access Token 쿠키 | SEC-04, D-62 |
| `POST /api/auth/logout` | Refresh Token 폐기, 쿠키 삭제 | USR-04 |
| `GET /api/me` | 로그인한 회원 번호·닉네임 | USR-03 |

오류는 모두 `{code, message, details?}` JSON이고, 화면은 `message`를 그대로 보여준다.

## Complexity Tracking

| 원칙과 다른 점 | 이유 | 더 단순한 방법을 안 쓴 이유 |
|---|---|---|
| IP별 요청 제한을 서버 메모리에서 셈 (헌법 V) | 4.3 이중화 표와 추천안 #2. 서버 1대인 1차에는 문제가 없고 요청마다 DB에 쓰지 않음 | DB에 세면 로그인마다 쓰기가 늘고, Redis는 1차 범위 밖(헌법 VIII). 인터페이스로 분리해 두어 나중에 바꾸기 쉬움. D-83으로 확정 필요 |
