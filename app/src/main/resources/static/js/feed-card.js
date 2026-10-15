// 피드·검색의 글 카드. 사용자 입력은 모두 textContent로 넣는다 (SEC-06).
import { el } from './layout.js';
import { blogUrl } from './blog-card.js';
import { withFrom } from './visit.js';
import { withTestBadge } from './test-badge.js';

export function postHref(slug, id) {
  return blogUrl(slug) + '/posts/' + id;
}

/** from: 어디서 눌렀는지 블로그 통계에 남길 표시, q: 검색어 (D-112). */
export function feedCard(p, { from, q } = {}) {
  const li = el('li', { class: p.imageUrl ? 'feed-card' : 'feed-card no-image' });
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
  return li;
}

/** 번호 페이지 (D-76). */
export { renderPager } from './pager.js';
