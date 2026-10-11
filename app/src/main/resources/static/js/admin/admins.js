// 관리자 계정 (ADM-01). 새 관리자는 임시 비밀번호로 처음 들어와 바로 바꾼다.
import { api } from '../api.js';
import { PASSWORD_MESSAGE, PASSWORD_RULE } from '../rules.js';
import { el, errorLine, showError, table, td } from './common.js';

const LOGIN_ID_RULE = /^[a-z0-9._-]{4,30}$/;

function field(id, text, attrs) {
  const box = el('div');
  box.append(el('label', { for: id }, text), el('input', { id, name: id, ...attrs }));
  return box;
}

export async function mount(panel) {
  const listCard = el('section', { class: 'adm-card' });

  async function load() {
    let list;
    try {
      list = await api.get('/api/admin/admins');
    } catch (err) {
      listCard.replaceChildren(errorLine(err));
      return;
    }
    const rows = list.map((a) => {
      const tr = el('tr');
      tr.append(td(a.loginId, 'adm-nowrap'), td(a.name), td(a.createdAt, 'adm-nowrap'),
        td(a.lastLoginAt || '아직 없음', 'adm-nowrap'));
      return tr;
    });
    listCard.replaceChildren(el('h2', {}, '관리자'),
      table(['아이디', '이름', '추가한 날', '마지막 로그인'], rows, '관리자가 없습니다.'));
  }

  const formCard = el('section', { class: 'adm-card' });
  const form = el('form', { class: 'adm-form', novalidate: '' });
  const row = el('div', { class: 'adm-form-row' });
  row.append(
    field('new-loginId', '아이디', { type: 'text', maxlength: '30', autocomplete: 'off', autocapitalize: 'none', spellcheck: 'false' }),
    field('new-name', '이름', { type: 'text', maxlength: '50', autocomplete: 'off' }),
    field('new-tempPassword', '임시 비밀번호', { type: 'text', maxlength: '15', autocomplete: 'off' }),
  );
  const msg = el('p', { class: 'adm-error', role: 'alert' });
  const ok = el('p', { class: 'adm-ok-text', role: 'status' });
  form.append(row,
    el('p', { class: 'adm-note' }, '아이디는 영문 소문자·숫자·. _ - 로 4~30자입니다. ' + PASSWORD_MESSAGE),
    el('p', { class: 'adm-note' }, '새 관리자는 처음 로그인할 때 임시 비밀번호를 바꿔야 합니다.'),
    msg, ok, el('button', { type: 'submit', class: 'adm-go' }, '관리자 추가'));
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    msg.textContent = '';
    ok.textContent = '';
    const loginId = form.querySelector('#new-loginId').value.trim().toLowerCase();
    const name = form.querySelector('#new-name').value.trim();
    const tempPassword = form.querySelector('#new-tempPassword').value;
    if (!LOGIN_ID_RULE.test(loginId)) {
      msg.textContent = '아이디는 영문 소문자·숫자·. _ - 로 4~30자입니다.';
      return;
    }
    if (!name) {
      msg.textContent = '이름을 넣어 주세요.';
      return;
    }
    if (!PASSWORD_RULE.test(tempPassword)) {
      msg.textContent = PASSWORD_MESSAGE;
      return;
    }
    const b = form.querySelector('button[type=submit]');
    b.disabled = true;
    try {
      await api.post('/api/admin/admins', { loginId, name, tempPassword });
      form.reset();
      ok.textContent = `${loginId} 관리자를 추가했습니다. 임시 비밀번호를 따로 알려 주세요.`;
      await load();
    } catch (err) {
      showError(err, msg);
    } finally {
      b.disabled = false;
    }
  });
  formCard.append(el('h2', {}, '관리자 추가'), form);

  panel.replaceChildren(listCard, formCard);
  await load();
}
