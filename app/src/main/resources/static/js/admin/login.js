// 관리자 로그인 (ADM-01). 회원 로그인과 따로 쓴다.
import { api } from '../api.js';
import { addPasswordToggles } from '../layout.js';
import { createCaptcha } from '../turnstile.js';

addPasswordToggles();

const form = document.getElementById('login-form');
const errorBox = document.getElementById('error');
// 아이디마다 3번, 같은 IP에서 5번 틀리면 사람 확인 상자가 나온다 (SEC-13, D-115)
const captcha = createCaptcha(document.getElementById('captcha'));

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  errorBox.textContent = '';
  const loginId = form.loginId.value.trim();
  if (!loginId || !form.password.value) {
    errorBox.textContent = '아이디와 비밀번호를 넣어 주세요.';
    return;
  }
  const button = form.querySelector('button[type=submit]');
  button.disabled = true;
  try {
    await api.post('/api/admin/auth/login', {
      loginId, password: form.password.value, turnstileToken: captcha.token(),
    });
    location.href = '/admin/index.html';
  } catch (err) {
    errorBox.textContent = err.message;
    button.disabled = false;
    if (err.details && err.details.captchaRequired) {
      await captcha.show(err.details.turnstileSiteKey);
    }
  }
});
