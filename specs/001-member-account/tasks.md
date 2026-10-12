# Tasks: 회원관리 (가입·로그인·계정)

**Input**: [plan.md](plan.md), [spec.md](spec.md)

**Tests**: 각 User Story의 Acceptance Scenario를 MockMvc 통합 테스트로 확인한다. `./gradlew test` (MySQL `blog_test` 필요, [로컬 실행 안내](../../docs/05-guides/로컬-실행.md)).

## Phase 1: Setup — 1단계 ✅

- [x] T001 Gradle 프로젝트, Spring Boot 4.1.1, Java 21 (`app/build.gradle`)
- [x] T002 [P] `application.yml`(숫자는 모두 환경변수), `application-local.yml`(개발용 메일·쿠키)
- [x] T003 [P] Flyway `V1__init_schema.sql` = `docs/04-design/schema.sql`
- [x] T004 [P] `.gitignore`에 빌드 결과물 추가

## Phase 2: Foundational — 1단계 ✅

- [x] T005 `SecurityConfig`: 보안 헤더 5종, 쿠키 CSRF, 무상태, 401·403 JSON (SEC-10·11·12)
- [x] T006 [P] `common/`: ApiException, 오류 응답 JSON, Hashing(SHA-256·HMAC·SecureRandom)
- [x] T007 [P] `RateLimiter` 인터페이스 + 메모리 구현 (D-80, 헌법 V 예외)
- [x] T008 [P] ShedLock + `@EnableAsync` + `Clock` 빈 (SCL-03)
- [x] T009 [P] `MailGateway`: Gmail SMTP / 개발용 콘솔 출력
- [x] T010 화면 공통: `api.js`(CSRF 헤더, 401이면 Refresh 후 재시도, 클릭·입력 추적), `layout.js`, `common.css`
- [x] T011 [P] `terms.html`, `privacy.html` 초안, 모든 화면 아래 개인정보 처리방침 링크 (4.6)

## Phase 3: User Story 1 - 이메일 인증으로 가입하기 (P1) — 1단계 ✅

- [x] T012 [US1] `Member`, `EmailVerification` 엔티티·리포지토리
- [x] T013 [US1] `MemberRules`: 이메일 소문자, 비밀번호 규칙, 닉네임·예약어, 이름·전화번호(추천안 #5)
- [x] T014 [US1] `SignupService.requestCode`: 1분 다시 보내기, IP당 1시간 10통, 이미 가입된 이메일도 같은 응답 (D-28 → D-99에서 409 `EMAIL_TAKEN`과 중복 확인 버튼으로 바꿈)
- [x] T015 [US1] `SignupService.verifyCode`: 10분 만료, 5번 틀리면 무효
- [x] T016 [US1] `SignupService.signup`: 인증 완료 재확인, 필수 동의 3개, bcrypt 저장, 인증 행 삭제
- [x] T017 [US1] `signup.html` + `signup.js`: 세 단계를 한 화면에서 (SC-001)
- [x] T018 [US1] `SignupTest` 9개, `MemberRulesTest`

## Phase 4: User Story 2 - 로그인과 로그아웃 (P1) — 1단계 ✅

- [x] T019 [US2] `RefreshToken`, `LoginFailure` 엔티티·리포지토리
- [x] T020 [US2] `JwtProvider`, `AuthCookies`, `JwtAuthenticationFilter`
- [x] T021 [US2] `LoginService.login`: IP당 1분 20번, 3번 실패부터 Turnstile, 5번이면 5분 잠금
- [x] T022 [US2] `LoginService.refresh`: 30분 무활동(직접 한 행동만), 14일 로그인 유지
- [x] T023 [US2] `LoginService.logout`: Refresh Token 폐기, 쿠키 삭제
- [x] T024 [US2] `TurnstileVerifier` (Cloudflare siteverify)
- [x] T025 [US2] `login.html` + `login.js`, 위쪽 메뉴의 로그인 상태·로그아웃
- [x] T026 [US2] `AuthCleanupJob`: 만료된 인증번호·토큰 정리, 04:00 + ShedLock
- [x] T027 [US2] `LoginTest` 11개, `SecurityHeadersTest` 3개
- [ ] T028 [US2] 만료 5분 전 "로그인을 연장할까요?" 창 → 3단계 글쓰기 화면과 함께

## Phase 5: User Story 3 - 비밀번호 찾기·재설정 (P2) — 2단계 ✅

- [x] T029 [US3] 인증번호 요청·확인(30분, 1회용, 5번 실패 무효), 같은 화면에서 새 비밀번호
- [x] T030 [US3] 재설정하면 그 회원의 Refresh Token 모두 폐기 + 로그인 잠금 해제
- [x] T031 [US3] 비밀번호를 바꾼 뒤 남은 Access Token도 다음 요청에서 풀리게 하기 (`members.credentials_changed_at`, V2)

## Phase 6: User Story 4 - 이메일 찾기 (P3) — 2단계 ✅

- [x] T032 [US4] 이름·전화번호로 가린 이메일과 가입일, 같은 IP 10분에 5번
- [x] T033 [US4] 결과에서 10분 임시 토큰으로 비밀번호 재설정 (D-26)

## Phase 7: User Story 5 - 회원정보 수정과 탈퇴 (P2) — 2단계 ✅

- [x] T034 [US5] 닉네임·전화번호·비밀번호 수정 (이메일·이름 불가)
- [ ] T035 [US5] 프로필 사진 → 업로드 검사가 생기는 3단계 이후
- [x] T036 [US5] 탈퇴: 운영 중인 블로그가 있으면 막기(002 필요), 이메일 비우기, 30일 뒤 개인정보 삭제 배치
- [x] T037 [US3·4·5] 화면 `forgot-password.html`, `find-email.html`, `me.html`, 로그인 화면 링크
- [x] T038 [US3·4·5] `PasswordResetTest` 7개, `EmailFindTest` 6개, `AccountTest` 8개
