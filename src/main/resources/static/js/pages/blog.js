import { api } from '../api.js';
import { JOIN_POLICY, VISIBILITY, roleName } from '../blog-card.js';
import { el, initLayout } from '../layout.js';

const me = await initLayout();

const $ = (id) => document.getElementById(id);
const slug = decodeURIComponent(location.pathname.split('/')[2] || '');
// 일부 공개 블로그의 공유 링크 값 (D-49)
const key = new URLSearchParams(location.search).get('key');

function showError(text) {
  $('blog').hidden = true;
  $('error').textContent = text;
  $('error').hidden = false;
}

function button(text, onClick, cls = '') {
  const b = el('button', { type: 'button', class: cls }, text);
  b.addEventListener('click', async () => {
    b.disabled = true;
    $('action-message').textContent = '';
    try {
      await onClick();
    } catch (err) {
      $('action-message').textContent = err.message;
      b.disabled = false;
    }
  });
  return b;
}

async function load() {
  let blog;
  try {
    blog = await api.get(`/api/blogs/${encodeURIComponent(slug)}` + (key ? '?key=' + encodeURIComponent(key) : ''));
  } catch (err) {
    if (err.code === 'SUSPENDED') {
      showError(`이 블로그에서 활동이 정지되어 ${err.details.suspendedUntil.replace('T', ' ')}까지 들어갈 수 없습니다.`);
    } else {
      showError(err.status === 404 ? '블로그를 찾을 수 없습니다. 주소를 확인하거나, 비공개 블로그라면 멤버만 볼 수 있습니다.' : err.message);
    }
    return;
  }
  document.title = blog.name + ' - 메인블로그';
  $('blog-name').textContent = blog.name;
  $('blog-address').textContent = '/blog/' + blog.slug;
  $('blog-description').textContent = blog.description || '';
  $('blog-tags').replaceChildren(...blog.tags.map((t) => el('li', {}, '#' + t)));
  $('blog-meta').textContent = `블로그장 ${blog.ownerNickname} · 멤버 ${blog.memberCount}명 · `
    + `${VISIBILITY[blog.visibility]} · ${JOIN_POLICY[blog.joinPolicy]} · ${blog.createdAt} 개설`;
  if (blog.status === 'CLOSING') {
    $('status-badge').textContent = '폐쇄 예정인 블로그입니다.';
    $('status-badge').hidden = false;
  }
  renderActions(blog);
  $('blog').hidden = false;
}

function renderActions(blog) {
  const actions = $('actions');
  actions.replaceChildren();
  const base = `/api/blogs/${encodeURIComponent(blog.slug)}`;

  if (!me) {
    actions.append(el('a', { class: 'button', href: '/login.html?next=' + encodeURIComponent(location.pathname + location.search) },
      '로그인하고 참여하기'));
    return;
  }
  if (blog.myRole) {
    actions.append(el('span', { class: 'badge' }, '내 역할: ' + roleName(blog.myRole)));
    if (blog.myRole === 'OWNER' || blog.myRole === 'SUB_OWNER') {
      actions.append(el('a', { class: 'button', href: `/blog/${encodeURIComponent(blog.slug)}/manage` }, '블로그 관리'));
    }
    if (blog.myRole !== 'OWNER') {
      actions.append(button('블로그 나가기', async () => {
        if (!confirm('이 블로그에서 나갈까요? 쓴 글의 작성자는 "탈퇴한 계정"으로 보입니다.')) {
          throw new Error('');
        }
        await api.post(base + '/leave');
        await load();
      }, 'danger-outline'));
    }
    return;
  }
  if (blog.status === 'CLOSING') return;
  if (blog.myRequest === 'PENDING') {
    actions.append(el('span', { class: 'badge' }, '참여 승인을 기다리는 중'));
    actions.append(button('신청 취소', async () => {
      await api.del(base + '/join');
      await load();
    }));
    return;
  }
  if (blog.myRequest === 'REJECTED') {
    actions.append(el('span', { class: 'muted' }, `참여 신청이 거절되었습니다. ${blog.canRequestAt}부터 다시 신청할 수 있습니다.`));
    return;
  }
  const label = blog.joinPolicy === 'FREE' ? '참여하기' : '참여 신청하기';
  actions.append(button(label, async () => {
    const res = await api.post(base + '/join', key ? { key } : {});
    await load();
    $('action-message').textContent = res.joined ? '멤버가 되었습니다.' : '참여를 신청했습니다. 블로그장이 승인하면 멤버가 됩니다.';
  }, 'primary inline'));
}

await load();
