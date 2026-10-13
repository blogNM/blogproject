import { api } from '../api.js';
import { requireLogin } from '../layout.js';
import { createRichEditor } from '../rich-editor.js';
import {
  IMAGE_EXTENSIONS, IMAGE_MAX_BYTES, POST_CONTENT_MAX, POST_TAGS_MAX, POST_TITLE_MAX, contentLength, parseTags,
} from '../rules.js';

await requireLogin();

const $ = (id) => document.getElementById(id);
// /blog/{주소}/write, /blog/{주소}/posts/{번호}/edit, 또는 블로그를 고르는 /write (D-101)
const parts = location.pathname.split('/');
const chooseBlog = parts[1] === 'write';
let slug = chooseBlog ? '' : decodeURIComponent(parts[2] || '');
const postId = !chooseBlog && parts[3] === 'posts' ? Number(parts[4]) : null;
let blogBase;
let blogHref;
function setSlug(value) {
  slug = value;
  blogBase = `/api/blogs/${encodeURIComponent(slug)}`;
  blogHref = `/blog/${encodeURIComponent(slug)}`;
}
setSlug(slug);
// 1차 임시저장은 브라우저에만 (004 6.6)
// 블로그를 고르는 글쓰기는 블로그를 바꿔도 쓰던 글이 남도록 한 자리에 저장한다.
const DRAFT_KEY = chooseBlog ? 'post-draft:choose:new' : `post-draft:${slug}:${postId || 'new'}`;

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
// 글을 쓸 수 있는 블로그(만들었거나 참여한 블로그). 주소의 ?blog= 가 그중 하나면 그것을 먼저 고른다.
let myBlogs = [];
let loadError = null;
if (chooseBlog) {
  try {
    const mine = await api.get('/api/me/blogs');
    myBlogs = [...mine.owned, ...mine.joined].filter((b) => b.status === 'ACTIVE');
  } catch (err) {
    loadError = err;
  }
  const wanted = new URLSearchParams(location.search).get('blog');
  const first = myBlogs.find((b) => b.slug === wanted) || myBlogs[0];
  if (first) setSlug(first.slug);
}
try {
  if (loadError) throw loadError;
  if (chooseBlog && !slug) throw Object.assign(new Error(''), { noBlog: true });
  blog = await api.get(blogBase);
  if (postId) post = await api.get(`/api/posts/${postId}`);
} catch (err) {
  if (err.noBlog) {
    showError('글을 쓸 블로그가 없어요. 블로그를 만들거나 다른 블로그에 참여한 뒤 써 주세요.');
    const make = document.createElement('a');
    make.href = '/blog-new.html';
    make.className = 'button primary inline';
    make.textContent = '블로그 만들기';
    $('error').after(make);
  } else {
    showError(err.status === 404 ? '블로그나 글을 찾을 수 없습니다.' : err.message);
  }
}
if (blog && !blog.myRole) {
  showError('이 블로그의 멤버만 글을 쓸 수 있습니다.');
  blog = null;
}
if (post && !post.canEdit) {
  showError('글은 쓴 사람만 고칠 수 있습니다.');
  blog = null;
}

/** 고른 블로그에 맞춰 위쪽 블로그 이름, 취소 주소, 공지 칸, 카테고리를 다시 채운다. */
async function showBlog() {
  $('blog-link').textContent = blog.name;
  $('blog-link').href = blogHref;
  $('cancel').href = post ? `${blogHref}/posts/${post.id}` : blogHref;
  $('heading').textContent = post ? '글 수정' : '글쓰기';
  document.title = (post ? '글 수정' : '글쓰기') + ' - ' + blog.name;
  $('notice-box').hidden = !blog.canManagePosts;
  if (!blog.canManagePosts) $('notice').checked = false;

  const categories = await api.get(blogBase + '/categories').catch(() => []);
  const none = document.createElement('option');
  none.value = '';
  none.textContent = '분류 없음';
  $('category').replaceChildren(none, ...categories.map((c) => {
    const option = document.createElement('option');
    option.value = String(c.id);
    option.textContent = c.name;
    return option;
  }));
}

