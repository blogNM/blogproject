// 신고 처리 (ADM-04). 기다리는 신고는 결과·사유를 골라 처리한다.
import { api } from '../api.js';
import { renderPager } from '../pager.js';
import { TARGET_TYPE, askReason, badge, blogLink, el, errorLine, filter, label, link, query, showError } from './common.js';

const RESULTS = [
  ['NONE', '문제 없음'],
  ['OWNER_WARNING', '블로그장 경고'],
  ['OWNER_KICK', '블로그장 강퇴'],
  ['BLOG_CLOSE', '블로그 폐쇄'],
];
const CONFIRM = {
  OWNER_KICK: '블로그장을 강퇴합니다. 부블로그장이 있으면 블로그장을 넘기고, 없으면 블로그가 폐쇄 예정이 됩니다.',
  BLOG_CLOSE: '블로그를 폐쇄합니다. 폐쇄 예정 기간이 지나면 되돌릴 수 없습니다.',
};

let status = 'PENDING';
let page = 1;

function processBox(r, reload) {
  const box = el('div', { class: 'adm-process' });
  const select = el('select', { class: 'adm-select', 'aria-label': '처리 결과' });
  RESULTS.filter(([v]) => r.blogSlug || v === 'NONE').forEach(([v, text]) => {
    select.append(el('option', { value: v }, text));
  });
  const reason = el('input', { type: 'text', maxlength: '200', placeholder: '처리 사유 (200자까지)', 'aria-label': '처리 사유' });
  const go = el('button', { type: 'button', class: 'adm-go' }, '처리');
  const msg = el('p', { class: 'adm-error', role: 'alert' });
  go.addEventListener('click', async () => {
    msg.textContent = '';
    const result = select.value;
    if (CONFIRM[result]) {
      const name = r.blogName ? `"${r.blogName}" ` : '';
      const ok = await askReason({
        title: `${name}${label(Object.fromEntries(RESULTS), result)}`,
        message: CONFIRM[result] + '\n정말 처리할까요?',
        danger: true,
        okText: '처리',
        withReason: false,
      });
      if (ok === null) return;
    }
    go.disabled = true;
    try {
      await api.post(`/api/admin/reports/${r.id}`, { result, reason: reason.value.trim() });
      await reload();
    } catch (err) {
      showError(err, msg);
      go.disabled = false;
    }
  });
  box.append(select, reason, go);
  const wrap = el('div');
  wrap.append(box, msg);
  return wrap;
}

function reportItem(r, reload) {
  const li = el('li', { class: 'adm-report' });
  const head = el('div', { class: 'adm-report-head' });
  const badges = el('div', { class: 'adm-badges' });
  badges.append(badge(label(TARGET_TYPE, r.targetType)), badge(r.reasonLabel || r.reason, 'adm-warn'));
  if (r.byOwner) badges.append(badge('블로그장이 신고', 'adm-grey'));
  if (r.targetGone) badges.append(badge('이미 지워짐·숨겨짐', 'adm-grey'));
  if (r.sameTargetPending > 1) badges.append(badge(`같은 대상 신고 ${r.sameTargetPending}건`, 'adm-bad'));
  if (r.status !== 'PENDING' && r.resultLabel) badges.append(badge(r.resultLabel, 'adm-ok'));
  head.append(badges, el('span', { class: 'adm-report-time' }, r.createdAt));

  const summary = el('p', { class: 'adm-report-summary' });
  if (r.link && !r.targetGone) summary.append(link(r.link, r.summary || '대상 보기'));
  else summary.textContent = r.summary || `${label(TARGET_TYPE, r.targetType)} #${r.targetId}`;

  li.append(head, summary);
  if (r.detail) li.append(el('p', { class: 'adm-report-detail' }, r.detail));

  const meta = el('p', { class: 'adm-meta' });
  meta.append(document.createTextNode('신고된 사람: '), el('span', { class: 'strong' }, r.authorNickname || '-'));
  if (r.blogSlug) {
    meta.append(document.createTextNode(' · 블로그: '), blogLink(r.blogSlug, r.blogName || r.blogSlug));
  }
  li.append(meta);
  if (r.status !== 'PENDING' && r.processedAt) {
    li.append(el('p', { class: 'adm-meta' }, `${r.processedAt} 처리`));
  }
  if (r.status === 'PENDING') li.append(processBox(r, reload));
  return li;
}

export async function mount(panel, ctx) {
  async function load() {
    let data;
    try {
      data = await api.get('/api/admin/reports' + query({ status, page }));
    } catch (err) {
      panel.replaceChildren(errorLine(err));
      return;
    }
    const reload = async () => {
      await load();
      ctx.onReportsChanged();
    };
    const toolbar = el('div', { class: 'adm-toolbar' });
    toolbar.append(filter([['PENDING', '기다리는 신고'], ['DONE', '처리한 신고']], status, (v) => {
      status = v;
      page = 1;
      load();
    }));
    const list = el('ul', { class: 'adm-reports' });
    if (data.items.length === 0) {
      list.append(el('li', { class: 'adm-card adm-empty' }, status === 'PENDING' ? '기다리는 신고가 없습니다.' : '처리한 신고가 없습니다.'));
    } else {
      list.append(...data.items.map((r) => reportItem(r, reload)));
    }
    const pager = el('nav', { class: 'pager' });
    renderPager(pager, data.page, data.totalPages, (p) => { page = p; load(); });
    panel.replaceChildren(toolbar, el('p', { class: 'adm-total' }, `모두 ${data.total}건`), list, pager);
  }
  await load();
}
