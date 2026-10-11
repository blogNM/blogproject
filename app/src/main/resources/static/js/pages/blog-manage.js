import { api } from '../api.js';
import { el, requireLogin } from '../layout.js';
import { setupModeration } from './blog-moderation.js';
import { IMAGE_EXTENSIONS, IMAGE_MAX_BYTES, parseTags } from '../rules.js';
import { FONTS, SWATCHES, THEMES, isAccent } from '../theme.js';

const me = await requireLogin();

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

let moderation = null;

// 멤버·신고·블랙리스트·위임·폐쇄는 blog-moderation.js가 그린다.
function loadMembers() {
  return moderation ? moderation.refresh() : Promise.resolve();
}

// 카테고리 (BRD-03). 블로그장 또는 글 관리 권한이 있는 부블로그장.
async function loadCategories() {
  if (!blog.canManagePosts) return;
  const categories = await api.get(base + '/categories');
  const msg = $('category-message');
  const list = $('category-list');
  if (categories.length === 0) {
    list.replaceChildren(el('li', { class: 'muted' }, '아직 카테고리가 없습니다.'));
  } else {
    list.replaceChildren(...categories.map((c) => {
      const li = el('li', { class: 'list-row' });
      li.append(el('span', { class: 'strong' }, c.name), el('span', { class: 'muted' }, `글 ${c.postCount}개`));
      const rename = el('button', { type: 'button' }, '이름 바꾸기');
      rename.addEventListener('click', async () => {
        const name = prompt('새 이름', c.name);
        if (name === null) return;
        try {
          await api.put(`${base}/categories/${c.id}`, { name });
          msg.textContent = '';
          await loadCategories();
        } catch (err) {
          msg.textContent = err.message;
        }
      });
      const remove = el('button', { type: 'button', class: 'danger-outline' }, '삭제');
      remove.addEventListener('click', async () => {
        if (!confirm(`"${c.name}" 카테고리를 지울까요? 글은 "분류 없음"이 됩니다.`)) return;
        try {
          await api.del(`${base}/categories/${c.id}`);
          msg.textContent = '';
          await loadCategories();
        } catch (err) {
          msg.textContent = err.message;
        }
      });
      li.append(rename, remove);
      return li;
    }));
  }
  $('categories-section').hidden = false;
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

// ----- 테마 꾸미기 (D-89). 블로그장 또는 정보 수정 권한이 있는 부블로그장 -----
function setAccent(value) {
  $('accent').value = value.toUpperCase();
  $('accent-picker').value = value.toLowerCase();
  document.querySelectorAll('.swatch').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.color === value.toUpperCase())));
}

function chosenTheme() {
  return document.querySelector('input[name=theme]:checked').value;
}

function themeBody() {
  return {
    theme: chosenTheme(),
    accent: $('accent').value.trim(),
    font: $('font').value,
    heroTitle: $('hero-title').value,
    heroSubtitle: $('hero-subtitle').value,
  };
}

function renderCover(url) {
  $('cover-preview').replaceChildren(...(url ? [el('img', { src: url, alt: '머리 사진' })] : []));
  $('cover-remove').hidden = !url;
}

function fillTheme() {
  const t = blog.theme;
  $('theme-cards').replaceChildren(...Object.entries(THEMES).map(([value, info]) => {
    const label = el('label', { class: 'theme-card' });
    const input = el('input', { type: 'radio', name: 'theme', value });
    input.checked = value === t.theme;
    // 틀을 바꾸면 그 틀의 기본 색을 권한다. 저장 전에는 언제든 다른 색으로 바꿀 수 있다.
    input.addEventListener('change', () => setAccent(info.accent));
    label.append(input, el('img', { src: `/img/themes/${value.toLowerCase()}.svg`, alt: '', width: '240', height: '160' }),
      el('strong', {}, info.name), el('span', { class: 'muted' }, info.summary),
      el('span', { class: 'muted' }, info.tags.map((x) => '#' + x).join(' ')));
    return label;
  }));
  $('swatches').replaceChildren(...SWATCHES.map((c) => {
    const b = el('button', { type: 'button', class: 'swatch', 'data-color': c, 'aria-label': '색 ' + c, 'aria-pressed': 'false' });
    b.style.setProperty('background', c);
    b.addEventListener('click', () => setAccent(c));
    return b;
  }));
  $('font').replaceChildren(...Object.entries(FONTS).map(([value, name]) => el('option', { value }, name)));
  $('font').value = t.font;
  setAccent(t.accent);
  $('hero-title').value = t.heroTitle || '';
  $('hero-subtitle').value = t.heroSubtitle || '';
  renderCover(t.coverUrl);
  $('theme-section').hidden = false;
}

