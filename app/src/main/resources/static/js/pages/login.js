import { api } from '../api.js';
import { initLayout } from '../layout.js';
import { createCaptcha } from '../turnstile.js';

// 미로 공은 SVG 애니메이션이라 CSS로 멈출 수 없어, 동작 줄이기를 켜면 여기서 멈춘다 (D-105)
if (matchMedia('(prefers-reduced-motion: reduce)').matches) document.querySelectorAll('svg.art').forEach((svg) => svg.pauseAnimations());

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

// 구글 로그인 (D-103)·네이버 로그인 (D-107)·카카오 로그인 (D-108). 서버에 앱 값이 있는 회사의 버튼만 보인다.
const params = new URLSearchParams(location.search);
const social = params.get('social');
const provider = params.get('provider');
const providerName = { naver: '네이버', kakao: '카카오' }[provider] || '구글';
if (social === 'failed') {
  errorBox.textContent = `${providerName} 로그인을 끝내지 못했어요. 다시 시도해 주세요.`;
} else if (social === 'unverified') {
  errorBox.textContent = provider === 'naver' || provider === 'kakao'
    ? `이 ${providerName} 계정으로는 로그인할 수 없어요. 이메일로 로그인해 주세요.`
    : '이 구글 계정으로는 로그인할 수 없어요. 이메일이 확인된 구글 계정을 쓰거나 이메일로 로그인해 주세요.';
} else if (social === 'no-email') {
  errorBox.textContent = provider === 'kakao'
    ? '카카오에서 인증된 이메일 제공에 동의해야 가입할 수 있어요. 카카오로 로그인을 다시 눌러 이메일 제공에 동의하거나, 카카오계정의 이메일 인증을 마쳐 주세요.'
    : '네이버에서 이메일 제공에 동의해야 가입할 수 있어요. 네이버로 로그인을 다시 눌러 이메일 주소 제공에 동의해 주세요.';
}
api.get('/api/auth/social/config')
  .then((config) => {
    document.getElementById('google-login').hidden = !config.google;
    document.getElementById('naver-login').hidden = !config.naver;
    document.getElementById('kakao-login').hidden = !config.kakao;
  })
  .catch(() => {});

// 같은 이메일로 3번, 같은 IP에서 계정을 바꿔 가며 5번 틀리면 사람 확인 상자가 나온다 (SEC-13, D-79, D-115)
const captcha = createCaptcha(captchaBox);

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
      turnstileToken: captcha.token(),
    });
    location.href = next;
  } catch (err) {
    errorBox.textContent = err.message;
    if (err.details && err.details.captchaRequired) {
      await captcha.show(err.details.turnstileSiteKey);
    }
  } finally {
    button.disabled = false;
  }
});
