// 관리자 화면 (005 ADM). 탭은 주소의 # 로 고른다 (#stats, #reports …).
import { api } from '../api.js';
import { addPasswordToggles } from '../layout.js';
import { PASSWORD_MESSAGE, PASSWORD_RULE } from '../rules.js';
import { el, handleAuthError, showError } from './common.js';
import * as stats from './stats.js';
import * as reports from './reports.js';
import * as members from './members.js';
import * as blogs from './blogs.js';
import * as content from './content.js';
import * as logs from './logs.js';
import * as admins from './admins.js';
import * as notices from './notices.js';

const $ = (id) => document.getElementById(id);

const TABS = [
  ['stats', '통계', stats],
  ['reports', '신고', reports],
  ['members', '회원', members],
  ['blogs', '블로그', blogs],
  ['content', '글·댓글', content],
  ['notices', '공지', notices],
  ['logs', '활동 기록', logs],
  ['admins', '관리자', admins],
];

let pendingBadge;
let me;
try {
  me = await api.get('/api/admin/auth/me');
} catch (err) {
  if (!handleAuthError(err)) {
    $('loading').textContent = err.message;
  }
}

if (me) {
  $('loading').hidden = true;
  $('user-name').textContent = `${me.name} (${me.loginId})`;
  $('user').hidden = false;
  if (me.mustChangePassword) showPasswordForm();
  else startApp();
}

$('logout').addEventListener('click', async () => {
  try {
    await api.post('/api/admin/auth/logout');
  } catch {
    // 이미 풀렸어도 로그인 화면으로 간다
  }
  location.replace('/admin/login.html');
});

// ----- 임시 비밀번호 바꾸기 (추천안 #16) -----
function showPasswordForm() {
  $('password-panel').hidden = false;
  addPasswordToggles();
  $('password-rule').textContent = PASSWORD_MESSAGE;
  const form = $('password-form');
  const errorBox = $('password-error');
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    errorBox.textContent = '';
    const currentPassword = form.currentPassword.value;
    const newPassword = form.newPassword.value;
    if (!currentPassword) {
      errorBox.textContent = '지금 비밀번호를 넣어 주세요.';
      return;
    }
    if (!PASSWORD_RULE.test(newPassword)) {
      errorBox.textContent = PASSWORD_MESSAGE;
      return;
    }
    if (newPassword !== form.newPassword2.value) {
      errorBox.textContent = '새 비밀번호가 서로 다릅니다.';
      return;
    }
    const b = form.querySelector('button[type=submit]');
    b.disabled = true;
    try {
      await api.post('/api/admin/auth/password', { currentPassword, newPassword });
      location.reload();
    } catch (err) {
      showError(err, errorBox);
      b.disabled = false;
    }
  });
}

// ----- 탭 -----

function renderTabs(current) {
  $('tabs').replaceChildren(...TABS.map(([key, name]) => {
    const a = el('a', { href: '#' + key, class: key === current ? 'adm-tab active' : 'adm-tab' }, name);
    if (key === current) a.setAttribute('aria-current', 'page');
    if (key === 'reports') {
      pendingBadge = el('span', { class: 'adm-count' });
      pendingBadge.hidden = true;
      a.append(pendingBadge);
    }
    return a;
  }));
}

/** 처리할 신고 수를 탭에 붙인다. */
export function setPendingCount(n) {
  if (!pendingBadge) return;
  pendingBadge.textContent = String(n);
  pendingBadge.hidden = !n;
}

async function refreshPendingCount() {
  try {
    const s = await api.get('/api/admin/stats');
    setPendingCount(s.pendingReports);
  } catch (err) {
    handleAuthError(err);
  }
}

function currentTab() {
  const key = location.hash.replace('#', '');
  return TABS.find(([k]) => k === key) || TABS[0];
}

async function show() {
  const [key, name, mod] = currentTab();
  renderTabs(key);
  document.title = `${name} - Creat_ 관리자`;
  const panel = $('panel');
  panel.replaceChildren(el('p', { class: 'adm-loading' }, '불러오는 중…'));
  await mod.mount(panel, { onReportsChanged: refreshPendingCount, setPendingCount });
}

function startApp() {
  $('app').hidden = false;
  window.addEventListener('hashchange', show);
  show();
  refreshPendingCount();
}
