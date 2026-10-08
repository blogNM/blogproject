// 글·댓글 관리 (ADM-03). 숨기기·삭제, 관리자가 지운 글은 되살리기.
import { api } from '../api.js';
import { renderPager } from '../pager.js';
import {
  CONTENT_STATUS, act, badge, blogLink, button, el, errorLine, filter, label, link, query, searchForm, table, td, toast,
} from './common.js';

const STATUS_FILTER = [['', '전체'], ['NORMAL', '보임'], ['HIDDEN', '숨김'], ['DELETED', '삭제됨']];
const ACTION = {
  hide: ['숨기기', '', '숨겼습니다.', false],
  unhide: ['숨김 풀기', '', '숨김을 풀었습니다.', false],
  delete: ['삭제', 'adm-danger', '삭제했습니다.', true],
  restore: ['되살리기', '', '되살렸습니다.', false],
};

function statusBadge(s) {
  return badge(label(CONTENT_STATUS, s), s === 'NORMAL' ? 'adm-ok' : s === 'HIDDEN' ? 'adm-warn' : 'adm-grey');
}

/** 글·댓글 목록 하나. kind: posts | comments */
function section(kind) {
  const isPost = kind === 'posts';
  const state = { q: '', status: '', page: 1 };
  const card = el('section', { class: 'adm-card' });

  function actionsFor(item) {
    const list = [];
    if (item.status === 'NORMAL') list.push('hide', 'delete');
    if (item.status === 'HIDDEN') list.push('unhide', 'delete');
    if (isPost && item.status === 'DELETED' && item.deletedBy === 'ADMIN') list.push('restore');
    const box = el('div', { class: 'adm-actions' });
    list.forEach((action) => {
      const [text, cls, done, danger] = ACTION[action];
      const what = isPost ? `글 "${item.title}"` : '댓글';
      const b = button(text, cls, () => act(b,
        { title: `${what} ${text}`, message: danger ? '지운 내용은 글쓴이에게 보이지 않습니다.' : '', danger, okText: text },
        (reason) => api.post(`/api/admin/${kind}/${item.id}/${action}`, { reason }),
        async () => { toast(done); await load(); }));
      box.append(b);
    });
    return box;
  }

  function row(item) {
    const tr = el('tr');
    const status = el('div', { class: 'adm-badges' });
    status.append(statusBadge(item.status));
    if (item.deletedBy) status.append(badge(item.deletedBy === 'ADMIN' ? '관리자가 지움' : '글쓴이가 지움', 'adm-grey'));
    if (isPost) {
      const main = td(item.status === 'DELETED' ? el('span', {}, item.title) : link(item.link, item.title), 'adm-wrap');
      main.append(el('span', { class: 'adm-sub' }, item.blogName || item.blogSlug || ''));
      tr.append(td('#' + item.id, 'adm-nowrap'), main, td(item.author, 'adm-nowrap'), td(status),
        td(item.createdAt, 'adm-nowrap'), td(actionsFor(item)));
    } else {
      const main = td(item.content, 'adm-wrap');
      const where = el('span', { class: 'adm-sub' });
      where.append(link(item.link, item.postTitle || '글 보기'));
      if (item.blogSlug) where.append(document.createTextNode(' · '), blogLink(item.blogSlug));
      main.append(where);
      tr.append(td('#' + item.id, 'adm-nowrap'), main, td(item.author, 'adm-nowrap'), td(status),
        td(item.createdAt, 'adm-nowrap'), td(actionsFor(item)));
    }
    return tr;
  }

  async function load() {
    let data;
    try {
      data = await api.get(`/api/admin/${kind}` + query({ q: state.q, status: state.status, page: state.page }));
    } catch (err) {
      card.replaceChildren(el('h2', {}, isPost ? '글' : '댓글'), errorLine(err));
      return;
    }
    const head = el('div', { class: 'adm-section-head' });
    head.append(el('h2', {}, isPost ? '글' : '댓글'),
      filter(isPost ? STATUS_FILTER : STATUS_FILTER.slice(0, 3), state.status, (v) => { state.status = v; state.page = 1; load(); }));
    const toolbar = el('div', { class: 'adm-toolbar' });
    toolbar.append(searchForm(isPost ? '글 제목 찾기' : '댓글 내용 찾기', state.q, (v) => { state.q = v; state.page = 1; load(); }));
    const pager = el('nav', { class: 'pager' });
    renderPager(pager, data.page, data.totalPages, (p) => { state.page = p; load(); });
    card.replaceChildren(head, toolbar, el('p', { class: 'adm-total' }, `모두 ${data.total}개`),
      table(['번호', isPost ? '제목·블로그' : '내용·글', '쓴 사람', '상태', '쓴 날', '조치'], data.items.map(row),
        isPost ? '찾는 글이 없습니다.' : '찾는 댓글이 없습니다.'),
      pager);
  }

  return { card, load };
}

export async function mount(panel) {
  const posts = section('posts');
  const comments = section('comments');
  panel.replaceChildren(posts.card, comments.card);
  await Promise.all([posts.load(), comments.load()]);
}
