// 모든 화면 위쪽 메뉴와 아래쪽 링크. 사용자 입력은 모두 textContent로 넣는다 (SEC-06).
import { api, startActivityHeartbeat } from './api.js';
import { mountBell } from './notifications.js';

export async function loadMe() {
  try {
    return await api.get('/api/me');
  } catch {
    return null;
  }
}

/** 요소를 만든다. 글자는 항상 textContent로 넣는다. */
export function el(tag, attrs = {}, text) {
  const node = document.createElement(tag);
  Object.entries(attrs).forEach(([k, v]) => node.setAttribute(k, v));
  if (text !== undefined) node.textContent = text;
  return node;
}

/**
 * 움직이는 그림(/img의 정적 SVG). 동작 줄이기를 켠 사람에게는 멈춘 그림을 보여 준다 (D-105).
 */
export function motionImage(src, stillSrc, attrs = {}) {
  const picture = el('picture');
  picture.append(el('source', { srcset: stillSrc, media: '(prefers-reduced-motion: reduce)' }),
    el('img', { src, alt: '', ...attrs }));
  return picture;
}

/** 사이트 이름 Creat_ 워드마크. 밑줄만 복숭아색으로 깜빡인다. */
export function wordmark(light = false) {
  const a = el('a', { href: '/', class: light ? 'wordmark light' : 'wordmark', 'aria-label': 'Creat_ 메인으로' });
  a.append(el('span', { class: 'word' }, 'Creat'), el('span', { class: 'cursor', 'aria-hidden': 'true' }, '_'));
  return a;
}

/** 위쪽 검색창. 통합 검색 화면으로 간다 (BRD-08). */
function headerSearch() {
  const form = el('form', { class: 'header-search', role: 'search', action: '/search.html' });
  const input = el('input', { type: 'search', name: 'q', placeholder: '블로그·글·태그·닉네임 검색', 'aria-label': '통합 검색',
    minlength: '2', maxlength: '20' });
  form.append(input);
  return form;
}

const MODE_KEY = 'creat-color-mode';
const MOON = 'M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z';
const SUN = 'M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zm0-5v3m0 14v3M4.2 4.2l2.1 2.1m11.4 11.4 2.1 2.1M2 12h3m14 0h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1';

function modeIcon(dark) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');
  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', dark ? SUN : MOON);
  path.setAttribute('fill', dark ? 'none' : 'currentColor');
  path.setAttribute('stroke', 'currentColor');
  path.setAttribute('stroke-width', dark ? '2' : '0');
  path.setAttribute('stroke-linecap', 'round');
  svg.append(path);
  return svg;
}

/**
 * 다크모드 켜고 끄기 (D-116). 값은 이 브라우저에만 남긴다.
 * dark.css를 읽는 화면(color-mode.js가 data-mode-ready를 붙임)에만 버튼을 둔다. 블로그 홈·글쓰기·관리자 화면은 늘 밝다.
 */
function modeToggle() {
  const root = document.documentElement;
  if (!root.hasAttribute('data-mode-ready')) return null;
  const button = el('button', { type: 'button', class: 'mode-toggle' });
  const paint = () => {
    const dark = root.getAttribute('data-mode') === 'dark';
    button.setAttribute('aria-pressed', String(dark));
    button.setAttribute('aria-label', dark ? '다크모드 끄기' : '다크모드 켜기');
    button.title = dark ? '다크모드 끄기' : '다크모드 켜기';
    button.replaceChildren(modeIcon(dark));
  };
  button.addEventListener('click', () => {
    const dark = root.getAttribute('data-mode') !== 'dark';
    if (dark) root.setAttribute('data-mode', 'dark'); else root.removeAttribute('data-mode');
    try { localStorage.setItem(MODE_KEY, dark ? 'dark' : 'light'); } catch { /* 이번 화면에만 적용 */ }
    paint();
  });
  paint();
  return button;
}

function renderHeader(me) {
  const header = document.querySelector('header.site-header');
  if (!header) return;
  header.replaceChildren();

  const logo = wordmark();
  const nav = el('nav', { class: 'site-nav' });
  if (!document.body.classList.contains('no-header-search')) nav.append(headerSearch());
  const toggle = modeToggle();
  if (toggle) nav.append(toggle);

  if (me) {
    nav.append(el('a', { href: '/my-blogs.html' }, '내 블로그'));
    nav.append(el('a', { href: '/members/' + me.id }, '내 활동'));
    const nick = el('a', { href: '/me.html', class: 'nickname' }, me.nickname + '님');
    // 테스트 회원으로 들어왔으면 닉네임 옆에 "테스트" 표시 (D-113). 표시 모듈이 이 파일을 쓰므로 여기서 직접 만든다
    if (me.test) nick.append(el('span', { class: 'test-badge', title: '기능을 확인하려고 만든 테스트 계정이에요' }, '테스트'));
    nav.append(nick);
    const logout = el('button', { type: 'button', class: 'link-button' }, '로그아웃');
    logout.addEventListener('click', async () => {
      try {
        await api.post('/api/auth/logout');
      } finally {
        location.href = '/';
      }
    });
    nav.append(logout);
    mountBell(nav);
  } else {
    nav.append(el('a', { href: '/login.html' }, '로그인'));
    nav.append(el('a', { href: '/signup.html', class: 'cta' }, '무료로 시작하기'));
  }
  header.append(logo, nav);
}

