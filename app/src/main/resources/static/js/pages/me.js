import { api } from '../api.js';
import { el, requireLogin } from '../layout.js';
import { PASSWORD_MESSAGE, PASSWORD_RULE } from '../rules.js';

const me = await requireLogin();

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

// ----- 프로필: 사진·소개 (SOC-03) -----

function showPhoto(url) {
  $('photo').replaceChildren(url ? el('img', { src: url, alt: '' }) : el('span', {}, [...me.nickname][0]));
  $('photo-delete').hidden = !url;
}

async function loadSocial() {
  const p = await api.get(`/api/members/${me.id}`);
  showPhoto(p.imageUrl);
  $('bio').value = p.bio || '';
  $('bio-length').textContent = String([...$('bio').value].length);
  $('my-activity').href = '/members/' + me.id;
}
await loadSocial();

$('bio').addEventListener('input', () => { $('bio-length').textContent = String([...$('bio').value].length); });
$('bio-save').addEventListener('click', async () => {
  try {
    await api.put('/api/me/bio', { bio: $('bio').value });
    show('bio-message', '소개를 저장했습니다.', true);
  } catch (err) {
    show('bio-message', err.message, false);
  }
});
$('photo-input').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const form = new FormData();
  form.append('file', file);
  try {
    const res = await api.upload('/api/me/profile-image', form);
    showPhoto(res.url);
    show('bio-message', '프로필 사진을 바꿨습니다.', true);
  } catch (err) {
    show('bio-message', err.message, false);
  }
  e.target.value = '';
});
$('photo-delete').addEventListener('click', async () => {
  try {
    await api.del('/api/me/profile-image');
    showPhoto(null);
  } catch (err) {
    show('bio-message', err.message, false);
  }
});

// ----- 알림 켜기·끄기와 보관 기간 (SOC-04, D-56) -----

function renderSettings(s) {
  $('notice-settings').replaceChildren(...s.items.map((item) => {
    const label = el('label', { class: 'checkbox' });
    const box = el('input', { type: 'checkbox' });
    box.checked = item.enabled;
    box.addEventListener('change', async () => {
      try {
        renderSettings(await api.put('/api/me/notification-settings', { enabled: { [item.type]: box.checked } }));
        $('notice-message').textContent = `"${item.label}" 알림을 ${box.checked ? '켰' : '껐'}습니다.`;
      } catch (err) {
        box.checked = !box.checked;
        $('notice-message').textContent = err.message;
      }
    });
    label.append(box, document.createTextNode(item.label));
    return label;
  }));
  $('always-on').textContent = s.alwaysOn.join(', ');
  $('retention').value = String(s.retentionDays);
}
renderSettings(await api.get('/api/me/notification-settings'));
$('retention').addEventListener('change', async () => {
  try {
    renderSettings(await api.put('/api/me/notification-settings', { retentionDays: Number($('retention').value) }));
    $('notice-message').textContent = `알림을 ${$('retention').value}일 동안 보관합니다.`;
  } catch (err) {
    $('notice-message').textContent = err.message;
  }
});

// ----- 차단한 회원 (SOC-05) -----

async function loadBlocked() {
  const list = await api.get('/api/me/blocks');
  if (!list.length) {
    $('blocked').replaceChildren(el('li', { class: 'muted' }, '차단한 회원이 없어요.'));
    return;
  }
  $('blocked').replaceChildren(...list.map((m) => {
    const li = el('li', { class: 'list-row' });
    const unblock = el('button', { type: 'button' }, '차단 풀기');
    unblock.addEventListener('click', async () => {
      await api.post(`/api/members/${m.id}/block`);
      loadBlocked();
    });
    li.append(el('a', { href: '/members/' + m.id }, m.nickname), unblock);
    return li;
  }));
}
loadBlocked();

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