function setupTheme() {
  $('accent-picker').addEventListener('input', () => setAccent($('accent-picker').value));
  $('accent').addEventListener('change', () => {
    if (isAccent($('accent').value.trim())) setAccent($('accent').value.trim());
  });
  $('theme-preview').addEventListener('click', () => {
    const body = themeBody();
    if (!isAccent(body.accent)) {
      $('theme-message').textContent = '색은 #과 16진수 6자리로 골라 주세요.';
      return;
    }
    const q = new URLSearchParams({ preview: body.theme, accent: body.accent, font: body.font });
    if (body.heroTitle) q.set('heroTitle', body.heroTitle);
    if (body.heroSubtitle) q.set('heroSubtitle', body.heroSubtitle);
    window.open(`/blog/${encodeURIComponent(slug)}?${q}`, '_blank', 'noopener');
  });
  $('theme-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('theme-message');
    try {
      await api.put(base + '/theme', themeBody());
      blog = await api.get(base);
      msg.className = 'info';
      msg.textContent = '테마를 저장했어요. 블로그로 돌아가면 바뀐 모습을 볼 수 있어요.';
    } catch (err) {
      msg.className = 'error';
      msg.textContent = err.message;
    }
  });
  // 머리 사진도 글 사진과 같은 규칙으로 먼저 확인하고, 서버가 한 번 더 확인한다 (SEC-08).
  $('cover-file').addEventListener('change', async () => {
    const file = $('cover-file').files[0];
    const msg = $('theme-message');
    if (!file) return;
    const ext = file.name.split('.').pop().toLowerCase();
    if (!IMAGE_EXTENSIONS.includes(ext)) {
      msg.textContent = 'jpg·jpeg·png·gif·webp 사진만 올릴 수 있습니다.';
      return;
    }
    if (file.size > IMAGE_MAX_BYTES) {
      msg.textContent = '사진은 한 장에 3MB까지 올릴 수 있습니다.';
      return;
    }
    const form = new FormData();
    form.append('file', file);
    try {
      const res = await api.upload(base + '/cover', form);
      renderCover(res.coverUrl);
      msg.className = 'info';
      msg.textContent = '머리 사진을 바꿨어요.';
    } catch (err) {
      msg.className = 'error';
      msg.textContent = err.message;
    } finally {
      $('cover-file').value = '';
    }
  });
  $('cover-remove').addEventListener('click', async () => {
    if (!confirm('머리 사진을 뺄까요? 테마 기본 색으로 칠해집니다.')) return;
    await api.del(base + '/cover');
    renderCover(null);
  });
}

if (blog) {
  $('blog-name').textContent = blog.name;
  $('back-link').href = '/blog/' + encodeURIComponent(slug);
  $('manage').hidden = false;
  fillSettings();
  if (blog.canEditInfo) {
    fillTheme();
    setupTheme();
  }
  moderation = setupModeration({ blog, base, me, reloadBlog: () => api.get(base) });
  await Promise.all([loadRequests(), moderation.ready, loadCategories()]);

  $('category-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('category-message');
    try {
      await api.post(base + '/categories', { name: $('category-name').value });
      $('category-name').value = '';
      msg.textContent = '';
      await loadCategories();
    } catch (err) {
      msg.textContent = err.message;
    }
  });

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

// 블로그 알림 보관 기간 (블로그장만, 3.6 알림 표)
if (blog && blog.myRole === 'OWNER') {
  api.get(base + '/notification-retention').then((res) => {
    $('blog-retention').value = String(res.days);
    $('retention-section').hidden = false;
  }).catch(() => {});
  $('blog-notifications-clear').addEventListener('click', async () => {
    if (!confirm('이 블로그에서 생긴 블로그 알림을 받은 사람 모두에게서 지울까요? 되돌릴 수 없습니다.')) return;
    try {
      const res = await api.del(base + '/notifications');
      $('retention-message').textContent = `블로그 알림 ${res.deleted}개를 지웠어요.`;
    } catch (err) {
      $('retention-message').textContent = err.message;
    }
  });
  $('blog-retention').addEventListener('change', async () => {
    try {
      const res = await api.put(base + '/notification-retention', { days: Number($('blog-retention').value) });
      $('retention-message').textContent = `블로그 알림을 ${res.days}일 동안 보관합니다.`;
    } catch (err) {
      $('retention-message').textContent = err.message;
    }
  });
}
