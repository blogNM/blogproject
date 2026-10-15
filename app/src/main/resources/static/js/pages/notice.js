// 메인 공지사항 목록과 본문 (BRD-10). /notices, /notices/{id}
import { ApiError, api } from '../api.js';
import { el, initLayout, renderNotFound } from '../layout.js';
import { NOTICE_CATEGORIES, categoryBadge } from '../notice-category.js';
import { renderPager } from '../pager.js';
import { highlightCode } from '../code-highlight.js';

await initLayout();
const $ = (id) => document.getElementById(id);
const id = location.pathname.split('/')[2];

if (id) {
  try {
    const n = await api.get(`/api/notices/${encodeURIComponent(id)}`);
    document.title = `${n.title} - 공지사항 - Creat_`;
    $('title').textContent = n.title;
    $('category').replaceChildren(categoryBadge(n.category));
    $('meta').textContent = n.updatedAt && n.updatedAt !== n.createdAt ? `${n.createdAt} · 고침 ${n.updatedAt}` : n.createdAt;
    $('content').innerHTML = n.html;
    highlightCode($('content'));
    // 사용 방법 안내 공지면 다른 안내도 옆으로 넘겨 볼 수 있게 (D-104)
    if (n.category === 'NOTICE' && n.title.startsWith('사용 방법')) {
      const more = el('p', { class: 'guide-more' });
      more.append(el('a', { class: 'button', href: '/guides?id=' + encodeURIComponent(n.id) }, '다른 사용 방법 넘겨 보기'));
      $('content').after(more);
    }
    $('detail-view').hidden = false;
  } catch (err) {
    if (err instanceof ApiError && (err.status === 404 || err.status === 400)) renderNotFound('공지를 찾을 수 없어요. 지워졌을 수 있어요.');
    else {
      $('error').textContent = err.message;
      $('error').hidden = false;
    }
  }
} else {
  const query = new URLSearchParams(location.search);
  let page = Number(query.get('page')) || 1;
  let category = NOTICE_CATEGORIES.some(([value]) => value === query.get('category')) ? query.get('category') : '';
  const address = () => '/notices?' + new URLSearchParams({ ...(category && { category }), page }).toString();

  // 전체·일반·업데이트·수정·점검 탭 (D-98)
  const tabs = [['', '전체'], ...NOTICE_CATEGORIES].map(([value, label]) => {
    const tab = el('button', { type: 'button', class: 'tab', role: 'tab' }, label);
    tab.addEventListener('click', () => {
      category = value;
      page = 1;
      history.replaceState(null, '', address());
      load();
    });
    return [value, tab];
  });
  $('notice-tabs').replaceChildren(...tabs.map(([, tab]) => tab));

  const load = async () => {
    tabs.forEach(([value, tab]) => {
      tab.classList.toggle('active', value === category);
      tab.setAttribute('aria-selected', String(value === category));
    });
    try {
      const res = await api.get('/api/notices/all?page=' + page + (category ? '&category=' + category : ''));
      const list = $('notice-list');
      if (res.items.length === 0) list.replaceChildren(el('li', { class: 'muted' }, category ? '이 카테고리에는 아직 글이 없어요.' : '아직 공지가 없어요.'));
      else {
        list.replaceChildren(...res.items.map((n) => {
          const li = el('li');
          const title = el('span', { class: 'notice-title' });
          title.append(categoryBadge(n.category), el('a', { href: '/notices/' + n.id }, n.title));
          li.append(title, el('span', { class: 'muted' }, n.createdAt));
          return li;
        }));
      }
      renderPager($('pager'), res.page, res.totalPages, (p) => {
        page = p;
        history.replaceState(null, '', address());
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
