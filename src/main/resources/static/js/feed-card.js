// 피드·검색의 글 카드. 사용자 입력은 모두 textContent로 넣는다 (SEC-06).
import { el } from './layout.js';
import { blogUrl } from './blog-card.js';

export function postHref(slug, id) {
  return blogUrl(slug) + '/posts/' + id;
}

export function feedCard(p) {
  const li = el('li', { class: p.imageUrl ? 'feed-card' : 'feed-card no-image' });
  const body = el('div');
  const blog = el('a', { href: blogUrl(p.blogSlug), class: 'blog-name' }, p.blogName);
  // 블로그마다 고른 강조 색. #RRGGBB만 서버가 받으므로 CSS 변수로만 넣는다 (D-89).
  if (/^#[0-9A-Fa-f]{6}$/.test(p.accent || '')) blog.style.setProperty('color', p.accent);
  const h3 = el('h3');
  h3.append(el('a', { href: postHref(p.blogSlug, p.id) }, p.title));
  body.append(blog, h3);
  if (p.excerpt) body.append(el('p', { class: 'excerpt' }, p.excerpt));
  const meta = el('div', { class: 'meta' });
  meta.append(el('span', {}, p.authorName), el('span', {}, p.createdAt),
    el('span', {}, '♥ ' + p.likes), el('span', {}, '댓글 ' + p.comments), el('span', {}, '조회 ' + p.views));
  body.append(meta);
  li.append(body);
  if (p.imageUrl) li.append(el('img', { src: p.imageUrl, alt: '', loading: 'lazy' }));
  return li;
}

/** 번호 페이지 (D-76). */
export { renderPager } from './pager.js';
