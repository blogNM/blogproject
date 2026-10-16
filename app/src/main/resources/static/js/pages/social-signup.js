import { api } from '../api.js';
import { createCaptcha } from '../captcha.js';
import { PASSWORD_MESSAGE, PASSWORD_RULE } from '../rules.js';

// 구글(D-103)·네이버(D-107)·카카오(D-108) 로그인으로 처음 온 사람. 먼저 고른다 (D-126):
// ① 이미 있는 계정의 이메일·비밀번호를 확인하고 연결 ② 닉네임·비밀번호를 정해 새로 가입 (이메일 확인은 그 회사 확인으로 대신)
const $ = (id) => document.getElementById(id);
const choice = $('choice');
const linkForm = $('social-link');
const form = $('social-signup');
const errorBox = $('error');

try {
  const pending = await api.get('/api/auth/social/pending');
  $('social-email').textContent = pending.email;
  const label = { naver: '네이버', kakao: '카카오' }[pending.provider] || '구글';
  document.querySelectorAll('.provider-name').forEach((n) => { n.textContent = label; });
} catch (err) {
  $('pending-error').textContent = err.message;
  choice.querySelectorAll('button').forEach((b) => { b.disabled = true; });
}

function show(target) {
  choice.hidden = target !== choice;
  linkForm.hidden = target !== linkForm;
  form.hidden = target !== form;
  const first = target.querySelector('input');
  if (first) first.focus();
}
$('choose-link').addEventListener('click', () => show(linkForm));
$('choose-signup').addEventListener('click', () => show(form));
document.querySelectorAll('.back-to-choice').forEach((b) => b.addEventListener('click', () => show(choice)));

// ----- 기존 계정에 연결 -----

// 로그인 화면과 같이 여러 번 틀리면 사람 확인 상자가 나온다 (SEC-13, D-79, D-115)
const captcha = createCaptcha($('captcha'));

linkForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  $('link-error').textContent = '';
  const button = linkForm.querySelector('button[type=submit]');
  button.disabled = true;
  try {
    await api.post('/api/auth/social/link', {
      email: $('link-email').value,
      password: $('link-password').value,
      captchaToken: captcha.token(),
    });
    location.href = '/';
  } catch (err) {
    $('link-error').textContent = err.message;
    if (err.details && err.details.captchaRequired) {
      await captcha.show(err.details.captchaSiteKey);
    }
  } finally {
    button.disabled = false;
  }
});

// ----- 새로 가입 -----

$('check-nickname').addEventListener('click', async () => {
  const nickname = $('nickname').value;
  try {
    const res = await api.get('/api/auth/signup/nickname-available?nickname=' + encodeURIComponent(nickname));
    $('nickname-message').textContent = res.available ? '쓸 수 있는 닉네임입니다.' : '이미 사용 중인 닉네임입니다.';
  } catch (err) {
    $('nickname-message').textContent = err.message;
  }
});

const consents = document.querySelectorAll('.consent');
$('agree-all').addEventListener('change', (e) => {
  consents.forEach((c) => { c.checked = e.target.checked; });
});
consents.forEach((c) => c.addEventListener('change', () => {
  $('agree-all').checked = [...consents].every((x) => x.checked);
}));

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  errorBox.textContent = '';
  const password = $('password').value;
  if (!PASSWORD_RULE.test(password)) {
    errorBox.textContent = PASSWORD_MESSAGE;
    return;
  }
  if (password !== $('password2').value) {
    errorBox.textContent = '비밀번호가 서로 다릅니다.';
    return;
  }
  const button = form.querySelector('button[type=submit]');
  button.disabled = true;
  try {
    await api.post('/api/auth/social/signup', {
      nickname: $('nickname').value,
      password,
      agreeTerms: $('agree-terms').checked,
      agreePrivacy: $('agree-privacy').checked,
      age14: $('age14').checked,
    });
    location.href = '/';
  } catch (err) {
    errorBox.textContent = err.message;
  } finally {
    button.disabled = false;
  }
});
