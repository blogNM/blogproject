// 통합 검색 (BRD-08, 004 US6). 결과의 모든 값은 textContent로 넣는다.
import { api, ApiError } from '../api.js';
import { blogCard } from '../blog-card.js';
import { feedCard, renderPager } from '../feed-card.js';
import { el, initLayout, motionImage } from '../layout.js';

const $ = (id) => document.getElementById(id);
const me = await initLayout();
const params = new URLSearchParams(location.search);
const state = {
  q: params.get('q') || '',
  type: ['post', 'blog', 'tag', 'nickname'].includes(params.get('type')) ? params.get('type') : 'post',
  sort: ['relevance', 'latest', 'popular'].includes(params.get('sort')) ? params.get('sort') : 'relevance',
  size: ['10', '20', '30'].includes(params.get('size')) ? params.get('size') : '10',
  page: Math.max(1, Number(params.get('page')) || 1),
};
$('q').value = state.q;
$('sort').value = state.sort;
$('size').value = state.size;

const EMPTY = '검색 결과가 없어요.';
const TYPE_NAME = { post: '글 제목', blog: '블로그', tag: '태그', nickname: '닉네임' };

/** 화면 규칙도 서버와 같다: 2~20자, 글자나 숫자가 있어야 한다 (6.1). */
function checkQuery(q) {
  const length = [...q].length;
  if (length < 2 || length > 20) return '검색어는 2~20자로 넣어 주세요.';
  if (!/[\p{L}\p{N}]/u.test(q)) return '검색어에 글자나 숫자를 넣어 주세요.';
  return null;
}

async function search() {
  const problem = checkQuery(state.q);
  $('error').textContent = state.q ? problem || '' : '';
  if (!state.q || problem) {
    $('result').hidden = true;
    return;
  }
  const query = new URLSearchParams({ q: state.q, type: state.type, sort: state.sort, size: state.size, page: state.page });
  history.replaceState(null, '', '/search.html?' + query);
  try {
    const res = await api.get('/api/search?' + query);
    $('result').hidden = false;
    document.querySelectorAll('#type-tabs .tab').forEach((t) => {
      t.classList.toggle('active', t.dataset.type === state.type);
      t.querySelector('.count').textContent = String(res.counts[t.dataset.type.toUpperCase()] ?? 0);
    });
    const page = state.type === 'blog' ? res.blogs : res.posts;
    $('post-results').replaceChildren(...(state.type === 'blog' ? [] : res.posts.items.map((p) => feedCard(p, { from: 'search', q: state.q, me }))));
    $('blog-results').replaceChildren(...(state.type === 'blog' ? res.blogs.items.map((b) => blogCard(b, { from: 'search', q: state.q })) : []));
    $('summary').textContent = page.total === 0 ? '' : `${TYPE_NAME[state.type]}에서 "${state.q}" ${page.total}건`;
    showEmpty(page.total === 0);
    renderPager($('pager'), page.page, page.totalPages, (p) => { state.page = p; search(); });
  } catch (e) {
    $('error').textContent = e instanceof ApiError ? e.message : '검색하지 못했어요. 잠시 뒤에 다시 해 주세요.';
  }
}

/** 검색 결과가 없을 때 가운데 그림과 문구. 그림은 돋보기와 빈 상자 중 하나를 고른다 (D-105) */
const EMPTY_ARTS = [['/img/empty-search.svg', '/img/empty-search-still.svg'], ['/img/empty-box.svg', '/img/empty-box-still.svg']];
function showEmpty(empty) {
  const box = $('search-empty');
  box.hidden = !empty;
  if (empty) {
    const [src, still] = EMPTY_ARTS[Math.floor(Math.random() * EMPTY_ARTS.length)];
    box.replaceChildren(motionImage(src, still, { width: '200', height: '170' }), el('p', {}, EMPTY));
  }
}

// ----- 최근 검색어: 로그인한 회원 본인만, 최대 10개 -----
async function showRecent() {
  if (!me) return;
  const items = await api.get('/api/me/recent-searches').catch(() => []);
  const list = $('recent-list');
  list.replaceChildren(...items.map((r) => {
    const li = el('li');
    const word = el('button', { type: 'button', class: 'word' }, r.keyword);
    word.addEventListener('mousedown', (e) => e.preventDefault());
    word.addEventListener('click', () => { $('q').value = r.keyword; submit(); });
    const remove = el('button', { type: 'button', class: 'remove', 'aria-label': r.keyword + ' 지우기' }, '×');
    remove.addEventListener('mousedown', (e) => e.preventDefault());
    remove.addEventListener('click', async () => { await api.del('/api/me/recent-searches/' + r.id); showRecent(); });
    li.append(word, remove);
    return li;
  }));
  $('recent').hidden = items.length === 0;
}

$('q').addEventListener('focus', showRecent);
$('q').addEventListener('blur', () => { $('recent').hidden = true; });
$('recent-clear').addEventListener('mousedown', (e) => e.preventDefault());
$('recent-clear').addEventListener('click', async () => {
  await api.del('/api/me/recent-searches');
  $('recent').hidden = true;
});

function submit() {
  state.q = $('q').value.trim();
  state.page = 1;
  $('recent').hidden = true;
  $('q').blur();
  search();
}

$('search-form').addEventListener('submit', (e) => { e.preventDefault(); submit(); });
document.querySelectorAll('#type-tabs .tab').forEach((tab) => tab.addEventListener('click', () => {
  state.type = tab.dataset.type;
  state.page = 1;
  search();
}));
$('sort').addEventListener('change', () => { state.sort = $('sort').value; state.page = 1; search(); });
$('size').addEventListener('change', () => { state.size = $('size').value; state.page = 1; search(); });

await search();
if (!state.q) $('q').focus();
