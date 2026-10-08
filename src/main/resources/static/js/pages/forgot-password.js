import { api } from '../api.js';
import { initLayout } from '../layout.js';
import { PASSWORD_MESSAGE, PASSWORD_RULE } from '../rules.js';

await initLayout();

const $ = (id) => document.getElementById(id);
// 이메일 찾기 결과에서 넘어오면 이메일 대신 10분짜리 임시 토큰을 쓴다 (D-26)
const findToken = sessionStorage.getItem('findToken');
const state = { email: null, findToken: null };

function target() {
  return state.findToken ? { findToken: state.findToken } : { email: state.email };
}

function showStep2(message) {
  $('step1').hidden = true;
  $('step2').hidden = false;
  $('sent-message').textContent = message;
  $('code').focus();
}

if (findToken) {
  state.findToken = findToken;
  showStep2((sessionStorage.getItem('findMasked') || '찾은 이메일') + '로 인증번호를 보냈습니다.');
}

$('step1').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('step1-error').textContent = '';
  try {
    state.email = $('email').value;
    const res = await api.post('/api/auth/password/code', { email: state.email });
    showStep2(res.message);
  } catch (err) {
    $('step1-error').textContent = err.message;
  }
});

$('resend').addEventListener('click', async () => {
  $('step2-error').textContent = '';
  try {
    const res = await api.post('/api/auth/password/code', target());
    $('sent-message').textContent = res.message;
  } catch (err) {
    $('step2-error').textContent = err.message;
  }
});

$('step2').addEventListener('submit', async (e) => {
  e.preventDefault();
  const pw = $('password').value;
  if (!PASSWORD_RULE.test(pw)) {
    $('step2-error').textContent = PASSWORD_MESSAGE;
    return;
  }
  if (pw !== $('password2').value) {
    $('step2-error').textContent = '새 비밀번호가 서로 다릅니다.';
    return;
  }
  $('step2-error').textContent = '';
  try {
    await api.post('/api/auth/password/reset', { ...target(), code: $('code').value, newPassword: pw });
    sessionStorage.removeItem('findToken');
    sessionStorage.removeItem('findMasked');
    $('step2').hidden = true;
    $('done').hidden = false;
  } catch (err) {
    $('step2-error').textContent = err.message;
    if (err.code === 'FIND_TOKEN_EXPIRED') {
      sessionStorage.removeItem('findToken');
    }
  }
});
