// 블로그 안 글 목록 (BRD-02·03·04). 사용자 입력(제목·닉네임·카테고리·태그)은 모두 textContent로 넣는다 (SEC-06).
import { api } from './api.js';
import { el } from './layout.js';
import { renderPager } from './pager.js';

const $ = (id) => document.getElementById(id);

export function postUrl(slug, id, key) {
  return `/blog/${encodeURIComponent(slug)}/posts/${id}` + (key ? '?key=' + encodeURIComponent(key) : '');
}

/**
 * 블로그 화면 아래의 글 목록. 카테고리·태그·페이지·개수는 주소(?category=&tag=&page=&size=)에 남긴다.
 * categoriesReady: 블로그 화면이 메뉴용으로 이미 부른 카테고리 요청. 같은 요청을 두 번 보내지 않는다.
 */
export async function initPostList(blog, key, categoriesReady) {
  const params = new URLSearchParams(location.search);
  const state = {
    page: Math.max(1, Number(params.get('page')) || 1),
    size: [10, 20, 30].includes(Number(params.get('size'))) ? Number(params.get('size')) : 10,
    category: params.get('category') || '',
    tag: params.get('tag') || '',
  };
  const base = `/api/blogs/${encodeURIComponent(blog.slug)}`;
  const keyParam = key ? { key } : {};

  if (blog.myRole) {
    $('write-link').href = `/blog/${encodeURIComponent(blog.slug)}/write`;
    $('write-link').hidden = false;
  }
  $('page-size').value = String(state.size);
  $('page-size').addEventListener('change', () => {
    state.size = Number($('page-size').value);
    state.page = 1;
    load();
  });

  const categoriesLoad = (categoriesReady || api.get(base + '/categories?' + new URLSearchParams(keyParam))).catch(() => []);
  let categories = [];

  function syncUrl() {
    const q = new URLSearchParams(keyParam);
    if (state.category) q.set('category', state.category);
    if (state.tag) q.set('tag', state.tag);
    if (state.page > 1) q.set('page', state.page);
    if (state.size !== 10) q.set('size', state.size);
    const qs = q.toString();
    history.replaceState(null, '', location.pathname + (qs ? '?' + qs : ''));
  }

  function renderCategories() {
    const nav = $('categories');
    nav.replaceChildren();
    const item = (label, value, count) => {
      const b = el('button', { type: 'button', class: 'category' + (state.category === value && !state.tag ? ' active' : '') },
        count === undefined ? label : `${label} (${count})`);
      b.addEventListener('click', () => {
        state.category = value;
        state.tag = '';
        state.page = 1;
        load();
      });
      return b;
    };
    nav.append(item('전체 글', ''));
    categories.forEach((c) => nav.append(item(c.name, String(c.id), c.postCount)));
  }

  function postItem(p) {
    const li = el('li', { class: 'post-item' + (p.notice ? ' notice-item' : '') });
    const head = el('div', { class: 'post-item-head' });
    if (p.notice) head.append(el('span', { class: 'badge' }, '공지'));
    if (p.categoryName) head.append(el('span', { class: 'post-category' }, p.categoryName));
    const title = el('a', { href: postUrl(blog.slug, p.id, key), class: 'post-title' }, p.title);
    // 제목에 마우스를 올리면 글의 첫 사진을 보여준다. 원본을 CSS로 줄여 보여준다 (US3-5).
    if (p.imageUrl) {
      const thumb = el('img', { src: p.imageUrl, alt: '', class: 'post-thumb', loading: 'lazy' });
      title.append(thumb);
    }
    head.append(title);
    li.append(head);
    if (p.tags.length) {
      const tags = el('ul', { class: 'tags' });
      p.tags.forEach((t) => {
        const a = el('button', { type: 'button', class: 'tag-button' }, '#' + t);
        a.addEventListener('click', () => {
          state.tag = t;
          state.category = '';
          state.page = 1;
          load();
        });
        const item = el('li');
        item.append(a);
        tags.append(item);
      });
      li.append(tags);
    }
    li.append(el('p', { class: 'muted' },
      `${p.authorName} · ${p.createdAt} · 조회 ${p.views} · 좋아요 ${p.likes} · 댓글 ${p.comments}`));
    return li;
  }

  function showPager(current, totalPages) {
    renderPager($('post-pager'), current, totalPages, (p) => { state.page = p; load(); });
  }

  async function load() {
    syncUrl();
    const q = new URLSearchParams({ ...keyParam, page: state.page, size: state.size });
    if (state.category) q.set('category', state.category);
    if (state.tag) q.set('tag', state.tag);
    // 글 목록과 카테고리를 함께 받는다 (처음 한 번만 카테고리를 기다리고, 그 뒤로는 받아 둔 값을 쓴다)
    const posts = api.get(base + '/posts?' + q);
    posts.catch(() => {});
    categories = await categoriesLoad;
    renderCategories();
    let res;
    try {
      res = await posts;
    } catch (err) {
      $('post-list').replaceChildren(el('li', { class: 'error' }, err.message));
      return;
    }
    const label = $('filter-label');
    if (state.tag) {
      label.replaceChildren(el('span', {}, `#${res.tag || state.tag} 태그 글 ${res.total}개 `));
      const clear = el('button', { type: 'button', class: 'link-button' }, '전체 글 보기');
      clear.addEventListener('click', () => { state.tag = ''; state.page = 1; load(); });
      label.append(clear);
      label.hidden = false;
    } else {
      label.hidden = true;
    }
    $('notices').replaceChildren(...res.notices.map(postItem));
    $('post-list').replaceChildren(...(res.items.length
      ? res.items.map(postItem)
      : [el('li', { class: 'muted empty-row' }, state.category || state.tag ? '이 조건에 맞는 글이 없습니다.' : '아직 글이 없습니다.')]));
    showPager(res.page, res.totalPages);
  }

  $('posts-section').hidden = false;
  await load();
}
