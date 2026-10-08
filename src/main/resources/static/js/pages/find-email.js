import { api } from '../api.js';
import { el, initLayout } from '../layout.js';

await initLayout();

const $ = (id) => document.getElementById(id);

$('find-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('error').textContent = '';
  $('result').hidden = true;
  try {
    const res = await api.post('/api/auth/find-email', { name: $('name').value, phone: $('phone').value });
    if (res.results.length === 0) {
      $('error').textContent = '일치하는 회원 정보가 없습니다.';
      return;
    }
    const list = $('result-list');
    list.replaceChildren();
    res.results.forEach((r) => {
      const reset = el('button', { type: 'button' }, '비밀번호 재설정');
      reset.addEventListener('click', async () => {
        reset.disabled = true;
        try {
          const sent = await api.post('/api/auth/password/code', { findToken: r.resetToken });
          sessionStorage.setItem('findToken', r.resetToken);
          sessionStorage.setItem('findMasked', sent.maskedEmail);
          location.href = '/forgot-password.html';
        } catch (err) {
          $('error').textContent = err.message;
          reset.disabled = false;
        }
      });
      const row = el('li', { class: 'list-row' });
      list.append(row);
      row.append(
        el('span', { class: 'strong' }, r.maskedEmail),
        el('span', { class: 'muted' }, r.joinedAt + ' 가입'),
        reset,
      );
    });
    $('result').hidden = false;
  } catch (err) {
    $('error').textContent = err.message;
  }
});