if (blog) {
  await showBlog();
  if (chooseBlog) {
    $('blog-select').replaceChildren(...myBlogs.map((b) => {
      const option = document.createElement('option');
      option.value = b.slug;
      option.textContent = b.myRole === 'OWNER' ? `${b.name} (내가 만든 블로그)` : b.name;
      return option;
    }));
    $('blog-select').value = slug;
    $('blog-choice').hidden = false;
    $('blog-select').addEventListener('change', async () => {
      const before = slug;
      const msg = $('form-message');
      msg.textContent = '';
      $('blog-select').disabled = true;
      try {
        setSlug($('blog-select').value);
        blog = await api.get(blogBase);
        if (!blog.myRole) throw new Error('이 블로그의 멤버만 글을 쓸 수 있습니다.');
        await showBlog();
        history.replaceState(null, '', '/write?blog=' + encodeURIComponent(slug));
      } catch (err) {
        setSlug(before);
        $('blog-select').value = before;
        blog = await api.get(blogBase).catch(() => blog);
        msg.textContent = err.message;
      } finally {
        $('blog-select').disabled = false;
      }
    });
  }

  // 보이는 그대로 쓰는 편집기 (D-106). 색·크기·형광펜이 쓰는 칸에 바로 보이고, HTML은 [HTML 보기]로 옆에 보인다.
  const rich = createRichEditor({
    host: $('editor'),
    height: '460px',
    placeholder: '여기에 글을 쓰세요. 위 버튼으로 꾸미고, 사진은 끌어다 놓거나 붙여 넣으면 올라갑니다.',
    uploadImage,
    withCode: true,
  });
  const { editor } = rich;

  /**
   * 번호 목록에서 Tab: 쓴 줄이면 다음 번호 줄을 만들고, 빈 번호 줄이면 번호를 지운다 (사용자 요청).
   * Enter는 에디터 기본 동작(다음 번호, 빈 줄에서 끝내기) 그대로다.
   */
  function listTab(e) {
    if (e.key !== 'Tab' || e.shiftKey || e.altKey || e.ctrlKey || e.metaKey || e.isComposing) return;
    const view = editor.wwEditor.view;
    const { state } = view;
    const { $from, empty } = state.selection;
    if (!empty) return;
    let depth = $from.depth;
    while (depth > 0 && $from.node(depth).type.name !== 'listItem') depth -= 1;
    if (!depth || $from.node(depth - 1).type.name !== 'orderedList') return;
    e.preventDefault();
    e.stopPropagation();
    if ($from.parent.content.size === 0) {
      editor.exec('outdent');
    } else {
      // 줄 끝으로 가서 Enter와 똑같이 다음 번호 줄을 만든다
      view.dispatch(state.tr.setSelection(state.selection.constructor.create(state.doc, $from.end())));
      view.someProp('handleKeyDown', (f) => f(view, new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13 })));
    }
    editor.focus();
  }

  function fill(values) {
    $('title').value = values.title || '';
    $('category').value = values.categoryId ? String(values.categoryId) : '';
    $('tags').value = (values.tags || []).join(', ');
    $('notice').checked = !!values.notice;
    rich.setMarkdown(values.content || '');
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

  // 편집기는 마크다운을 조금 다르게 적기도 한다(목록 기호 등). 같은 글인지는 편집기가 다시 적은 글로도 본다.
  let original = '';
  if (post) {
    fill({ title: post.title, content: post.contentMarkdown, categoryId: post.categoryId, tags: post.tags, notice: post.notice });
    original = editor.getMarkdown();
  }
  const draft = readDraft();
  if (draft && (draft.title || draft.content)
      && (!post || (draft.content !== post.contentMarkdown && draft.content !== original) || draft.title !== post.title)
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
  editor.getEditorElements().wwEditor.addEventListener('keydown', listTab, true);
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
