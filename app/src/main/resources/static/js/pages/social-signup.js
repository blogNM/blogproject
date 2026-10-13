import { api } from '../api.js';

// 구글 로그인으로 처음 온 사람의 가입 마무리 (D-103). 이메일은 구글이 확인했으므로 닉네임과 약관 동의만 받는다.
const $ = (id) => document.getElementById(id);
const errorBox = $('error');
const form = $('social-signup');

try {
  const pending = await api.get('/api/auth/social/pending');
  $('google-email').textContent = pending.email;
} catch (err) {
  errorBox.textContent = err.message;
  form.querySelector('button[type=submit]').disabled = true;
}

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
  const button = form.querySelector('button[type=submit]');
  button.disabled = true;
  try {
    await api.post('/api/auth/social/signup', {
      nickname: $('nickname').value,
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
