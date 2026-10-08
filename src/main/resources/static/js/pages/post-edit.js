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
    toolbarItems: [['heading', 'bold', 'italic', 'strike'], ['hr', 'quote'], ['ul', 'ol'], ['table', 'image', 'link'],
      ['code', 'codeblock']],
    hooks: { addImageBlobHook: uploadImage },
  });

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
