// 위쪽 메뉴의 종 아이콘과 알림 사이드바 (SOC-04, D-39, D-56, D-72).
// 30초마다 읽지 않은 알림 수를 묻는다. 자동 요청이라 30분 무활동 시간을 늘리지 않는다 (D-62).
// 문구는 서버가 만들고, 화면은 textContent로만 넣는다 (SEC-06).
import { api } from './api.js';
import { el, toast } from './layout.js';

const POLL_MS = 30 * 1000;
const TABS = [['ALL', '전체'], ['COMMENT', '댓글'], ['LIKE', '좋아요'], ['FOLLOW', '팔로우·구독'], ['BLOG', '블로그'], ['ADMIN', '운영']];
const SVG = 'http://www.w3.org/2000/svg';

function bellIcon() {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', '20');
  svg.setAttribute('height', '20');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(SVG, 'path');
  path.setAttribute('d', 'M12 3a6 6 0 0 0-6 6v3.6l-1.6 2.7A1 1 0 0 0 5.3 17h13.4a1 1 0 0 0 .9-1.7L18 12.6V9a6 6 0 0 0-6-6Zm-2.2 15.5a2.3 2.3 0 0 0 4.4 0Z');
  path.setAttribute('fill', 'currentColor');
  svg.append(path);
  return svg;
}

export function mountBell(nav) {
  const bell = el('button', { type: 'button', class: 'bell', 'aria-label': '알림', 'aria-expanded': 'false' });
  const badge = el('span', { class: 'bell-badge' });
  badge.hidden = true;
  bell.append(bellIcon(), badge);
  nav.prepend(bell);

  const panel = buildPanel();
  document.body.append(panel.root);

  let unread = {};
  const showCount = (counts) => {
    unread = counts || {};
    const n = unread.ALL || 0;
    badge.textContent = n > 99 ? '99+' : String(n);
    badge.hidden = n === 0;
    bell.setAttribute('aria-label', n ? `알림 ${n}개 안 읽음` : '알림');
    panel.showTabCounts(unread);
  };
  panel.onCounts = showCount;

  let timer;
  const poll = async () => {
    try {
      showCount(await api.get('/api/notifications/unread'));
    } catch (err) {
      // 로그인이 풀렸으면 더 묻지 않는다.
      if (err.status === 401) clearInterval(timer);
    }
  };
  poll();
  timer = setInterval(() => { if (!document.hidden) poll(); }, POLL_MS);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) poll(); });

  bell.addEventListener('click', () => {
    const open = panel.root.hidden;
    panel.root.hidden = !open;
    bell.setAttribute('aria-expanded', String(open));
    if (open) panel.load();
  });
  panel.onClose = () => {
    panel.root.hidden = true;
    bell.setAttribute('aria-expanded', 'false');
    bell.focus();
  };
}

function buildPanel() {
  const root = el('aside', { class: 'notice-panel', 'aria-label': '알림' });
  root.hidden = true;
  const head = el('div', { class: 'notice-head' });
  const close = el('button', { type: 'button', class: 'notice-close', 'aria-label': '알림 닫기' }, '×');
  head.append(el('h2', {}, '알림'), close);

  const tabs = el('div', { class: 'notice-tabs', role: 'tablist' });
  const tabButtons = {};
  TABS.forEach(([code, name]) => {
    const b = el('button', { type: 'button', role: 'tab', 'aria-selected': 'false', 'data-tab': code });
    b.append(el('span', {}, name), el('span', { class: 'dot' }));
    tabButtons[code] = b;
    tabs.append(b);
  });

  const tools = el('div', { class: 'notice-tools' });
  const readAll = el('button', { type: 'button' }, '모두 읽음');
  const delAll = el('button', { type: 'button' }, '전체 삭제');
  tools.append(readAll, delAll);

  const list = el('ul', { class: 'notice-list' });
  const more = el('button', { type: 'button', class: 'notice-more' }, '더 보기');
  more.hidden = true;
  const foot = el('p', { class: 'notice-foot muted' });
  foot.append(document.createTextNode('알림 종류와 보관 기간은 '), el('a', { href: '/me.html#notifications' }, '내 정보'),
    document.createTextNode('에서 바꿀 수 있어요.'));
  root.append(head, tabs, tools, list, more, foot);

  const state = { tab: 'ALL', page: 1 };
  const panel = { root, onCounts: () => {}, onClose: () => {} };

  panel.showTabCounts = (counts) => {
    TABS.forEach(([code]) => tabButtons[code].classList.toggle('has-unread', (counts[code] || 0) > 0));
  };

  panel.load = async (append = false) => {
    Object.entries(tabButtons).forEach(([code, b]) => b.setAttribute('aria-selected', String(code === state.tab)));
    if (!append) {
      state.page = 1;
      list.replaceChildren(el('li', { class: 'notice-empty' }, '불러오는 중…'));
    }
    try {
      const res = await api.get('/api/notifications?' + new URLSearchParams({ tab: state.tab, page: state.page }));
      if (!append) list.replaceChildren();
      if (!res.items.length && !append) list.append(el('li', { class: 'notice-empty' }, '새 알림이 없어요.'));
      res.items.forEach((n) => list.append(item(n)));
      more.hidden = res.page >= res.totalPages;
      panel.onCounts(res.unread);
    } catch (err) {
      list.replaceChildren(el('li', { class: 'notice-empty' }, err.message));
    }
  };

  function item(n) {
    const li = el('li', { class: n.read ? 'notice-item' : 'notice-item unread' });
    const body = n.link ? el('a', { href: n.link }) : el('div');
    body.append(el('p', { class: 'notice-text' }, n.message), el('span', { class: 'muted' }, n.createdAt));
    body.addEventListener('click', async (e) => {
      if (n.read) return;
      if (n.link) e.preventDefault();
      try {
        await api.post(`/api/notifications/${n.id}/read`);
      } catch {
        // 읽음 표시가 실패해도 이동은 한다.
      }
      if (n.link) {
        location.href = n.link;
      } else {
        n.read = true;
        li.classList.remove('unread');
        panel.onCounts(await api.get('/api/notifications/unread').catch(() => ({})));
      }
    });
    li.append(body);
    return li;
  }

  tabs.addEventListener('click', (e) => {
    const b = e.target.closest('button[data-tab]');
    if (!b) return;
    state.tab = b.dataset.tab;
    panel.load();
  });
  more.addEventListener('click', () => { state.page += 1; panel.load(true); });
  readAll.addEventListener('click', async () => {
    await api.post('/api/notifications/read-all?tab=' + state.tab).catch((err) => toast(err.message));
    panel.load();
  });
  delAll.addEventListener('click', async () => {
    const name = TABS.find(([c]) => c === state.tab)[1];
    if (!confirm(`${name} 알림을 모두 지울까요?`)) return;
    await api.del('/api/notifications?tab=' + state.tab).catch((err) => toast(err.message));
    panel.load();
  });
  close.addEventListener('click', () => panel.onClose());
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !root.hidden) panel.onClose(); });
  return panel;
}
