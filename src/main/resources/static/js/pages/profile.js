// 회원 프로필과 [내 활동] (SOC-03, SOC-07, D-91).
// 본인이 보면 목록마다 "다른 사람에게 보여주기" 체크가 있다 (기본 켜짐).
// 닉네임·소개·블로그 이름 등 사용자 입력은 모두 textContent (SEC-06).
import { api } from '../api.js';
import { blogUrl } from '../blog-card.js';
import { feedCard, renderPager } from '../feed-card.js';
import { el, initLayout, renderNotFound, toast } from '../layout.js';
import { openReport } from '../report.js';

const me = await initLayout();
const $ = (id) => document.getElementById(id);
const memberId = Number(location.pathname.split('/')[2]);

/** key: 주소의 ?tab= 값. setting: 공개 설정 이름 (팔로워는 늘 공개라 없음). */
const TABS = [
  { key: 'posts', name: '쓴 글', setting: 'posts', api: 'posts' },
  { key: 'likes', name: '좋아요 한 글', setting: 'likes', api: 'likes' },
  { key: 'following', name: '팔로잉', setting: 'following', api: 'following' },
  { key: 'followers', name: '팔로워', setting: null, api: 'followers' },
  { key: 'subscriptions', name: '구독한 블로그', setting: 'subscriptions', api: 'subscriptions' },
];

let profile;
try {
  profile = await api.get(`/api/members/${memberId}`);
} catch (err) {
  if (err.status === 404 || Number.isNaN(memberId)) renderNotFound('회원을 찾을 수 없어요. 탈퇴했을 수 있어요.');
  else {
    $('error').textContent = err.message;
    $('error').hidden = false;
  }
}

const params = new URLSearchParams(location.search);
let tab = TABS.find((t) => t.key === params.get('tab')) || TABS[0];
let page = Number(params.get('page')) || 1;

function avatar(node, imageUrl, nickname) {
  node.replaceChildren(imageUrl ? el('img', { src: imageUrl, alt: '' }) : el('span', {}, [...(nickname || '?')][0]));
}

