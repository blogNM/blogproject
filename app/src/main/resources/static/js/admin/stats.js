// 통계 (ADM-05). 숫자 네 개와 최근 14일 가입·새 글.
import { api } from '../api.js';
import { el, errorLine, table, td } from './common.js';

function card(labelText, value, href, alert) {
  const box = href ? el('a', { href, class: alert ? 'adm-stat adm-alert' : 'adm-stat' }) : el('div', { class: 'adm-stat' });
  box.append(el('p', { class: 'adm-stat-label' }, labelText), el('p', { class: 'adm-stat-value' }, Number(value).toLocaleString('ko-KR')));
  return box;
}

function bar(value, max, alt) {
  const cell = el('div', { class: 'adm-bar-cell' });
  const track = el('div', { class: 'adm-bar-track', 'aria-hidden': 'true' });
  const fill = el('div', { class: alt ? 'adm-bar-fill adm-alt' : 'adm-bar-fill' });
  // CSSOM으로 너비만 준다 (style 속성이 아니라 CSP에 걸리지 않는다)
  fill.style.width = max > 0 ? `${Math.round((value / max) * 100)}%` : '0';
  track.append(fill);
  cell.append(track, el('span', { class: 'adm-bar-num' }, String(value)));
  return cell;
}

export async function mount(panel, ctx) {
  let s;
  try {
    s = await api.get('/api/admin/stats');
  } catch (err) {
    panel.replaceChildren(errorLine(err));
    return;
  }
  ctx.setPendingCount(s.pendingReports);
  const cards = el('div', { class: 'adm-stats' });
  cards.append(card('회원', s.members), card('블로그', s.blogs), card('글', s.posts),
    card('처리할 신고', s.pendingReports, '#reports', s.pendingReports > 0));

  const days = [...(s.days || [])].reverse();
  // 가입과 새 글은 크기가 많이 달라 칸마다 따로 견준다
  const maxSignups = Math.max(1, ...days.map((d) => d.signups));
  const maxPosts = Math.max(1, ...days.map((d) => d.posts));
  const rows = days.map((d) => {
    const tr = el('tr');
    tr.append(td(d.date, 'adm-nowrap'), td(bar(d.signups, maxSignups)), td(bar(d.posts, maxPosts, true)));
    return tr;
  });
  const section = el('section', { class: 'adm-card' });
  const legend = el('p', { class: 'adm-legend' });
  const a = el('span');
  a.append(el('span', { class: 'adm-dot' }), document.createTextNode('가입'));
  const b = el('span');
  b.append(el('span', { class: 'adm-dot adm-alt' }), document.createTextNode('새 글'));
  legend.append(a, b);
  section.append(el('h2', {}, '최근 14일'), legend,
    table(['날짜', '가입', '새 글'], rows, '기록이 없습니다.'));
  panel.replaceChildren(cards, section);
}
