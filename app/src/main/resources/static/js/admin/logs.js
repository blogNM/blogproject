// 관리자 활동 기록 (ADM-06).
import { api } from '../api.js';
import { renderPager } from '../pager.js';
import { TARGET_TYPE, badge, el, errorLine, label, query, table, td } from './common.js';

const ACTION = {
  WARN: '경고', KICK: '강퇴', DELETE: '삭제', HIDE: '숨김', UNHIDE: '숨김 해제', CLOSE: '폐쇄',
  RESTORE: '되살리기', VIEW_PII: '개인정보 전체 보기', REPORT: '신고 처리', CREATE_ADMIN: '관리자 추가',
};
const TARGET = { ...TARGET_TYPE, REPORT: '신고', ADMIN: '관리자' };

export async function mount(panel) {
  let page = 1;

  async function load() {
    let data;
    try {
      data = await api.get('/api/admin/logs' + query({ page }));
    } catch (err) {
      panel.replaceChildren(errorLine(err));
      return;
    }
    const rows = data.items.map((l) => {
      const tr = el('tr');
      tr.append(td(l.createdAt, 'adm-nowrap'), td(l.admin, 'adm-nowrap'), td(badge(label(ACTION, l.action))),
        td(`${label(TARGET, l.targetType)} #${l.targetId}`, 'adm-nowrap'), td(l.reason || '', 'adm-wrap'));
      return tr;
    });
    const pager = el('nav', { class: 'pager' });
    renderPager(pager, data.page, data.totalPages, (p) => { page = p; load(); });
    const card = el('section', { class: 'adm-card' });
    card.append(el('h2', {}, '활동 기록'), el('p', { class: 'adm-total' }, `모두 ${data.total}건`),
      table(['시각', '관리자', '한 일', '대상', '사유'], rows, '기록이 없습니다.'), pager);
    panel.replaceChildren(card);
  }

  await load();
}
