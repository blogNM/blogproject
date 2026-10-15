// 블로그 관리 (ADM-02, ADM-07). 숨기기·강제 폐쇄·블로그장 경고·강퇴.
import { api } from '../api.js';
import { renderPager } from '../pager.js';
import { VISIBILITY, act, badge, blogLink, button, el, errorLine, label, query, searchForm, table, td, toast } from './common.js';

let q = '';
let page = 1;

export async function mount(panel) {
  const card = el('section', { class: 'adm-card' });

  function actions(b) {
    const box = el('div', { class: 'adm-actions' });
    const post = (action) => (answer) => api.post(`/api/admin/blogs/${b.id}/${action}`,
      typeof answer === 'string' ? { reason: answer } : { reason: answer.reason, reasonCode: answer.code });
    const name = `"${b.name}"`;

    const hide = button(b.hidden ? '숨김 풀기' : '숨기기', '', () => act(hide,
      { title: `${name} ${b.hidden ? '숨김 풀기' : '숨기기'}`,
        message: b.hidden ? '다시 목록과 검색에 보입니다.' : '목록·검색·피드에서 빠지고, 멤버가 아니면 주소로도 들어갈 수 없습니다. 블로그장에게 알림이 갑니다.' },
      post(b.hidden ? 'unhide' : 'hide'),
      async () => { toast(b.hidden ? '숨김을 풀었습니다.' : '숨겼습니다.'); await load(); }));

    const close = button('강제 폐쇄', 'adm-danger', () => act(close,
      { title: `${name} 강제 폐쇄`, message: '블로그를 폐쇄 예정으로 바꿉니다. 기간이 지나면 되돌릴 수 없습니다.', danger: true, okText: '폐쇄' },
      post('close'),
      async (r) => { toast(`${r.closeAt}에 폐쇄됩니다.`); await load(); }));
    if (b.status === 'CLOSING') close.disabled = true;

    const warn = button('블로그장 경고', '', () => act(warn,
      { title: `${b.ownerNickname || '블로그장'}님에게 경고`,
        message: `지금 경고 ${b.ownerWarnings}/3. 3번이 되면 블로그장에서 강퇴됩니다.`, danger: b.ownerWarnings >= 2, okText: '경고', withCode: true },
      post('warn-owner'),
      async (r) => { toast(r.kicked ? `경고 ${r.warnings}/3. 블로그장이 강퇴되었습니다.` : `경고 ${r.warnings}/3`); await load(); }));

    const kick = button('블로그장 강퇴', 'adm-danger', () => act(kick,
      { title: `${b.ownerNickname || '블로그장'}님 강퇴`,
        message: '부블로그장이 있으면 블로그장을 넘기고, 없으면 블로그가 폐쇄 예정이 됩니다.', danger: true, okText: '강퇴' },
      post('kick-owner'),
      async (r) => { toast(r.result === 'TRANSFERRED' ? '블로그장을 넘겼습니다.' : '블로그가 폐쇄 예정이 되었습니다.'); await load(); }));
    if (b.status === 'CLOSING') { warn.disabled = true; kick.disabled = true; }

    box.append(hide, close, warn, kick);
    return box;
  }

  async function load() {
    let data;
    try {
      data = await api.get('/api/admin/blogs' + query({ q, page }));
    } catch (err) {
      card.replaceChildren(errorLine(err));
      return;
    }
    const rows = data.items.map((b) => {
      const tr = el('tr');
      const nameCell = td(blogLink(b.slug, b.name), 'adm-wrap');
      nameCell.append(el('span', { class: 'adm-sub' }, '/blog/' + b.slug));
      const state = el('div', { class: 'adm-badges' });
      state.append(badge(label(VISIBILITY, b.visibility), 'adm-grey'));
      if (b.hidden) state.append(badge('숨김', 'adm-warn'));
      if (b.status === 'CLOSING') state.append(badge(`${b.closeScheduledAt || ''} 폐쇄 예정`.trim(), 'adm-bad'));
      if (b.ownerWarnings > 0) state.append(badge(`경고 ${b.ownerWarnings}/3`, 'adm-warn'));
      // 테스트 회원이 만든 블로그 (D-113)
      if (b.ownerTest) state.append(badge('테스트', 'adm-test'));
      const owner = td(b.ownerNickname || '-', 'adm-nowrap');
      if (b.ownerTest) owner.append(badge('테스트', 'adm-test'));
      tr.append(td('#' + b.id, 'adm-nowrap'), nameCell, state.childElementCount ? td(state) : td(''), owner,
        td(b.members, 'adm-num'), td(b.posts, 'adm-num'), td(b.createdAt, 'adm-nowrap'), td(actions(b)));
      return tr;
    });
    const toolbar = el('div', { class: 'adm-toolbar' });
    toolbar.append(searchForm('블로그 이름·주소 찾기', q, (v) => { q = v; page = 1; load(); }));
    const pager = el('nav', { class: 'pager' });
    renderPager(pager, data.page, data.totalPages, (p) => { page = p; load(); });
    card.replaceChildren(toolbar, el('p', { class: 'adm-total' }, `모두 ${data.total}개`),
      table(['번호', '블로그', '상태', '블로그장', ['회원', 'adm-num'], ['글', 'adm-num'], '만든 날', '조치'], rows,
        '찾는 블로그가 없습니다.'),
      pager);
  }

  panel.replaceChildren(card);
  await load();
}
