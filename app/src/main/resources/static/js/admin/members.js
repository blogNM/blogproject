// 회원 찾기와 상세 (ADM-01). 개인정보는 가려서 보이고, "전체 보기"를 누르면 기록이 남는다.
import { api } from '../api.js';
import { renderPager } from '../pager.js';
import {
  MEMBERSHIP_STATUS, MEMBER_STATUS, ROLE, badge, blogLink, button, el, errorLine, filter, label, query, searchForm, showError,
  table, td,
} from './common.js';

let q = '';
let page = 1;
// 전체 / 실제 회원 / 테스트 회원 (D-113)
let kind = 'all';
const KINDS = [['all', '전체', 'all'], ['real', '실제 회원', 'real'], ['test', '테스트 회원', 'test']];
let selectedId = null;

function info(pairs) {
  const dl = el('dl', { class: 'adm-info' });
  pairs.forEach(([k, v]) => {
    const dd = el('dd');
    if (v instanceof Node) dd.append(v);
    else dd.textContent = v === null || v === undefined || v === '' ? '-' : String(v);
    dl.append(el('dt', {}, k), dd);
  });
  return dl;
}

async function showDetail(box, id) {
  box.replaceChildren(el('p', { class: 'adm-loading' }, '불러오는 중…'));
  let m;
  try {
    m = await api.get(`/api/admin/members/${id}`);
  } catch (err) {
    box.replaceChildren(errorLine(err));
    return;
  }
  const pii = { name: el('span', {}, m.name || '-'), email: el('span', {}, m.email || '-'), phone: el('span', {}, m.phone || '-') };
  const msg = el('p', { class: 'adm-error', role: 'alert' });
  const reveal = button('전체 보기', 'adm-small', async () => {
    reveal.disabled = true;
    try {
      const full = await api.post(`/api/admin/members/${id}/reveal`);
      pii.name.textContent = full.name || '-';
      pii.email.textContent = full.email || '-';
      pii.phone.textContent = full.phone || '-';
      reveal.textContent = '모두 보는 중';
    } catch (err) {
      showError(err, msg);
      reveal.disabled = false;
    }
  });

  const head = el('div', { class: 'adm-section-head' });
  const title = el('h2', {}, m.nickname);
  head.append(title, badge(label(MEMBER_STATUS, m.status), m.status === 'ACTIVE' ? 'adm-ok' : 'adm-grey'));
  if (m.test) head.append(badge('테스트', 'adm-test'));

  const revealRow = el('div', { class: 'adm-actions' });
  revealRow.append(reveal);
  const note = el('p', { class: 'adm-note' }, '개인정보를 모두 보려면 누르세요. 누른 기록이 남습니다.');

  const blogs = el('ul', { class: 'adm-mini-list' });
  if (!m.blogs || m.blogs.length === 0) {
    blogs.append(el('li', { class: 'adm-detail-empty' }, '참여한 블로그가 없습니다.'));
  } else {
    m.blogs.forEach((b) => {
      const li = el('li');
      const line = el('div', { class: 'adm-badges' });
      line.append(blogLink(b.slug, b.name), badge(label(ROLE, b.role), 'adm-grey'),
        badge(label(MEMBERSHIP_STATUS, b.status), b.status === 'ACTIVE' ? 'adm-ok' : 'adm-grey'));
      if (b.suspendedUntil) line.append(badge(`${b.suspendedUntil}까지 정지`, 'adm-bad'));
      li.append(line, el('span', { class: 'adm-note' }, `경고 ${b.warnings}번 · 정지 ${b.suspensions}번`));
      blogs.append(li);
    });
  }

  box.replaceChildren(head,
    info([['회원 번호', '#' + m.id], ['이름', pii.name], ['이메일', pii.email], ['휴대폰', pii.phone],
      ['가입', m.createdAt], ...(m.withdrawnAt ? [['탈퇴', m.withdrawnAt]] : [])]),
    revealRow, note, msg, el('h2', { class: 'adm-sub-title' }, '참여한 블로그'), blogs);
}

export async function mount(panel) {
  const detail = el('section', { class: 'adm-card' });
  detail.append(el('p', { class: 'adm-detail-empty' }, '회원을 누르면 자세히 보입니다.'));
  const listBox = el('section', { class: 'adm-card' });

  async function load() {
    let data;
    try {
      data = await api.get('/api/admin/members' + query({ q, kind, page }));
    } catch (err) {
      listBox.replaceChildren(errorLine(err));
      return;
    }
    const rows = data.items.map((m) => {
      const tr = el('tr', { class: m.id === selectedId ? 'adm-clickable adm-selected' : 'adm-clickable', tabindex: '0' });
      const name = el('span', {}, m.nickname);
      const nameCell = td(name);
      if (m.test) nameCell.append(badge('테스트', 'adm-test'));
      nameCell.append(el('span', { class: 'adm-sub' }, m.email));
      tr.append(td('#' + m.id, 'adm-nowrap'), nameCell,
        td(badge(label(MEMBER_STATUS, m.status), m.status === 'ACTIVE' ? 'adm-ok' : 'adm-grey')),
        td(m.blogs, 'adm-num'), td(m.posts, 'adm-num'), td(m.createdAt, 'adm-nowrap'));
      const open = () => {
        selectedId = m.id;
        tr.parentElement.querySelectorAll('tr').forEach((r) => r.classList.remove('adm-selected'));
        tr.classList.add('adm-selected');
        showDetail(detail, m.id);
        if (window.matchMedia('(max-width: 860px)').matches) detail.scrollIntoView({ behavior: 'smooth' });
      };
      tr.addEventListener('click', open);
      tr.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          open();
        }
      });
      return tr;
    });
    const toolbar = el('div', { class: 'adm-toolbar' });
    toolbar.append(searchForm('닉네임·이메일 찾기', q, (v) => { q = v; page = 1; load(); }),
      filter(KINDS.map(([value, name, key]) => [value, `${name} ${data.counts[key]}`]), kind,
        (v) => { kind = v; page = 1; load(); }));
    const pager = el('nav', { class: 'pager' });
    renderPager(pager, data.page, data.totalPages, (p) => { page = p; load(); });
    const shown = KINDS.find(([value]) => value === kind)[1];
    listBox.replaceChildren(toolbar, el('p', { class: 'adm-total' }, kind === 'all' ? `모두 ${data.total}명` : `${shown} ${data.total}명`),
      table(['번호', '닉네임·이메일', '상태', ['블로그', 'adm-num'], ['글', 'adm-num'], '가입'], rows, '찾는 회원이 없습니다.'),
      pager);
  }

  const split = el('div', { class: 'adm-split' });
  split.append(listBox, detail);
  panel.replaceChildren(split);
  await load();
  if (selectedId) showDetail(detail, selectedId);
}
