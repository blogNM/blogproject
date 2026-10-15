// 피드·검색의 글 카드. 사용자 입력은 모두 textContent로 넣는다 (SEC-06).
import { api } from './api.js';
import { el, toast } from './layout.js';
import { blogUrl } from './blog-card.js';
import { withFrom } from './visit.js';
import { withTestBadge } from './test-badge.js';

export function postHref(slug, id) {
  return blogUrl(slug) + '/posts/' + id;
}

/** from: 어디서 눌렀는지 블로그 통계에 남길 표시, q: 검색어 (D-112). me: 로그인한 회원이면 ⋮ 메뉴(관심 없음)를 단다 (D-123). */
export function feedCard(p, { from, q, me } = {}) {
  const li = el('li', { class: p.imageUrl ? 'feed-card' : 'feed-card no-image' });
  li.dataset.blog = p.blogSlug;
  if (p.authorId) li.dataset.author = String(p.authorId);
  const body = el('div');
  const blog = el('a', { href: withFrom(blogUrl(p.blogSlug), from, q), class: 'blog-name' }, p.blogName);
  // 블로그마다 고른 강조 색. #RRGGBB만 서버가 받으므로 CSS 변수로만 넣는다 (D-89).
  if (/^#[0-9A-Fa-f]{6}$/.test(p.accent || '')) blog.style.setProperty('color', p.accent);
  const h3 = el('h3');
  h3.append(el('a', { href: withFrom(postHref(p.blogSlug, p.id), from, q) }, p.title));
  body.append(blog, h3);
  if (p.excerpt) body.append(el('p', { class: 'excerpt' }, p.excerpt));
  const meta = el('div', { class: 'meta' });
  // 작성자를 누르면 프로필로 간다. 탈퇴한 회원·블로그를 나간 작성자는 글자만.
  const author = p.authorId ? el('a', { href: '/members/' + p.authorId, class: 'author-link' }, p.authorName)
    : el('span', {}, p.authorName);
  meta.append(withTestBadge(author, p.authorTest), el('span', {}, p.createdAt),
    el('span', {}, '♥ ' + p.likes), el('span', {}, '댓글 ' + p.comments), el('span', {}, '조회 ' + p.views));
  body.append(meta);
  li.append(body);
  if (p.imageUrl) li.append(el('img', { src: p.imageUrl, alt: '', loading: 'lazy' }));
  if (me) li.append(muteMenu(p, me));
  return li;
}

// ----- 관심 없음 ⋮ 메뉴 (SOC-08, D-123) -----

let openMenu = null;

function closeMenu() {
  if (!openMenu) return;
  openMenu.menu.hidden = true;
  openMenu.button.setAttribute('aria-expanded', 'false');
  openMenu = null;
}

document.addEventListener('click', (e) => {
  if (openMenu && !openMenu.wrap.contains(e.target)) closeMenu();
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && openMenu) {
    const { button } = openMenu;
    closeMenu();
    button.focus();
  }
});

/** 숨기면 같은 목록에서 그 회원의 글, 그 블로그의 글을 모두 빼고 알려 준다. 나머지는 다시 불러올 때 서버가 뺀다. */
function muteMenu(p, me) {
  const wrap = el('div', { class: 'card-menu' });
  const button = el('button', { type: 'button', class: 'card-menu-button', 'aria-label': '더 보기', 'aria-haspopup': 'menu', 'aria-expanded': 'false' }, '⋮');
  const menu = el('div', { class: 'card-menu-list', role: 'menu' });
  menu.hidden = true;
  const item = (label, url, selector, done) => {
    const b = el('button', { type: 'button', role: 'menuitem', class: 'card-menu-item' }, label);
    b.addEventListener('click', async () => {
      closeMenu();
      try {
        await api.post(url);
        const list = wrap.closest('ul, ol') || document;
        list.querySelectorAll(selector).forEach((card) => card.remove());
        toast(done);
      } catch (err) {
        toast(err.message);
      }
    });
    return b;
  };
  if (p.authorId && p.authorId !== me.id) {
    menu.append(item(`이 회원 관심 없음 (${p.authorName})`, `/api/members/${p.authorId}/mute`,
      `.feed-card[data-author="${p.authorId}"]`, '이 회원의 글을 피드와 검색에서 숨겼어요. 내 정보에서 풀 수 있어요.'));
  }
  menu.append(item(`이 블로그 관심 없음 (${p.blogName})`, `/api/blogs/${encodeURIComponent(p.blogSlug)}/mute`,
    `.feed-card[data-blog="${CSS.escape(p.blogSlug)}"]`, '이 블로그를 피드와 검색에서 숨겼어요. 내 정보에서 풀 수 있어요.'));
  button.addEventListener('click', (e) => {
    e.stopPropagation();
    const wasOpen = openMenu && openMenu.wrap === wrap;
    closeMenu();
    if (wasOpen) return;
    menu.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    openMenu = { wrap, menu, button };
    if (e.detail === 0) menu.querySelector('button').focus();
  });
  wrap.append(button, menu);
  return wrap;
}

/** 번호 페이지 (D-76). */
export { renderPager } from './pager.js';
