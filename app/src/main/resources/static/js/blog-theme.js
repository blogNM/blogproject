// 블로그 첫 화면의 테마 틀별 칸 (D-89). 글 제목·요약·카테고리 같은 사용자 입력은 모두 textContent로 넣는다 (SEC-06).
import { el } from './layout.js';

/** 글 사진. 사진이 없는 글은 강조 색 그라데이션 칸으로 채운다. */
function thumb(p, cls = 'thumb') {
  if (p.imageUrl) return el('img', { src: p.imageUrl, alt: '', class: cls, loading: 'lazy' });
  const ph = el('div', { class: cls + ' ph', 'aria-hidden': 'true' });
  ph.append(el('span', {}, (p.categoryName || p.title || '').slice(0, 1)));
  return ph;
}

function section(title, cls, more) {
  const s = el('section', { class: 'sc ' + cls });
  const head = el('div', { class: 'sc-head' });
  head.append(el('h2', { class: 'sc-title' }, title));
  if (more) head.append(el('a', { href: '#all-posts', class: 'sc-more' }, more));
  s.append(head);
  return s;
}

function meta(p, withAuthor = false) {
  const parts = [];
  if (p.categoryName) parts.push(p.categoryName);
  if (withAuthor) parts.push(p.authorName);
  parts.push(p.createdAt.slice(0, 10));
  return el('p', { class: 'sc-meta' }, parts.join(' · '));
}

function card(p, href, { image = true, excerpt = true, author = false, cls = 'sc-card', button } = {}) {
  const a = el('a', { href: href(p), class: cls });
  if (image) a.append(thumb(p));
  const body = el('div', { class: 'sc-body' });
  if (p.categoryName) body.append(el('p', { class: 'sc-cat' }, p.categoryName));
  body.append(el('h3', { class: 'sc-card-title' }, p.title));
  if (excerpt && p.excerpt) body.append(el('p', { class: 'sc-excerpt' }, p.excerpt));
  body.append(meta({ ...p, categoryName: null }, author));
  if (button) body.append(el('span', { class: 'sc-button' }, button));
  a.append(body);
  return a;
}

const byLikes = (a, b) => b.likes - a.likes || b.views - a.views || b.id - a.id;
const withImage = (posts) => posts.filter((p) => p.imageUrl);

function grid(cls, items) {
  const g = el('div', { class: cls });
  g.append(...items);
  return g;
}

