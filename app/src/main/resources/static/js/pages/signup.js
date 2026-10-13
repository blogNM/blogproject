import { api } from '../api.js';
import { initLayout } from '../layout.js';
import { PASSWORD_MESSAGE, PASSWORD_RULE } from '../rules.js';

const me = await initLayout();
if (me) {
  location.replace('/');
}

const $ = (id) => document.getElementById(id);
const state = { email: null, password: null, emailChecked: null, nicknameChecked: null };

function showStep(n) {
  [1, 2, 3].forEach((i) => { $('step' + i).hidden = i !== n; });
  document.querySelectorAll('.steps li').forEach((li) => {
    li.classList.toggle('active', Number(li.dataset.step) === n);
    li.classList.toggle('done', Number(li.dataset.step) < n);
  });
}

// ① 이메일 인증 (USR-02)
let resendTimer = null;
let resendTimerRunning = false;
function startResendCooldown(seconds) {
  const button = $('send-code');
  let left = seconds;
  button.disabled = true;
  resendTimerRunning = true;
  clearInterval(resendTimer);
  resendTimer = setInterval(() => {
    left -= 1;
    button.textContent = left > 0 ? `다시 보내기 (${left}초)` : '다시 보내기';
    if (left <= 0) {
      clearInterval(resendTimer);
      resendTimerRunning = false;
      button.disabled = state.emailChecked === null;
    }
  }, 1000);
}

// 이메일 중복 확인 (D-99). 확인한 이메일과 지금 칸의 값이 같을 때만 인증번호를 받을 수 있다.
$('check-email').addEventListener('click', async () => {
  const email = $('email').value.trim();
  $('step1-error').textContent = '';
  try {
    const res = await api.get('/api/auth/signup/email-available?email=' + encodeURIComponent(email));
    // 구글·네이버·카카오로 로그인하던 이메일이면 그 회사로 로그인하라고 알려 준다 (D-103, D-107, D-108)
    const provider = res.provider || (res.google ? 'google' : null);
    const label = { naver: '네이버', kakao: '카카오' }[provider] || '구글';
    $('email-message').textContent = res.available
      ? '가입할 수 있는 이메일입니다.'
      : provider
        ? `${label} 계정으로 가입된 이메일입니다. 로그인 화면에서 ${label}로 로그인해 주세요.`
        : '이미 가입된 이메일입니다. 로그인하거나 비밀번호 찾기를 이용해 주세요.';
    state.emailChecked = res.available ? email : null;
  } catch (err) {
    $('email-message').textContent = '';
    $('step1-error').textContent = err.message;
    state.emailChecked = null;
  }
  $('send-code').disabled = state.emailChecked === null || resendTimerRunning;
});

$('email').addEventListener('input', () => {
  if (state.emailChecked !== null && $('email').value.trim() !== state.emailChecked) {
    state.emailChecked = null;
    $('email-message').textContent = '';
    $('send-code').disabled = true;
  }
});

$('send-code').addEventListener('click', async () => {
  $('step1-error').textContent = '';
  if (state.emailChecked === null) {
    $('step1-error').textContent = '이메일 중복 확인을 먼저 해 주세요.';
    return;
  }
  try {
    const res = await api.post('/api/auth/signup/code', { email: state.emailChecked });
    state.email = state.emailChecked;
    $('send-message').textContent = res.message;
    $('code-box').hidden = false;
    $('code').focus();
    startResendCooldown(60);
  } catch (err) {
    $('step1-error').textContent = err.message;
  }
});

$('step1').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('step1-error').textContent = '';
  try {
    await api.post('/api/auth/signup/verify', { email: state.email, code: $('code').value });
    $('email').readOnly = true;
    showStep(2);
    $('password').focus();
  } catch (err) {
    $('step1-error').textContent = err.message;
  }
});

// ② 비밀번호 (SEC-02). 서버도 같은 규칙으로 다시 확인한다.

$('step2').addEventListener('submit', (e) => {
  e.preventDefault();
  const pw = $('password').value;
  if (!PASSWORD_RULE.test(pw)) {
    $('step2-error').textContent = PASSWORD_MESSAGE;
    return;
  }
  if (pw !== $('password2').value) {
    $('step2-error').textContent = '비밀번호가 서로 다릅니다.';
    return;
  }
  $('step2-error').textContent = '';
  state.password = pw;
  showStep(3);
  $('name').focus();
});

// ③ 이름·닉네임·전화번호와 동의
$('check-nickname').addEventListener('click', async () => {
  const nickname = $('nickname').value;
  try {
    const res = await api.get('/api/auth/signup/nickname-available?nickname=' + encodeURIComponent(nickname));
    $('nickname-message').textContent = res.available ? '쓸 수 있는 닉네임입니다.' : '이미 사용 중인 닉네임입니다.';
    state.nicknameChecked = res.available ? nickname : null;
  } catch (err) {
    $('nickname-message').textContent = err.message;
    state.nicknameChecked = null;
  }
});

const consents = document.querySelectorAll('.consent');
$('agree-all').addEventListener('change', (e) => {
  consents.forEach((c) => { c.checked = e.target.checked; });
});
consents.forEach((c) => c.addEventListener('change', () => {
  $('agree-all').checked = [...consents].every((x) => x.checked);
}));

$('step3').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('step3-error').textContent = '';
  try {
    await api.post('/api/auth/signup', {
      email: state.email,
      password: state.password,
      name: $('name').value,
      nickname: $('nickname').value,
      phone: $('phone').value,
      agreeTerms: $('agree-terms').checked,
      agreePrivacy: $('agree-privacy').checked,
      age14: $('age14').checked,
    });
    location.href = '/login.html?joined';
  } catch (err) {
    $('step3-error').textContent = err.message;
    if (err.code === 'EMAIL_NOT_VERIFIED') {
      $('email').readOnly = false;
      showStep(1);
    }
  }
});
