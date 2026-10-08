// 관리자 화면 공통 도구. 사용자 입력은 모두 textContent로 넣는다 (SEC-06).
import { el, toast } from '../layout.js';

export { el, toast };

/** 서버 오류 처리. 로그인이 풀렸으면 로그인 화면으로, 비밀번호를 바꿔야 하면 다시 읽는다. 처리했으면 true. */
export function handleAuthError(err) {
  if (err && err.status === 401) {
    location.replace('/admin/login.html');
    return true;
  }
  if (err && err.status === 403 && err.code === 'PASSWORD_CHANGE_REQUIRED') {
    location.reload();
    return true;
  }
  return false;
}

/** 오류를 상자에 보이거나(없으면 토스트) 한다. */
export function showError(err, box) {
  if (handleAuthError(err)) return;
  const message = (err && err.message) || '요청을 처리하지 못했습니다.';
  if (box) box.textContent = message;
  else toast(message);
}

export function errorLine(err) {
  const p = el('p', { class: 'adm-error', role: 'alert' });
  showError(err, p);
  return p;
}

export function badge(text, kind = '') {
  return el('span', { class: kind ? `adm-badge ${kind}` : 'adm-badge' }, text);
}

export function button(text, cls, onClick) {
  const b = el('button', { type: 'button', class: cls || '' }, text);
  b.addEventListener('click', onClick);
  return b;
}

/** 표. head는 [이름, 클래스] 또는 이름. 표가 넓으면 감싼 칸 안에서만 옆으로 밀린다. */
export function table(head, rows, emptyText) {
  const wrap = el('div', { class: 'adm-table-wrap' });
  const t = el('table', { class: 'adm-table' });
  const thead = el('thead');
  const tr = el('tr');
  head.forEach((h) => {
    const [text, cls] = Array.isArray(h) ? h : [h];
    tr.append(el('th', cls ? { class: cls, scope: 'col' } : { scope: 'col' }, text));
  });
  thead.append(tr);
  const tbody = el('tbody');
  if (rows.length === 0) {
    const empty = el('tr');
    empty.append(el('td', { class: 'adm-empty', colspan: String(head.length) }, emptyText || '없습니다.'));
    tbody.append(empty);
  } else {
    tbody.append(...rows);
  }
  t.append(thead, tbody);
  wrap.append(t);
  return wrap;
}

/** 칸 하나. value가 노드면 그대로, 아니면 글자로 넣는다. */
export function td(value, cls) {
  const cell = el('td', cls ? { class: cls } : {});
  if (value instanceof Node) cell.append(value);
  else if (Array.isArray(value)) cell.append(...value);
  else cell.textContent = value === null || value === undefined ? '' : String(value);
  return cell;
}

/** 새 탭으로 여는 링크. 이 사이트 안의 주소만 받는다. */
export function link(href, text) {
  if (!href || !href.startsWith('/') || href.startsWith('//')) return el('span', {}, text);
  return el('a', { href, target: '_blank', rel: 'noopener' }, text);
}

export function blogLink(slug, text) {
  return slug ? link('/blog/' + encodeURIComponent(slug), text || slug) : el('span', {}, text || '');
}

/** 검색창. 엔터를 누르면 onSearch(q). */
export function searchForm(placeholder, value, onSearch) {
  const form = el('form', { role: 'search' });
  const input = el('input', { type: 'search', placeholder, 'aria-label': placeholder, maxlength: '50' });
  input.value = value || '';
  form.append(input, el('button', { type: 'submit' }, '찾기'));
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    onSearch(input.value.trim());
  });
  return form;
}

/** 단추 묶음 필터. options: [[값, 이름]] */
export function filter(options, current, onChange) {
  const box = el('div', { class: 'adm-filter', role: 'group' });
  options.forEach(([value, label]) => {
    const b = el('button', { type: 'button', class: value === current ? 'active' : '' }, label);
    b.setAttribute('aria-pressed', String(value === current));
    b.addEventListener('click', () => onChange(value));
    box.append(b);
  });
  return box;
}

export function query(params) {
  const s = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') s.set(k, v);
  });
  const text = s.toString();
  return text ? '?' + text : '';
}

// ----- 사유 입력 창 -----
const dialog = document.getElementById('reason-dialog');

/**
 * 조치 전에 사유를 받는다. 취소하면 null, 확인하면 사유(빈 문자열일 수 있음).
 * danger면 확인 단추를 빨갛게 하고 되돌리기 어렵다는 말을 붙인다.
 */
export function askReason({ title, message, danger = false, okText = '확인', withReason = true }) {
  const input = document.getElementById('reason-input');
  input.hidden = !withReason;
  document.querySelector('label[for=reason-input]').hidden = !withReason;
  const ok = document.getElementById('reason-ok');
  const msg = document.getElementById('reason-message');
  document.getElementById('reason-title').textContent = title;
  msg.textContent = message || '';
  msg.hidden = !message;
  msg.className = danger ? 'adm-dialog-warn' : '';
  ok.textContent = okText;
  ok.className = danger ? 'adm-confirm-danger' : 'adm-go';
  input.value = '';
  return new Promise((resolve) => {
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      cleanup();
      if (dialog.open) dialog.close();
      resolve(value);
    };
    const onSubmit = (e) => {
      e.preventDefault();
      finish(input.value.trim());
    };
    const onCancel = () => finish(null);
    const onClose = () => finish(null);
    const cancelButton = document.getElementById('reason-cancel');
    const form = document.getElementById('reason-form');
    function cleanup() {
      form.removeEventListener('submit', onSubmit);
      cancelButton.removeEventListener('click', onCancel);
      dialog.removeEventListener('close', onClose);
    }
    form.addEventListener('submit', onSubmit);
    cancelButton.addEventListener('click', onCancel);
    dialog.addEventListener('close', onClose);
    dialog.showModal();
    if (withReason) input.focus();
    else ok.focus();
  });
}

/**
 * 사유를 받고 요청을 보낸 뒤 다시 그린다. 실패하면 토스트로 알린다.
 * run(reason)은 서버 응답을 돌려주고, done(result)는 성공 뒤 할 일.
 */
export async function act(trigger, ask, run, done) {
  const reason = await askReason(ask);
  if (reason === null) return;
  if (trigger) trigger.disabled = true;
  try {
    const result = await run(reason);
    await done(result);
  } catch (err) {
    showError(err);
    if (trigger) trigger.disabled = false;
  }
}

// ----- 이름표 -----
export const MEMBER_STATUS = { ACTIVE: '활동', WITHDRAWN: '탈퇴' };
export const MEMBERSHIP_STATUS = { ACTIVE: '참여 중', SUSPENDED: '정지', LEFT: '나감', KICKED: '강퇴' };
export const ROLE = { OWNER: '블로그장', SUB_OWNER: '부블로그장', MEMBER: '회원' };
export const VISIBILITY = { PUBLIC: '공개', LINK_ONLY: '일부 공개', PRIVATE: '비공개' };
export const CONTENT_STATUS = { NORMAL: '보임', HIDDEN: '숨김', DELETED: '삭제됨' };
export const TARGET_TYPE = { MEMBER: '회원', BLOG: '블로그', POST: '글', COMMENT: '댓글', BLOG_OWNER: '블로그장' };

export function label(map, value) {
  return map[value] || value || '';
}
