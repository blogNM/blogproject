// 메인 공지사항 목록과 본문 (BRD-10). /notices, /notices/{id}
import { ApiError, api } from '../api.js';
import { el, initLayout, renderNotFound } from '../layout.js';
import { renderPager } from '../pager.js';

await initLayout();
const $ = (id) => document.getElementById(id);
const id = location.pathname.split('/')[2];

if (id) {
  try {
    const n = await api.get(`/api/notices/${encodeURIComponent(id)}`);
    document.title = `${n.title} - 공지사항 - Creat_`;
    $('title').textContent = n.title;
    $('meta').textContent = n.updatedAt && n.updatedAt !== n.createdAt ? `${n.createdAt} · 고침 ${n.updatedAt}` : n.createdAt;
    $('content').innerHTML = n.html;
    $('detail-view').hidden = false;
  } catch (err) {
    if (err instanceof ApiError && (err.status === 404 || err.status === 400)) renderNotFound('공지를 찾을 수 없어요. 지워졌을 수 있어요.');
    else {
      $('error').textContent = err.message;
      $('error').hidden = false;
    }
  }
} else {
  let page = Number(new URLSearchParams(location.search).get('page')) || 1;
  const load = async () => {
    try {
      const res = await api.get('/api/notices/all?page=' + page);
      const list = $('notice-list');
      if (res.items.length === 0) list.replaceChildren(el('li', { class: 'muted' }, '아직 공지가 없어요.'));
      else {
        list.replaceChildren(...res.items.map((n) => {
          const li = el('li');
          li.append(el('a', { href: '/notices/' + n.id }, n.title), el('span', { class: 'muted' }, n.createdAt));
          return li;
        }));
      }
      renderPager($('pager'), res.page, res.totalPages, (p) => {
        page = p;
        history.replaceState(null, '', '/notices?page=' + p);
        load();
      });
      $('list-view').hidden = false;
    } catch (err) {
      $('error').textContent = err.message;
      $('error').hidden = false;
    }
  };
  await load();
}