const RENDER = {
  ODYSSEY(posts, href) {
    const out = [];
    const top = [...posts].sort(byLikes)[0];
    if (top) {
      const s = section('인기글', 'od-popular');
      s.append(card(top, href, { cls: 'sc-card big' }));
      out.push(s);
    }
    const recent = posts.filter((p) => p !== top).slice(0, 6);
    if (recent.length) {
      const s = section('최근글', 'od-recent', 'more ›');
      s.append(grid('sc-list right-thumb', recent.map((p) => card(p, href, { cls: 'sc-row' }))));
      out.push(s);
    }
    return out;
  },
  POSTER(posts, href) {
    const out = [];
    const strip = section('최근 올라온 글', 'po-strip');
    strip.append(grid('sc-grid four', posts.slice(0, 4).map((p) => card(p, href, { excerpt: false }))));
    out.push(strip);
    const popular = [...posts].sort(byLikes).slice(0, 7);
    if (popular.length) {
      const s = section('인기글', 'po-popular');
      s.append(grid('sc-masonry', popular.map((p) => card(p, href))));
      const more = el('a', { href: '#all-posts', class: 'sc-outline' }, 'VIEW MORE');
      s.append(more);
      out.push(s);
    }
    return out;
  },
  WHATEVER(posts, href, blog) {
    const out = [];
    const intro = section('', 'wh-intro');
    const text = el('div', { class: 'wh-intro-text' });
    text.append(el('p', { class: 'sc-cat' }, '소개'), el('h2', {}, blog.name));
    intro.replaceChildren(text, grid('sc-grid three', posts.slice(0, 3).map((p) => card(p, href, { excerpt: false }))));
    out.push(intro);
    const tiles = section('', 'wh-checker');
    const picks = posts.slice(3, 7).length ? posts.slice(3, 7) : posts.slice(0, 4);
    picks.forEach((p, i) => {
      const row = el('a', { href: href(p), class: 'wh-tile' + (i % 2 ? ' flip' : '') });
      const t = el('div', { class: 'wh-text' });
      if (p.categoryName) t.append(el('p', { class: 'sc-cat' }, p.categoryName));
      t.append(el('h3', {}, p.title), el('span', { class: 'wh-rule', 'aria-hidden': 'true' }));
      if (p.excerpt) t.append(el('p', { class: 'sc-excerpt' }, p.excerpt));
      t.append(el('span', { class: 'sc-button ghost' }, '더보기 →'));
      row.append(thumb(p, 'wh-img'), t);
      tiles.append(row);
    });
    tiles.querySelector('.sc-head')?.remove();
    intro.querySelector('.sc-head')?.remove();
    out.push(tiles);
    return out;
  },
  LETTER(posts, href) {
    const out = [];
    const [first, ...rest] = posts;
    if (first) {
      const s = section('', 'le-featured');
      s.querySelector('.sc-head').remove();
      const a = el('a', { href: href(first), class: 'le-hero-post' });
      a.append(el('h2', {}, first.title), meta(first), thumb(first, 'le-img'));
      if (first.excerpt) a.append(el('p', { class: 'sc-excerpt' }, first.excerpt));
      a.append(el('span', { class: 'sc-button' }, 'Read More'));
      s.append(a);
      out.push(s);
    }
    if (rest.length) {
      const s = section('', 'le-cards');
      s.querySelector('.sc-head').remove();
      s.append(grid('sc-grid two', rest.slice(0, 4).map((p) => card(p, href, { button: 'Read More' }))));
      s.append(el('a', { href: '#all-posts', class: 'sc-outline wide' }, 'MORE POST'));
      out.push(s);
    }
    return out;
  },
  BOOK_CLUB(posts, href) {
    const out = [];
    const best = [...posts].sort(byLikes).slice(0, 3);
    if (best.length) {
      const s = section('베스트', 'bc-best', 'more');
      s.append(grid('sc-grid three covers', best.map((p) => card(p, href, { excerpt: false }))));
      out.push(s);
    }
    const recent = posts.slice(0, 5);
    if (recent.length) {
      const s = section('이번 달의 글', 'bc-new');
      s.append(grid('sc-list right-thumb tall', recent.map((p) => card(p, href, { cls: 'sc-row' }))));
      s.append(el('a', { href: '#all-posts', class: 'sc-outline wide' }, 'more'));
      out.push(s);
    }
    return out;
  },
  MAGAZINE(posts, href) {
    const out = [];
    const hot = section('Hot Issue', 'mg-hot');
    hot.append(grid('sc-grid four scroll', posts.slice(0, 4).map((p) => card(p, href, { excerpt: false }))));
    out.push(hot);
    const trend = [...posts].sort(byLikes).slice(0, 2);
    if (trend.length) {
      const s = section('Trend', 'mg-trend');
      s.append(grid('sc-grid two', trend.map((p) => card(p, href))));
      out.push(s);
    }
    const news = posts.slice(4, 8);
    if (news.length) {
      const s = section('News', 'mg-news');
      s.append(grid('sc-list left-thumb', news.map((p) => card(p, href, { cls: 'sc-row' }))));
      out.push(s);
    }
    const photos = withImage(posts).slice(0, 8);
    if (photos.length) {
      const s = section('Photo Gallery', 'mg-gallery');
      s.append(grid('sc-grid four', photos.map((p) => card(p, href, { excerpt: false, cls: 'sc-photo' }))));
      out.push(s);
    }
    return out;
  },
  PORTFOLIO(posts, href) {
    const out = [];
    const shots = (withImage(posts).length >= 4 ? withImage(posts) : posts).slice(0, 8);
    const s = section('Portfolio', 'pf-grid');
    s.append(grid('sc-grid four tall', shots.map((p) => card(p, href, { excerpt: false, cls: 'sc-photo' }))));
    out.push(s);
    const popular = [...posts].sort(byLikes).slice(0, 4);
    if (popular.length) {
      const p = section('Most Popular', 'pf-popular');
      p.append(grid('sc-list left-thumb', popular.map((x) => card(x, href, { cls: 'sc-row' }))));
      out.push(p);
    }
    return out;
  },
};

/** 첫 화면의 테마 칸을 그린다. 글이 없으면 아무것도 그리지 않는다. */
export function renderShowcase(container, themeKey, posts, blog, href) {
  container.replaceChildren();
  if (!posts.length) return;
  const render = RENDER[themeKey] || RENDER.ODYSSEY;
  container.append(...render(posts, href, blog));
}

/** 사이드바(Odyssey·Book Club)의 최근 글 목록. */
export function renderSideRecent(container, posts, notices, href) {
  container.replaceChildren();
  if (notices.length) {
    const box = el('div', { class: 'side-box' });
    box.append(el('h3', {}, '공지사항'));
    const ul = el('ul');
    notices.slice(0, 3).forEach((p) => {
      const li = el('li');
      li.append(el('a', { href: href(p) }, p.title));
      ul.append(li);
    });
    box.append(ul);
    container.append(box);
  }
  if (posts.length) {
    const box = el('div', { class: 'side-box' });
    box.append(el('h3', {}, '최근 글'));
    const ul = el('ul', { class: 'side-thumbs' });
    posts.slice(0, 5).forEach((p) => {
      const li = el('li');
      const a = el('a', { href: href(p) });
      const text = el('span');
      text.append(el('span', { class: 'side-title' }, p.title), el('span', { class: 'muted' }, p.createdAt.slice(0, 10)));
      a.append(thumb(p, 'side-thumb'), text);
      li.append(a);
      ul.append(li);
    });
    box.append(ul);
    container.append(box);
  }
}
