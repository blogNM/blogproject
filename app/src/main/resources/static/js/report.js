// 신고 창 (SOC-06). 회원·블로그·글·댓글을 사유를 골라 신고한다.
// 같은 대상은 2주에 한 번. 기타는 내용을 꼭 쓴다 (추천안 #9).
import { api } from './api.js';
import { el, toast } from './layout.js';

export const REASONS = [
  ['SPAM', '스팸·광고'],
  ['ABUSE', '욕설·괴롭힘'],
  ['OBSCENE', '음란·선정적인 내용'],
  ['PRIVACY', '개인정보 노출'],
  ['COPYRIGHT', '저작권 침해'],
  ['ETC', '기타'],
];

const LABELS = { MEMBER: '회원', BLOG: '블로그', POST: '글', COMMENT: '댓글' };

/** target: { targetType, targetId?, slug?, key? }. 로그인하지 않았으면 로그인 화면으로 보낸다. */
export function openReport(target, loggedIn) {
  if (!loggedIn) {
    location.href = '/login.html?next=' + encodeURIComponent(location.pathname + location.search);
    return;
  }
  const dialog = el('dialog', { class: 'report-dialog', 'aria-label': '신고하기' });
  const form = el('form', { method: 'dialog' });
  form.append(el('h2', {}, `${LABELS[target.targetType]} 신고`),
    el('p', { class: 'muted' }, '신고 사유를 골라 주세요. 같은 대상은 2주에 한 번 신고할 수 있어요.'));
  const choices = el('fieldset', { class: 'report-reasons' });
  choices.append(el('legend', { class: 'visually-hidden' }, '신고 사유'));
  REASONS.forEach(([code, name], i) => {
    const label = el('label', { class: 'checkbox' });
    const radio = el('input', { type: 'radio', name: 'reason', value: code });
    if (i === 0) radio.checked = true;
    label.append(radio, document.createTextNode(name));
    choices.append(label);
  });
  const detail = el('textarea', { maxlength: '200', rows: '3', placeholder: '자세한 내용 (기타는 꼭 써 주세요, 200자까지)',
    'aria-label': '신고 내용' });
  const error = el('p', { class: 'error', role: 'alert' });
  const actions = el('div', { class: 'actions' });
  const cancel = el('button', { type: 'button' }, '취소');
  const submit = el('button', { type: 'submit', class: 'dark' }, '신고하기');
  actions.append(cancel, submit);
  form.append(choices, detail, error, actions);
  dialog.append(form);
  document.body.append(dialog);

  const closeDialog = () => { dialog.close(); dialog.remove(); };
  cancel.addEventListener('click', closeDialog);
  dialog.addEventListener('cancel', () => dialog.remove());
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const reason = form.querySelector('input[name=reason]:checked').value;
    if (reason === 'ETC' && !detail.value.trim()) {
      error.textContent = '기타 사유는 내용을 써 주세요.';
      return;
    }
    submit.disabled = true;
    try {
      const res = await api.post('/api/reports', { ...target, reason, detail: detail.value });
      closeDialog();
      toast(res.handler === 'ADMIN' ? '신고를 접수했어요. 운영진이 확인합니다.' : '신고를 접수했어요. 블로그장이 확인합니다.');
    } catch (err) {
      error.textContent = err.message;
      submit.disabled = false;
    }
  });
  dialog.showModal();
}
