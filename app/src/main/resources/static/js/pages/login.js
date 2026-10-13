import { api } from '../api.js';
import { initLayout } from '../layout.js';

// 로그인 뒤 돌아갈 화면. 다른 사이트로 보내지 않도록 이 사이트 안의 주소만 받는다.
const nextParam = new URLSearchParams(location.search).get('next');
const next = nextParam && nextParam.startsWith('/') && !nextParam.startsWith('//') && !nextParam.startsWith('/\\')
  ? nextParam : '/';

const me = await initLayout();
if (me) {
  location.replace(next);
}

const form = document.getElementById('login-form');
const errorBox = document.getElementById('error');
const captchaBox = document.getElementById('captcha');
const notice = document.getElementById('notice');

if (new URLSearchParams(location.search).has('joined')) {
  notice.textContent = '가입이 끝났습니다. 로그인해 주세요.';
  notice.hidden = false;
} else if (new URLSearchParams(location.search).has('withdrawn')) {
  notice.textContent = '탈퇴했습니다. 그동안 이용해 주셔서 감사합니다.';
  notice.hidden = false;
}

// 구글 로그인 (D-103). 서버에 구글 앱 값이 있을 때만 버튼을 보인다.
const social = new URLSearchParams(location.search).get('social');
if (social === 'failed') {
  errorBox.textContent = '구글 로그인을 끝내지 못했어요. 다시 시도해 주세요.';
} else if (social === 'unverified') {
  errorBox.textContent = '이 구글 계정으로는 로그인할 수 없어요. 이메일이 확인된 구글 계정을 쓰거나 이메일로 로그인해 주세요.';
}
api.get('/api/auth/social/config')
  .then((config) => { document.getElementById('google-login').hidden = !config.google; })
  .catch(() => {});

let turnstileToken = null;
let widgetId = null;

// 로그인 3번 실패 뒤부터 Cloudflare Turnstile을 보여준다 (SEC-13, D-79)
function loadTurnstile() {
  if (window.turnstile) return Promise.resolve(window.turnstile);
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
    s.async = true;
    s.onload = () => resolve(window.turnstile);
    s.onerror = reject;
    document.head.append(s);
  });
}

async function showCaptcha(siteKey) {
  captchaBox.hidden = false;
  const ts = await loadTurnstile();
  if (widgetId === null) {
    widgetId = ts.render(captchaBox, {
      sitekey: siteKey,
      callback: (token) => { turnstileToken = token; },
      'expired-callback': () => { turnstileToken = null; },
    });
  } else {
    ts.reset(widgetId);
    turnstileToken = null;
  }
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  errorBox.textContent = '';
  const button = form.querySelector('button[type=submit]');
  button.disabled = true;
  try {
    await api.post('/api/auth/login', {
      email: form.email.value,
      password: form.password.value,
      rememberMe: document.getElementById('remember').checked,
      turnstileToken,
    });
    location.href = next;
  } catch (err) {
    errorBox.textContent = err.message;
    if (err.details && err.details.captchaRequired) {
      await showCaptcha(err.details.turnstileSiteKey);
    }
  } finally {
    button.disabled = false;
  }
});
