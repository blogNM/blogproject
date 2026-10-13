// 메인 공지사항 (BRD-10, ADM). 쓰면 공지 알림을 끈 회원을 뺀 모든 회원에게 알림이 간다.
// 본문은 마크다운이고, 보여 줄 때 서버가 HTML로 바꿔 걸러 준다.
import { api } from '../api.js';
import { NOTICE_CATEGORIES, categoryBadge } from '../notice-category.js';
import { renderPager } from '../pager.js';
import { act, button, el, errorLine, link, showError, table, td, toast } from './common.js';
import { noticeEditor } from './notice-editor.js';

let page = 1;

export async function mount(panel) {
  const listCard = el('section', { class: 'adm-card' });
  const formCard = el('section', { class: 'adm-card' });
  let editingId = null;

  const heading = el('h2', {}, '공지 쓰기');
  const form = el('form', { class: 'adm-form', novalidate: '' });
  const title = el('input', { id: 'notice-title', type: 'text', maxlength: '100', autocomplete: 'off' });
  const category = el('select', { id: 'notice-category' });
  category.append(...NOTICE_CATEGORIES.map(([value, label]) => el('option', { value }, label)));
  const msg = el('p', { class: 'adm-error', role: 'alert' });
  let editor;
  try {
    editor = await noticeEditor(msg);
  } catch (err) {
    showError(err);
    return;
  }
  const submit = el('button', { type: 'submit', class: 'adm-go' }, '공지 올리기');
  const cancel = button('수정 그만두기', '', () => reset());
  cancel.hidden = true;
  const buttons = el('div', { class: 'adm-actions' });
  buttons.append(submit, cancel);
  form.append(el('label', { for: 'notice-category' }, '카테고리'), category,
    el('label', { for: 'notice-title' }, '제목'), title,
    el('label', { id: 'notice-content-label' }, '내용'), editor.element,
    el('p', { class: 'adm-note' }, '새 공지는 공지 알림을 끄지 않은 모든 회원에게 알림이 갑니다. 고칠 때는 알림을 다시 보내지 않아요.'),
    msg, buttons);
  formCard.append(heading, form);

  function reset() {
    editingId = null;
    form.reset();
    editor.reset();
    msg.textContent = '';
    heading.textContent = '공지 쓰기';
    submit.textContent = '공지 올리기';
    cancel.hidden = true;
  }

  async function startEdit(id) {
    try {
      const n = await api.get(`/api/notices/${id}`);
      editingId = id;
      title.value = n.title;
      category.value = n.category;
      editor.reset();
      editor.setValue(n.content);
      heading.textContent = `공지 #${id} 고치기`;
      submit.textContent = '고친 내용 저장';
      cancel.hidden = false;
      formCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
      title.focus();
    } catch (err) {
      showError(err);
    }
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    msg.textContent = '';
    const body = { title: title.value.trim(), content: editor.getValue().trim(), category: category.value };
    if (!body.title || body.title.length > 100) {
      msg.textContent = '제목은 1~100자로 써 주세요.';
      return;
    }
    if (!body.content) {
      msg.textContent = '내용을 써 주세요.';
      return;
    }
    if (body.content.length > 5000) {
      msg.textContent = '내용은 5000자까지 쓸 수 있습니다.';
      return;
    }
    submit.disabled = true;
    try {
      if (editingId) {
        await api.put(`/api/admin/notices/${editingId}`, body);
        toast('공지를 고쳤습니다.');
      } else {
        await api.post('/api/admin/notices', body);
        toast('공지를 올리고 회원들에게 알림을 보냈습니다.');
        page = 1;
      }
      reset();
      await load();
    } catch (err) {
      showError(err, msg);
    } finally {
      submit.disabled = false;
    }
  });

  async function load() {
    let data;
    try {
      data = await api.get('/api/admin/notices?page=' + page);
    } catch (err) {
      listCard.replaceChildren(errorLine(err));
      return;
    }
    const rows = data.items.map((n) => {
      const tr = el('tr');
      const tools = el('div', { class: 'adm-actions' });
      const del = button('삭제', 'adm-danger', () => act(del,
        { title: `"${n.title}" 공지 삭제`, message: '공지 목록과 메인 화면에서 빠집니다.', danger: true, okText: '삭제', withReason: false },
        () => api.del(`/api/admin/notices/${n.id}`),
        async () => { toast('공지를 지웠습니다.'); if (editingId === n.id) reset(); await load(); }));
      tools.append(button('고치기', '', () => startEdit(n.id)), del);
      tr.append(td('#' + n.id, 'adm-nowrap'), td(categoryBadge(n.category), 'adm-nowrap'),
        td(link('/notices/' + n.id, n.title), 'adm-wrap'),
        td(n.createdAt, 'adm-nowrap'), td(tools));
      return tr;
    });
    const pager = el('nav', { class: 'pager' });
    renderPager(pager, data.page, data.totalPages, (p) => { page = p; load(); });
    listCard.replaceChildren(el('h2', {}, '공지사항'), el('p', { class: 'adm-total' }, `모두 ${data.total}개`),
      table(['번호', '카테고리', '제목', '올린 날', '조치'], rows, '아직 공지가 없습니다.'), pager);
  }

  panel.replaceChildren(formCard, listCard);
  await load();
}
