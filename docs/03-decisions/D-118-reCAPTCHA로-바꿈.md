# D-118 로봇 확인을 Cloudflare Turnstile에서 Google reCAPTCHA로 바꿈

- 날짜: 2026-10-15
- 관련 요구사항: SEC-13(사람 확인), SEC-03, D-79, D-115
- 사용자 요청 (김채림, 2026-10-15): "클라우드페어는 로그인 하나식 다시 해야해서 그냥 구글로 할 수 있는 캡챠로 했는데 클라우드 페어를 빼고 리캡챠로 할 수 있나?" Google reCAPTCHA 키를 GitHub Actions secrets에 넣었다고 함
- 선택지
  1. Google reCAPTCHA v2 체크박스("로봇이 아닙니다")로 바꾼다 → 고름. 지금 화면(3번 틀리면 상자를 누름)과 같은 모양이다
  2. reCAPTCHA v3(점수 방식, 상자 없음) → 점수 기준을 따로 정해야 하고, 틀린 횟수 뒤에 상자를 보여 주는 지금 규칙(D-79, D-115)과 맞지 않는다
  3. Turnstile과 reCAPTCHA를 둘 다 두고 설정으로 고른다 → 쓰지 않을 코드가 남는다
- 결정
  - 사람 확인은 Google reCAPTCHA v2 체크박스. Cloudflare Turnstile 코드는 지운다
  - 언제 보이는지는 그대로: 계정마다 3번 틀리면 상자, 5번이면 5분 잠금, 같은 IP에서 계정을 바꿔 1시간에 5번 틀리면 상자. 관리자 로그인도 같다 (D-115)
  - 키: `RECAPTCHA_SITE_KEY`(사이트 키)·`RECAPTCHA_SECRET_KEY`(비밀 키). GitHub Actions secrets → `deploy.sh` → `secrets.env`. 이름을 다르게 넣었을 때를 위해 `RECAPTCHA_ID`·`RECAPTCHA_KEY`·`RECAPTCHA_CLIENT_ID`·`RECAPTCHA_SITEKEY`, `RECAPTCHA_PASSWORD`·`RECAPTCHA_SECRET`·`RECAPTCHA_CLIENT_SECRET`·`RECAPTCHA_PW`도 읽는다. 없으면 Google이 공개한 v2 테스트 키(늘 통과, 상자에 경고 표시)로 뜨고 서버 로그에 경고
  - 서버 확인 주소 `https://www.google.com/recaptcha/api/siteverify`
  - CSP: Google이 권하는 주소만 연다. `script-src`에 `https://www.google.com/recaptcha/`·`https://www.gstatic.com/recaptcha/`, `frame-src`에 `https://www.google.com/recaptcha/`·`https://recaptcha.google.com/recaptcha/`, `connect-src`에 `https://www.google.com/recaptcha/`. reCAPTCHA 상자가 style 속성으로 크기·숨김을 주므로 회원·관리자 로그인 화면은 글쓰기 화면처럼 인라인 style을 허용한다(스크립트 인라인은 그대로 막음)
  - API 이름: 로그인 요청 `turnstileToken` → `captchaToken`, 응답 `turnstileSiteKey` → `captchaSiteKey`, `/api/auth/login-config`도 `captchaSiteKey`
  - 화면: `js/turnstile.js` → `js/captcha.js`. 다크모드면 상자도 `theme: 'dark'` (D-116)
  - 키 만드는 법은 [도커-배포.md](../05-guides/도커-배포.md#로봇-확인-실제-키-넣기-d-118)