function footerColumn(title, links) {
  const col = el('div');
  col.append(el('h4', {}, title));
  const ul = el('ul');
  links.forEach(([href, text]) => {
    const li = el('li');
    li.append(el('a', { href }, text));
    ul.append(li);
  });
  col.append(ul);
  return col;
}

function renderFooter(me) {
  const footer = document.querySelector('footer.site-footer');
  if (!footer) return;
  const brand = el('div');
  brand.append(wordmark(), el('p', { class: 'muted' }, '블로그를 만들고 함께 쓰는 곳'));
  const inner = el('div', { class: 'footer-inner' });
  inner.append(brand,
    footerColumn('둘러보기', [['/', '메인 피드'], ['/search.html', '통합 검색'], ['/blog-new.html', '블로그 만들기']]),
    footerColumn('내 활동', [['/my-blogs.html', '내 블로그'], ...(me ? [['/members/' + me.id, '내 활동']] : []),
      ['/me.html', '내 정보']]),
    footerColumn('도움말', [['/guides', '사용 방법'], ['/find-email.html', '이메일 찾기'], ['/forgot-password.html', '비밀번호 찾기']]));
  const bottom = el('div', { class: 'footer-bottom' });
  bottom.append(el('span', {}, '© Creat_'), el('a', { href: '/terms.html' }, '이용약관'),
    el('a', { href: '/privacy.html', class: 'strong' }, '개인정보 처리방침'));
  footer.replaceChildren(inner, bottom);
}

/** 화면 아래에 잠깐 떴다 사라지는 안내. */
export function toast(message) {
  const t = el('div', { class: 'toast', role: 'status' }, message);
  document.body.append(t);
  setTimeout(() => t.remove(), 2200);
}

/**
 * 찾을 수 없는 블로그·글. 에러 화면과 같은 모양으로 바꿔 그린다 (404.html과 같은 문구).
 * 그림은 /img의 정적 SVG라 사용자 입력이 들어가지 않는다.
 */
export function renderNotFound(message) {
  const main = document.querySelector('main');
  const page = el('section', { class: 'error-page' });
  page.append(motionImage('/img/lost.svg', '/img/lost-still.svg', { width: '180', height: '160' }),
    el('p', { class: 'code' }, '404'),
    el('h1', {}, '앗, 길이 막혔어요!'),
    el('p', {}, message || '찾는 페이지가 없거나 볼 수 없는 페이지예요.'));
  const back = el('a', { href: '/', class: 'button primary' }, '처음으로 돌아가기');
  page.append(back);
  main.replaceChildren(page);
  main.className = 'error-main';
}

/** 비밀번호 칸마다 눌러서 입력한 값을 보거나 다시 가리는 버튼을 붙인다. */
export function addPasswordToggles() {
  document.querySelectorAll('input[type=password]').forEach((input) => {
    const wrapper = el('div', { class: 'password-field' });
    input.replaceWith(wrapper);
    const toggle = el('button', {
      type: 'button',
      class: 'password-toggle',
      'aria-label': '비밀번호 보기',
      'aria-pressed': 'false',
    }, '보기');
    toggle.addEventListener('click', () => {
      const show = input.type === 'password';
      input.type = show ? 'text' : 'password';
      toggle.textContent = show ? '숨기기' : '보기';
      toggle.setAttribute('aria-label', show ? '비밀번호 숨기기' : '비밀번호 보기');
      toggle.setAttribute('aria-pressed', String(show));
      input.focus();
    });
    wrapper.append(input, toggle);
  });
}

/** 화면 위·아래를 그리고 로그인한 회원(없으면 null)을 돌려준다. */
export async function initLayout() {
  renderFooter();
  addPasswordToggles();
  const me = await loadMe();
  renderHeader(me);
  if (me) {
    renderFooter(me);
    startActivityHeartbeat();
  }
  return me;
}

/** 로그인해야 쓰는 화면. 로그인하지 않았으면 로그인 화면으로 보내고, 로그인 뒤 이 화면으로 돌아온다. */
export async function requireLogin() {
  const me = await initLayout();
  if (!me) {
    location.replace('/login.html?next=' + encodeURIComponent(location.pathname + location.search));
    return new Promise(() => {});
  }
  return me;
}