/** 블로그 한 줄: 머리 사진(없으면 강조 색 동그라미), 이름, 아래 줄 글. */
function blogChip(b, sub) {
  const li = el('li', { class: 'blog-chip' });
  const a = el('a', { href: blogUrl(b.slug) });
  const dot = el('span', { class: 'chip-cover' });
  if (b.coverUrl) dot.append(el('img', { src: b.coverUrl, alt: '' }));
  else if (/^#[0-9A-Fa-f]{6}$/.test(b.accent || '')) dot.style.setProperty('background', b.accent);
  const text = el('span', { class: 'chip-text' });
  text.append(el('strong', {}, b.name), el('span', { class: 'muted' }, sub));
  a.append(dot, text);
  li.append(a);
  return li;
}

if (profile) {
  document.title = (profile.me ? '내 활동' : profile.nickname) + ' - Creat_';
  avatar($('avatar'), profile.imageUrl, profile.nickname);
  $('nickname').textContent = profile.nickname;
  $('bio').textContent = profile.bio || (profile.me ? '아직 소개가 없어요. 내 정보에서 쓸 수 있어요.' : '');
  $('follower-count').textContent = String(profile.followers);
  $('following-count').textContent = String(profile.following);
  $('joined').textContent = profile.joinedAt + ' 가입';
  renderActions();
  renderBlogs();
  $('activity-title').textContent = profile.me ? '내 활동' : '활동';
  $('profile').hidden = false;
  $('activity').hidden = false;
  document.querySelectorAll('.count-link').forEach((b) => b.addEventListener('click', () => {
    selectTab(TABS.find((t) => t.key === b.dataset.tab));
    $('activity').scrollIntoView({ behavior: 'smooth' });
  }));
  renderTabs();
  loadList();
}

function renderActions() {
  const actions = $('profile-actions');
  actions.replaceChildren();
  if (profile.me) {
    actions.append(el('a', { class: 'button', href: '/me.html#profile-card' }, '프로필 꾸미기'));
    return;
  }
  const login = () => { location.href = '/login.html?next=' + encodeURIComponent(location.pathname + location.search); };
  const follow = el('button', { type: 'button', class: profile.followedByMe ? '' : 'dark' },
    profile.followedByMe ? '팔로잉' : '팔로우');
  follow.setAttribute('aria-pressed', String(profile.followedByMe));
  follow.hidden = profile.blockedByMe;
  follow.addEventListener('click', async () => {
    if (!me) return login();
    follow.disabled = true;
    try {
      const res = await api.post(`/api/members/${memberId}/follow`);
      profile.followedByMe = res.on;
      $('follower-count').textContent = String(res.count);
      renderActions();
    } catch (err) {
      toast(err.message);
      follow.disabled = false;
    }
  });
  const block = el('button', { type: 'button', class: 'danger-outline' }, profile.blockedByMe ? '차단 풀기' : '차단');
  block.addEventListener('click', async () => {
    if (!me) return login();
    if (!profile.blockedByMe && !confirm(`${profile.nickname}님을 차단할까요? 서로의 글과 댓글이 숨겨지고 팔로우가 풀립니다.`)) return;
    try {
      const res = await api.post(`/api/members/${memberId}/block`);
      profile.blockedByMe = res.blocked;
      if (res.blocked) profile.followedByMe = false;
      toast(res.blocked ? '차단했어요.' : '차단을 풀었어요.');
      location.reload();
    } catch (err) {
      toast(err.message);
    }
  });
  const report = el('button', { type: 'button', class: 'link-button muted-link' }, '신고');
  report.addEventListener('click', () => openReport({ targetType: 'MEMBER', targetId: memberId }, !!me));
  actions.append(follow, block, report);
}

function renderBlogs() {
  const chip = (b) => blogChip(b, '/blog/' + b.slug);
  $('owned').replaceChildren(...profile.ownedBlogs.map(chip));
  $('joined-blogs').replaceChildren(...profile.joinedBlogs.map(chip));
  $('owned-wrap').hidden = !profile.ownedBlogs.length;
  $('joined-wrap').hidden = !profile.joinedBlogs.length;
  $('blogs').hidden = !profile.ownedBlogs.length && !profile.joinedBlogs.length;
}

function renderTabs() {
  $('activity-tabs').replaceChildren(...TABS.map((t) => {
    const b = el('button', { type: 'button', role: 'tab', class: t === tab ? 'tab active' : 'tab' }, t.name);
    b.setAttribute('aria-selected', String(t === tab));
    // 남이 볼 때 공개하지 않은 목록은 자물쇠 표시
    if (t.setting && !profile.visible[t.setting]) b.append(el('span', { class: 'count' }, '비공개'));
    b.addEventListener('click', () => selectTab(t));
    return b;
  }));
  const toggle = $('share-toggle');
  toggle.hidden = !profile.me || !tab.setting;
  if (profile.me && tab.setting) {
    $('share-check').checked = profile.mySettings[tab.setting];
    $('share-label').textContent = `다른 사람에게 "${tab.name}" 보여주기`;
  }
}

function selectTab(t) {
  tab = t;
  page = 1;
  const q = new URLSearchParams(location.search);
  q.set('tab', t.key);
  q.delete('page');
  history.replaceState(null, '', location.pathname + '?' + q);
  renderTabs();
  loadList();
}

$('share-check').addEventListener('change', async (e) => {
  const next = { ...profile.mySettings, [tab.setting]: e.target.checked };
  try {
    profile.mySettings = await api.put('/api/me/activity-visibility', next);
    toast(e.target.checked ? '다른 사람에게 보여요.' : '나만 볼 수 있어요.');
  } catch (err) {
    e.target.checked = !e.target.checked;
    toast(err.message);
  }
});

async function loadList() {
  const box = $('activity-list');
  const pager = $('activity-pager');
  pager.replaceChildren();
  if (tab.setting && !profile.visible[tab.setting]) {
    box.replaceChildren(el('p', { class: 'empty-box' }, `${profile.nickname}님이 공개하지 않은 목록이에요.`));
    return;
  }
  box.replaceChildren(el('p', { class: 'muted' }, '불러오는 중…'));
  let res;
  try {
    res = await api.get(`/api/members/${memberId}/${tab.api}?page=${page}`);
  } catch (err) {
    box.replaceChildren(el('p', { class: 'empty-box' }, err.message));
    return;
  }
  if (!res.items.length) {
    box.replaceChildren(el('p', { class: 'empty-box' }, emptyText()));
    return;
  }
  let list;
  if (tab.api === 'posts' || tab.api === 'likes') {
    list = el('ul', { class: 'feed' });
    list.append(...res.items.map(feedCard));
  } else if (tab.api === 'subscriptions') {
    list = el('ul', { class: 'blog-chips grid' });
    list.append(...res.items.map((b) => blogChip(b, b.description || '/blog/' + b.slug)));
  } else {
    list = el('ul', { class: 'people' });
    list.append(...res.items.map((m) => {
      const li = el('li', { class: 'person' });
      const a = el('a', { href: '/members/' + m.id });
      const face = el('span', { class: 'avatar' });
      avatar(face, m.imageUrl, m.nickname);
      const text = el('span', { class: 'chip-text' });
      text.append(el('strong', {}, m.nickname), el('span', { class: 'muted' }, m.bio || ''));
      a.append(face, text);
      li.append(a);
      return li;
    }));
  }
  box.replaceChildren(list);
  renderPager(pager, res.page, res.totalPages, (p) => {
    page = p;
    loadList();
    $('activity').scrollIntoView({ behavior: 'smooth' });
  });
}

function emptyText() {
  const who = profile.me ? '아직' : `${profile.nickname}님은 아직`;
  return {
    posts: `${who} 쓴 글이 없어요.`,
    likes: `${who} 좋아요 한 글이 없어요.`,
    following: `${who} 팔로우하는 사람이 없어요.`,
    followers: `${who} 팔로워가 없어요.`,
    subscriptions: `${who} 구독한 블로그가 없어요.`,
  }[tab.key];
}
