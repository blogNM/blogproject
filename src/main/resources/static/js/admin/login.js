// 관리자 로그인 (ADM-01). 회원 로그인과 따로 쓴다.
import { api } from '../api.js';

const form = document.getElementById('login-form');
const errorBox = document.getElementById('error');

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
    await api.post('/api/admin/auth/login', { loginId, password: form.password.value });
    location.href = '/admin/index.html';
  } catch (err) {
    errorBox.textContent = err.message;
    button.disabled = false;
  }
});
