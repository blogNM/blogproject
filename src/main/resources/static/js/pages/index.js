import { api, ApiError } from '../api.js';
import { blogCard, blogUrl } from '../blog-card.js';
import { feedCard, renderPager } from '../feed-card.js';
import { el, initLayout } from '../layout.js';

const $ = (id) => document.getElementById(id);
const me = await initLayout();
if (me) {
  $('headline').textContent = me.nickname + '님, 오늘은 무엇을 만들까요?';
  $('welcome').textContent = '블로그를 만들고 테마 틀로 꾸며 보세요. 팔로우·구독한 글은 피드에서 모아 볼 수 있어요.';
  $('cta-main').textContent = '블로그 만들기';
  $('cta-main').href = '/blog-new.html';
}

// ----- 피드: 최신·인기·팔로우·구독 (BRD-09, D-65). 볼 때마다 새로 모아 온다 (D-73) -----
const feedState = { tab: 'latest', page: 1 };

async function loadFeed() {
  document.querySelectorAll('#feed-tabs .tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === feedState.tab));
  const list = $('feed-list');
  if (!me && (feedState.tab === 'following' || feedState.tab === 'subscribed')) {
    const box = el('li', { class: 'empty-box' });
    box.append(el('p', {}, '로그인하면 팔로우한 회원과 구독한 블로그의 새 글을 모아 볼 수 있어요.'),
      el('a', { href: '/login.html?next=/', class: 'button primary inline' }, '로그인'));
    list.replaceChildren(box);
    $('feed-pager').replaceChildren();
    return;
  }
  try {
    const res = await api.get(`/api/feed?tab=${feedState.tab}&page=${feedState.page}`);
    if (res.items.length === 0) {
      const empty = {
        latest: '아직 공개된 글이 없어요.',
        popular: '최근 7일 동안 좋아요를 받은 글이 없어요.',
        following: '팔로우한 회원의 새 글이 없어요.',
        subscribed: '구독한 블로그의 새 글이 없어요.',
      }[feedState.tab];
      list.replaceChildren(el('li', { class: 'empty-box' }, empty));
    } else {
      list.replaceChildren(...res.items.map(feedCard));
    }
    renderPager($('feed-pager'), res.page, res.totalPages, (p) => { feedState.page = p; loadFeed(); });
  } catch (e) {
    list.replaceChildren(el('li', { class: 'empty-box' }, e instanceof ApiError ? e.message : '피드를 불러오지 못했어요.'));
  }
}

document.querySelectorAll('#feed-tabs .tab').forEach((tab) => tab.addEventListener('click', () => {
  feedState.tab = tab.dataset.tab;
  feedState.page = 1;
  loadFeed();
}));

// ----- 공지사항 (BRD-10) -----
async function loadNotices() {
  const notices = await api.get('/api/notices').catch(() => []);
  const ul = $('notices');
  if (notices.length === 0) {
    ul.replaceChildren(el('li', { class: 'muted' }, '새 공지가 없어요.'));
    return;
  }
  ul.replaceChildren(...notices.map((n) => {
    const li = el('li');
    li.append(el('span', {}, n.title), el('span', { class: 'muted' }, ' · ' + n.createdAt));
    return li;
  }));
}

// ----- 블로그 둘러보기: 공개 블로그 최신순·인기순 (BLG-02) -----
const blogState = { sort: 'latest', page: 1 };

async function loadBlogs() {
  document.querySelectorAll('#blog-tabs .tab').forEach((t) => t.classList.toggle('active', t.dataset.sort === blogState.sort));
  const res = await api.get(`/api/blogs?sort=${blogState.sort}&page=${blogState.page}`);
  $('blog-list').replaceChildren(...res.items.map(blogCard));
  $('summary').textContent = res.total === 0 ? '아직 공개 블로그가 없어요. 첫 블로그를 만들어 보세요.' : `공개 블로그 ${res.total}개`;
  renderPager($('pager'), res.page, res.totalPages, (p) => { blogState.page = p; loadBlogs(); });
  if (blogState.sort === 'latest' && blogState.page === 1) {
    $('new-blogs').replaceChildren(...res.items.slice(0, 5).map((b) => {
      const li = el('li');
      li.append(el('a', { href: blogUrl(b.slug) }, b.name), el('span', { class: 'muted' }, ' · ' + b.ownerNickname));
      return li;
    }));
  }
}

document.querySelectorAll('#blog-tabs .tab').forEach((tab) => tab.addEventListener('click', () => {
  blogState.sort = tab.dataset.sort;
  blogState.page = 1;
  loadBlogs();
}));

await Promise.all([loadFeed(), loadNotices(), loadBlogs()]);
