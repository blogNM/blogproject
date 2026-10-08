// 모든 화면 위쪽 메뉴와 아래쪽 링크. 사용자 입력은 모두 textContent로 넣는다 (SEC-06).
import { api, startActivityHeartbeat } from './api.js';

export async function loadMe() {
  try {
    return await api.get('/api/me');
  } catch {
    return null;
  }
}

function el(tag, attrs = {}, text) {
  const node = document.createElement(tag);
  Object.entries(attrs).forEach(([k, v]) => node.setAttribute(k, v));
  if (text !== undefined) node.textContent = text;
  return node;
}

function renderHeader(me) {
  const header = document.querySelector('header.site-header');
  if (!header) return;
  header.replaceChildren();

  const logo = el('a', { href: '/', class: 'logo' }, '메인블로그');
  const nav = el('nav', { class: 'site-nav' });

  if (me) {
    nav.append(el('span', { class: 'nickname' }, me.nickname + '님'));
    const logout = el('button', { type: 'button', class: 'link-button' }, '로그아웃');
    logout.addEventListener('click', async () => {
      try {
        await api.post('/api/auth/logout');
      } finally {
        location.href = '/';
      }
    });
    nav.append(logout);
  } else {
    nav.append(el('a', { href: '/login.html' }, '로그인'));
    nav.append(el('a', { href: '/signup.html' }, '회원가입'));
  }
  header.append(logo, nav);
}

function renderFooter() {
  const footer = document.querySelector('footer.site-footer');
  if (!footer) return;
  footer.replaceChildren(
    el('a', { href: '/terms.html' }, '이용약관'),
    el('a', { href: '/privacy.html', class: 'strong' }, '개인정보 처리방침'),
    el('span', {}, '© 메인블로그'),
  );
}

/** 화면 위·아래를 그리고 로그인한 회원(없으면 null)을 돌려준다. */
export async function initLayout() {
  renderFooter();
  const me = await loadMe();
  renderHeader(me);
  if (me) startActivityHeartbeat();
  return me;
}
