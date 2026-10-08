import { api } from '../api.js';
import { requireLogin } from '../layout.js';
import {
  IMAGE_EXTENSIONS, IMAGE_MAX_BYTES, POST_CONTENT_MAX, POST_TAGS_MAX, POST_TITLE_MAX, contentLength, parseTags,
} from '../rules.js';

await requireLogin();

const $ = (id) => document.getElementById(id);
// /blog/{주소}/write 또는 /blog/{주소}/posts/{번호}/edit
const parts = location.pathname.split('/');
const slug = decodeURIComponent(parts[2] || '');
const postId = parts[3] === 'posts' ? Number(parts[4]) : null;
const blogBase = `/api/blogs/${encodeURIComponent(slug)}`;
const blogHref = `/blog/${encodeURIComponent(slug)}`;
// 글자 색 7가지. 서버의 MarkdownRenderer.TEXT_COLORS와 같다.
const TEXT_COLORS = [['red', '빨강'], ['orange', '주황'], ['green', '초록'], ['blue', '파랑'], ['purple', '보라'],
  ['mauve', '자주'], ['gray', '회색']];
// 1차 임시저장은 브라우저에만 (004 6.6)
const DRAFT_KEY = `post-draft:${slug}:${postId || 'new'}`;

function showError(text) {
  $('post-form').hidden = true;
  $('error').textContent = text;
  $('error').hidden = false;
}

function readDraft() {
  try {
    return JSON.parse(localStorage.getItem(DRAFT_KEY));
  } catch {
    return null;
  }
}

function writeDraft(draft) {
  try {
    localStorage.setItem(DRAFT_KEY, JSON.stringify(draft));
  } catch {
    // 저장소를 못 쓰는 브라우저(사생활 보호 창 등)에서는 자동 저장 없이 쓴다.
  }
}

function clearDraft() {
  try {
    localStorage.removeItem(DRAFT_KEY);
  } catch {
    // 위와 같음
  }
}

let blog;
let post = null;
try {
  blog = await api.get(blogBase);
  if (postId) post = await api.get(`/api/posts/${postId}`);
} catch (err) {
  showError(err.status === 404 ? '블로그나 글을 찾을 수 없습니다.' : err.message);
}
if (blog && !blog.myRole) {
  showError('이 블로그의 멤버만 글을 쓸 수 있습니다.');
  blog = null;
}
if (post && !post.canEdit) {
  showError('글은 쓴 사람만 고칠 수 있습니다.');
  blog = null;
}

