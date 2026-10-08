import { api } from '../api.js';
import { roleName } from '../blog-card.js';
import { el, requireLogin } from '../layout.js';
import { parseTags } from '../rules.js';

await requireLogin();

const $ = (id) => document.getElementById(id);
const slug = decodeURIComponent(location.pathname.split('/')[2] || '');
const base = `/api/blogs/${encodeURIComponent(slug)}`;

function showError(text) {
  $('manage').hidden = true;
  $('error').textContent = text;
  $('error').hidden = false;
}

let blog;
try {
  blog = await api.get(base);
} catch (err) {
  showError(err.message);
}

if (blog && blog.myRole !== 'OWNER' && blog.myRole !== 'SUB_OWNER') {
  showError('이 블로그를 관리할 권한이 없습니다.');
  blog = null;
}

function formatTime(value) {
  return value.replace('T', ' ').substring(0, 16);
}

// 참여 신청 승인·거절 (BLG-05). 권한이 없으면 서버가 403을 준다.
async function loadRequests() {
  let requests;
  try {
    requests = await api.get(base + '/join-requests');
  } catch {
    $('requests-section').hidden = true;
    return;
  }
  const list = $('requests');
  if (requests.length === 0) {
    list.replaceChildren(el('li', { class: 'muted' }, '기다리는 참여 신청이 없습니다.'));
  } else {
    list.replaceChildren(...requests.map((r) => {
      const li = el('li', { class: 'list-row' });
      const decide = (action, label, cls) => {
        const b = el('button', { type: 'button', class: cls }, label);
        b.addEventListener('click', async () => {
          b.disabled = true;
          try {
            await api.post(`${base}/join-requests/${r.id}/${action}`);
            await Promise.all([loadRequests(), loadMembers()]);
          } catch (err) {
            alert(err.message);
            b.disabled = false;
          }
        });
        return b;
      };
      li.append(el('span', { class: 'strong' }, r.nickname), el('span', { class: 'muted' }, formatTime(r.requestedAt) + ' 신청'),
        decide('approve', '승인', 'primary inline'), decide('reject', '거절', ''));
      return li;
    }));
  }
  $('requests-section').hidden = false;
}

async function loadMembers() {
  let members;
  try {
    members = await api.get(base + '/members');
  } catch {
    $('members-section').hidden = true;
    return;
  }
  $('members').replaceChildren(...members.map((m) => {
    const tr = el('tr');
    [m.nickname, m.name || '', m.email || '', m.phone || '', roleName(m.role), m.joinedAt]
      .forEach((v) => tr.append(el('td', {}, v)));
    return tr;
  }));
  $('members-section').hidden = false;
}

function shareUrl(token) {
  return `${location.origin}/blog/${encodeURIComponent(slug)}?key=${token}`;
}

function fillSettings() {
  $('name').value = blog.name;
  $('description').value = blog.description || '';
  $('tags').value = blog.tags.join(', ');
  const owner = blog.myRole === 'OWNER';
  $('owner-settings').hidden = !owner;
  if (owner) {
    document.querySelector(`input[name=visibility][value=${blog.visibility}]`).checked = true;
    document.querySelector(`input[name=joinPolicy][value=${blog.joinPolicy}]`).checked = true;
  }
  $('share-box').hidden = !(owner && blog.visibility === 'LINK_ONLY');
  if (blog.shareToken) $('share-url').value = shareUrl(blog.shareToken);
  $('settings-section').hidden = false;
}

if (blog) {
  $('blog-name').textContent = blog.name;
  $('back-link').href = '/blog/' + encodeURIComponent(slug);
  $('manage').hidden = false;
  fillSettings();
  await Promise.all([loadRequests(), loadMembers()]);

  $('settings-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('settings-message');
    const body = { name: $('name').value, description: $('description').value, tags: parseTags($('tags').value) };
    if (blog.myRole === 'OWNER') {
      body.visibility = document.querySelector('input[name=visibility]:checked').value;
      body.joinPolicy = document.querySelector('input[name=joinPolicy]:checked').value;
    }
    try {
      await api.put(base, body);
      blog = await api.get(base);
      fillSettings();
      $('blog-name').textContent = blog.name;
      msg.className = 'info';
      msg.textContent = '저장했습니다.';
    } catch (err) {
      msg.className = 'error';
      msg.textContent = err.message;
    }
  });

  $('regenerate').addEventListener('click', async () => {
    if (!confirm('새 링크를 만들면 지금까지 나눠 준 링크로는 들어올 수 없습니다. 계속할까요?')) return;
    const res = await api.post(base + '/share-link');
    $('share-url').value = shareUrl(res.shareToken);
  });
}
