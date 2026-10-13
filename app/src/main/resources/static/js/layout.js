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

function renderHeader(me) {
  const header = document.querySelector('header.site-header');
  if (!header) return;
  header.replaceChildren();

  const logo = wordmark();
  const nav = el('nav', { class: 'site-nav' });
  if (!document.body.classList.contains('no-header-search')) nav.append(headerSearch());

  if (me) {
    nav.append(el('a', { href: '/my-blogs.html' }, '내 블로그'));
    nav.append(el('a', { href: '/members/' + me.id }, '내 활동'));
    nav.append(el('a', { href: '/me.html', class: 'nickname' }, me.nickname + '님'));
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
  page.append(el('img', { src: '/img/lost.svg', alt: '', width: '180', height: '160' }),
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
