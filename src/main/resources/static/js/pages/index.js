import { api } from '../api.js';
import { blogCard } from '../blog-card.js';
import { el, initLayout } from '../layout.js';

const me = await initLayout();
if (me) {
  document.getElementById('welcome').textContent = me.nickname + '님, 환영합니다.';
}

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const state = {
  sort: params.get('sort') === 'popular' ? 'popular' : 'latest',
  q: params.get('q') || '',
  page: Math.max(1, Number(params.get('page')) || 1),
};
$('q').value = state.q;

// 공개 블로그만 최신순·인기순으로, 번호 페이지로 보여준다 (BLG-02, BLG-03)
async function load() {
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.sort === state.sort));
  const query = new URLSearchParams({ sort: state.sort, page: state.page });
  if (state.q) query.set('q', state.q);
  history.replaceState(null, '', '/?' + query);

  const res = await api.get('/api/blogs?' + query);
  const list = $('blog-list');
  list.replaceChildren(...res.items.map(blogCard));
  if (res.total === 0) {
    $('summary').textContent = state.q ? `"${state.q}" 검색 결과가 없습니다.` : '아직 공개 블로그가 없습니다. 첫 블로그를 만들어 보세요.';
  } else {
    $('summary').textContent = state.q ? `"${state.q}" 검색 결과 ${res.total}개` : `공개 블로그 ${res.total}개`;
  }
  renderPager(res.page, res.totalPages);
}

function renderPager(current, total) {
  const pager = $('pager');
  pager.replaceChildren();
  if (total <= 1) return;
  for (let p = 1; p <= total; p += 1) {
    const b = el('button', { type: 'button', class: p === current ? 'page active' : 'page' }, String(p));
    if (p === current) b.setAttribute('aria-current', 'page');
    b.addEventListener('click', () => { state.page = p; load(); });
    pager.append(b);
  }
}

document.querySelectorAll('.tab').forEach((tab) => tab.addEventListener('click', () => {
  state.sort = tab.dataset.sort;
  state.page = 1;
  load();
}));

$('search-form').addEventListener('submit', (e) => {
  e.preventDefault();
  state.q = $('q').value.trim();
  state.page = 1;
  load();
});

await load();
