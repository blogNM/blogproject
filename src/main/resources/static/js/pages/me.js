import { api } from '../api.js';
import { el, requireLogin } from '../layout.js';
import { PASSWORD_MESSAGE, PASSWORD_RULE } from '../rules.js';

await requireLogin();

const $ = (id) => document.getElementById(id);

async function loadProfile() {
  const p = await api.get('/api/me/profile');
  $('email').textContent = p.email;
  $('name').textContent = p.name;
  $('joined').textContent = p.joinedAt;
  $('nickname').value = p.nickname;
  $('phone').value = p.phone;
}
await loadProfile();

function show(id, text, ok) {
  $(id).textContent = text;
  $(id).className = ok ? 'info' : 'error';
}

$('profile-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    await api.put('/api/me/profile', { nickname: $('nickname').value, phone: $('phone').value });
    show('profile-message', '저장했습니다.', true);
    await loadProfile();
    const nick = document.querySelector('.site-nav .nickname');
    if (nick) nick.textContent = $('nickname').value + '님';
  } catch (err) {
    show('profile-message', err.message, false);
  }
});

$('password-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const pw = $('new-password').value;
  if (!PASSWORD_RULE.test(pw)) {
    show('password-message', PASSWORD_MESSAGE, false);
    return;
  }
  if (pw !== $('new-password2').value) {
    show('password-message', '새 비밀번호가 서로 다릅니다.', false);
    return;
  }
  try {
    await api.post('/api/me/password', { currentPassword: $('current-password').value, newPassword: pw });
    e.target.reset();
    show('password-message', '비밀번호를 바꿨습니다. 다른 기기의 로그인은 모두 풀렸습니다.', true);
  } catch (err) {
    show('password-message', err.message, false);
  }
});

$('withdraw-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const owned = $('owned-blogs');
  owned.hidden = true;
  if (!$('withdraw-confirm').checked) {
    show('withdraw-message', '탈퇴 안내를 확인하고 체크해 주세요.', false);
    return;
  }
  try {
    await api.post('/api/me/withdraw', { password: $('withdraw-password').value });
    location.href = '/login.html?withdrawn';
  } catch (err) {
    show('withdraw-message', err.message, false);
    if (err.code === 'OWNS_BLOGS') {
      owned.replaceChildren(...err.details.blogs.map((b) => {
        const li = el('li');
        li.append(el('a', { href: '/blog/' + encodeURIComponent(b.slug) }, b.name), el('span', { class: 'muted' }, ' /blog/' + b.slug));
        return li;
      }));
      owned.hidden = false;
    }
  }
});