if (blog) {
  $('blog-link').textContent = blog.name;
  $('blog-link').href = blogHref;
  $('cancel').href = post ? `${blogHref}/posts/${post.id}` : blogHref;
  $('heading').textContent = post ? '글 수정' : '글쓰기';
  document.title = (post ? '글 수정' : '글쓰기') + ' - ' + blog.name;
  $('notice-box').hidden = !blog.canManagePosts;

  const categories = await api.get(blogBase + '/categories').catch(() => []);
  categories.forEach((c) => {
    const option = document.createElement('option');
    option.value = String(c.id);
    option.textContent = c.name;
    $('category').append(option);
  });

  const editor = new window.toastui.Editor({
    el: $('editor'),
    height: '460px',
    initialEditType: 'markdown',
    previewStyle: window.innerWidth > 900 ? 'vertical' : 'tab',
    hideModeSwitch: true,
    language: 'ko-KR',
    usageStatistics: false,
    placeholder: '마크다운으로 쓰세요. 사진은 끌어다 놓거나 붙여 넣으면 올라갑니다.',
    toolbarItems: [['heading', 'bold', 'italic', 'strike', colorButton()], ['hr', 'quote'], ['ul', 'ol'],
      ['table', 'image', 'link'], ['code', 'codeblock']],
    hooks: { addImageBlobHook: uploadImage },
  });

  // 글자 색 (사용자 요청 2026-10-08). 고른 글자를 <span class="tc-색">으로 감싼다.
  // 서버는 정해 둔 색 class만 남기고 나머지는 지운다 (MarkdownRenderer.TEXT_COLORS). CSP 때문에 style 속성은 쓰지 않는다.
  function colorButton() {
    const body = document.createElement('div');
    body.className = 'color-popup';
    TEXT_COLORS.forEach(([code, name]) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'color-swatch tc-bg-' + code;
      b.title = name;
      b.setAttribute('aria-label', name);
      b.addEventListener('click', () => paint(code));
      body.append(b);
    });
    const clear = document.createElement('button');
    clear.type = 'button';
    clear.className = 'color-clear';
    clear.textContent = '색 빼기';
    clear.addEventListener('click', () => paint(null));
    body.append(clear);
    return { name: 'color', tooltip: '글자 색', text: 'A', className: 'toolbar-color', popup: { body, style: { width: 'auto' } } };
  }

  function paint(code) {
    const selected = editor.getSelectedText();
    const plain = selected.replace(/<span class="tc-[a-z]+">|<\/span>/g, '');
    if (code) editor.replaceSelection(`<span class="tc-${code}">${plain || '글자'}</span>`);
    else editor.replaceSelection(plain);
    editor.eventEmitter.emit('closePopup');
    editor.focus();
  }

  /**
   * 번호 목록에서 Tab: 쓴 줄이면 다음 번호 줄을 만들고, 빈 번호 줄이면 번호를 지운다 (사용자 요청).
   * Enter는 에디터 기본 동작(다음 번호, 빈 줄에서 끝내기) 그대로다.
   */
  function listTab(e) {
    if (e.key !== 'Tab' || e.shiftKey || e.altKey || e.ctrlKey || e.metaKey || e.isComposing) return;
    const [start, end] = editor.getSelection();
    if (start[0] !== end[0] || start[1] !== end[1]) return;
    const line = start[0];
    const text = editor.getMarkdown().split('\n')[line - 1] ?? '';
    const m = /^(\s*)(\d+)([.)])(\s+)(.*)$/.exec(text) || /^(\s*)(\d+)([.)])()()$/.exec(text);
    if (!m) return;
    e.preventDefault();
    e.stopPropagation();
    const [, indent, num, mark, , body] = m;
    const lineEnd = [line, text.length + 1];
    if (!body.trim()) {
      editor.replaceSelection(indent, [line, 1], lineEnd);
      editor.setSelection([line, indent.length + 1]);
    } else {
      const next = `${indent}${Number(num) + 1}${mark} `;
      editor.replaceSelection('\n' + next, lineEnd, lineEnd);
      editor.setSelection([line + 1, next.length + 1]);
    }
    editor.focus();
  }

  function fill(values) {
    $('title').value = values.title || '';
    $('category').value = values.categoryId ? String(values.categoryId) : '';
    $('tags').value = (values.tags || []).join(', ');
    $('notice').checked = !!values.notice;
    editor.setMarkdown(values.content || '', false);
  }

  function current() {
    return {
      title: $('title').value,
      content: editor.getMarkdown(),
      categoryId: $('category').value ? Number($('category').value) : null,
      tags: parseTags($('tags').value),
      notice: $('notice').checked,
    };
  }

  if (post) {
    fill({ title: post.title, content: post.contentMarkdown, categoryId: post.categoryId, tags: post.tags, notice: post.notice });
  }
  const draft = readDraft();
  if (draft && (draft.title || draft.content)
      && (!post || draft.content !== post.contentMarkdown || draft.title !== post.title)
      && confirm(`${draft.savedAt}에 자동 저장된 글이 있습니다. 불러올까요?`)) {
    fill(draft);
    $('draft-message').textContent = '자동 저장된 글을 불러왔습니다.';
    $('draft-message').hidden = false;
  }

  function updateLength() {
    const n = contentLength(editor.getMarkdown());
    $('length').textContent = String(n);
    $('length').className = n > POST_CONTENT_MAX ? 'error' : '';
  }
  updateLength();

  let saveTimer = null;
  function scheduleDraft() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      const now = new Date();
      writeDraft({ ...current(), savedAt: `${now.getMonth() + 1}월 ${now.getDate()}일 ${now.toTimeString().substring(0, 5)}` });
    }, 1000);
  }
  editor.on('change', () => { updateLength(); scheduleDraft(); });
  editor.getEditorElements().mdEditor.addEventListener('keydown', listTab, true);
  ['title', 'tags', 'category', 'notice'].forEach((id) => $(id).addEventListener('input', scheduleDraft));

  async function uploadImage(blob, callback) {
    const msg = $('form-message');
    msg.textContent = '';
    const type = (blob.type || '').split('/')[1] || '';
    const name = blob.name || `image.${type}`;
    const ext = name.includes('.') ? name.split('.').pop().toLowerCase() : '';
    if (!IMAGE_EXTENSIONS.includes(ext)) {
      msg.textContent = 'jpg·jpeg·png·gif·webp 사진만 올릴 수 있습니다.';
      return;
    }
    if (blob.size > IMAGE_MAX_BYTES) {
      msg.textContent = '사진은 한 장에 3MB까지 올릴 수 있습니다.';
      return;
    }
    const form = new FormData();
    form.append('file', blob, name);
    try {
      const res = await api.upload(blogBase + '/images', form);
      callback(res.url, name.replace(/\.[^.]+$/, '').replace(/[[\]]/g, ''));
    } catch (err) {
      msg.textContent = err.message;
    }
  }

  $('post-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('form-message');
    const body = current();
    const titleLength = [...body.title.trim()].length;
    if (titleLength < 1 || titleLength > POST_TITLE_MAX) {
      msg.textContent = `제목은 1~${POST_TITLE_MAX}자로 써 주세요.`;
      return;
    }
    if (!body.content.trim()) {
      msg.textContent = '본문을 써 주세요.';
      return;
    }
    if (contentLength(body.content) > POST_CONTENT_MAX) {
      msg.textContent = `본문은 ${POST_CONTENT_MAX}자까지 쓸 수 있습니다.`;
      return;
    }
    if (body.tags.length > POST_TAGS_MAX) {
      msg.textContent = `태그는 ${POST_TAGS_MAX}개까지 달 수 있습니다.`;
      return;
    }
    $('save').disabled = true;
    try {
      let id = postId;
      if (post) {
        await api.put(`/api/posts/${post.id}`, body);
      } else {
        id = (await api.post(blogBase + '/posts', body)).id;
      }
      clearTimeout(saveTimer);
      clearDraft();
      location.href = `${blogHref}/posts/${id}`;
    } catch (err) {
      msg.textContent = err.message;
      $('save').disabled = false;
    }
  });

  $('post-form').hidden = false;
}
