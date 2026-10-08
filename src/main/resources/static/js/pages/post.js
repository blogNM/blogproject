import { api, ApiError } from '../api.js';
import { suspendedText } from '../blog-card.js';
import { el, initLayout, renderNotFound, toast } from '../layout.js';
import { openReport } from '../report.js';
import { applyBlogLook } from '../theme.js';

const me = await initLayout();

const $ = (id) => document.getElementById(id);
const [, , rawSlug, , rawId] = location.pathname.split('/');
const slug = decodeURIComponent(rawSlug || '');
const postId = Number(rawId);
// 일부 공개 블로그의 공유 링크 값 (D-49)
const key = new URLSearchParams(location.search).get('key');
const blogHref = `/blog/${encodeURIComponent(slug)}` + (key ? '?key=' + encodeURIComponent(key) : '');
const keyQuery = key ? '?key=' + encodeURIComponent(key) : '';

function showError(text) {
  $('post').hidden = true;
  $('error').textContent = text;
  $('error').hidden = false;
}

let post;
try {
  post = await api.get(`/api/posts/${postId}` + (key ? '?key=' + encodeURIComponent(key) : ''));
} catch (err) {
  if (err.code === 'SUSPENDED') {
    showError(suspendedText(err.details));
  } else {
    if (err.status === 404) {
      renderNotFound('글을 찾을 수 없어요. 지워졌거나 볼 수 없는 글이에요.');
    } else {
      showError(err.message);
    }
  }
}

if (post && post.blogSlug !== slug) {
  location.replace(`/blog/${encodeURIComponent(post.blogSlug)}/posts/${post.id}` + location.search);
} else if (post) {
  document.title = `${post.title} - ${post.blogName}`;
  $('blog-link').textContent = post.blogName;
  $('blog-link').href = blogHref;
  $('notice-badge').hidden = !post.notice;
  if (post.categoryName) {
    $('category').textContent = post.categoryName;
    $('category').hidden = false;
  }
  $('title').textContent = post.title;
  const author = post.authorId ? el('a', { href: '/members/' + post.authorId, class: 'author-link' }, post.authorName)
    : document.createTextNode(post.authorName);
  $('meta').replaceChildren(author,
    document.createTextNode(` · ${post.createdAt}` + (post.updatedAt ? ` (수정 ${post.updatedAt})` : '')));
  $('content').innerHTML = post.contentHtml;
  $('tags').replaceChildren(...post.tags.map((t) => {
    const li = el('li');
    li.append(el('a', { href: `${blogHref}${key ? '&' : '?'}tag=${encodeURIComponent(t)}` }, '#' + t));
    return li;
  }));
  $('counts').textContent = `조회 ${post.counts.views} · 댓글 ${post.counts.comments}`;
  api.get(`/api/blogs/${encodeURIComponent(slug)}${keyQuery}`).then((blog) => applyBlogLook(blog.theme)).catch(() => {});
  setupLike();
  setupShare();
  loadComments();

  const actions = $('actions');
  if (post.canEdit) {
    actions.append(el('a', { class: 'button', href: `/blog/${encodeURIComponent(slug)}/posts/${post.id}/edit` }, '수정'));
  }
  if (post.canDelete) {
    const del = el('button', { type: 'button', class: 'danger-outline' }, '삭제');
    del.addEventListener('click', async () => {
      if (!confirm('글을 정말 삭제하시겠습니까?')) return;
      del.disabled = true;
      try {
        await api.del(`/api/posts/${post.id}`);
        location.replace(blogHref);
      } catch (err) {
        alert(err.message);
        del.disabled = false;
      }
    });
    actions.append(del);
  }
  // 내 글이 아니면 신고 (SOC-06). 블로그장이 쓴 글은 메인 관리자에게 간다.
  if (!post.canEdit) {
    const report = el('button', { type: 'button', class: 'link-button muted-link' }, '신고');
    report.addEventListener('click', () => openReport({ targetType: 'POST', targetId: post.id, key }, !!me));
    $('post-actions-extra').append(report);
  }
  $('post').hidden = false;
}

/** 인스타그램처럼 하트가 커졌다 돌아오고, 작은 하트들이 둘레로 퍼진다. */
function heartBurst(button) {
  const heart = button.querySelector('.heart');
  heart.classList.remove('pop');
  void heart.offsetWidth; // 연달아 눌러도 처음부터 다시
  heart.classList.add('pop');
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  for (let i = 0; i < 7; i += 1) {
    const angle = (Math.PI * 2 * i) / 7 - Math.PI / 2;
    const bit = el('span', { class: 'heart-bit', 'aria-hidden': 'true' }, '♥');
    bit.style.setProperty('--dx', `${Math.cos(angle) * 30}px`);
    bit.style.setProperty('--dy', `${Math.sin(angle) * 30}px`);
    heart.append(bit);
    bit.addEventListener('animationend', () => bit.remove());
  }
}

// ----- 좋아요 (BRD-06): 누르면 좋아요, 한 번 더 누르면 취소 -----
function setupLike() {
  const button = $('like');
  const render = (liked, likes) => {
    button.setAttribute('aria-pressed', String(liked));
    $('like-count').textContent = String(likes);
  };
  render(post.liked, post.counts.likes);
  button.addEventListener('click', async () => {
    if (!me) {
      location.href = '/login.html?next=' + encodeURIComponent(location.pathname + location.search);
      return;
    }
    button.disabled = true;
    // 누르는 순간 하트가 튀어 오른다. 취소할 때는 효과 없음
    if (button.getAttribute('aria-pressed') !== 'true') heartBurst(button);
    try {
      const res = await api.post(`/api/posts/${post.id}/like${keyQuery}`);
      render(res.liked, res.likes);
    } catch (err) {
      toast(err.message);
    } finally {
      button.disabled = false;
    }
  });
}

// ----- 공유 (BRD-07): 1차는 링크 복사. 일부 공개 블로그는 공유 키까지 붙인다 -----
function setupShare() {
  $('share').addEventListener('click', async () => {
    const url = location.origin + `/blog/${encodeURIComponent(post.blogSlug)}/posts/${post.id}${keyQuery}`;
    try {
      await navigator.clipboard.writeText(url);
      toast('글 주소를 복사했어요.');
    } catch {
      prompt('아래 주소를 복사해 주세요.', url);
    }
  });
}

// ----- 댓글 (BRD-06, US5). 내용·닉네임은 모두 textContent -----
async function loadComments() {
  $('comments').hidden = false;
  $('comment-form').hidden = !me;
  $('comment-login').hidden = !!me;
  let comments;
  try {
    comments = await api.get(`/api/posts/${post.id}/comments${keyQuery}`);
  } catch (err) {
    $('comment-list').replaceChildren(el('li', { class: 'muted' }, err.message));
    return;
  }
  const total = comments.reduce((n, c) => n + (c.deleted ? 0 : 1) + c.replies.length, 0);
  $('comment-count').textContent = String(total);
  $('counts').textContent = `조회 ${post.counts.views} · 댓글 ${total}`;
  $('comment-list').replaceChildren(...comments.map((c) => commentItem(c, c.id)));
  // 알림에서 눌러 들어오면 그 댓글로 내려간다.
  const target = /^#comment-\d+$/.test(location.hash) ? document.querySelector(location.hash) : null;
  if (target) {
    target.classList.add('focused');
    target.scrollIntoView({ block: 'center' });
  }
}

function commentItem(c, rootId) {
  const li = el('li', { class: c.deleted ? 'comment deleted' : 'comment', id: 'comment-' + c.id });
  if (!c.deleted) {
    const head = el('div', { class: 'comment-head' });
    head.append(c.authorId ? el('a', { class: 'comment-author', href: '/members/' + c.authorId }, c.authorName)
      : el('span', { class: 'comment-author' }, c.authorName), el('span', { class: 'muted' }, c.createdAt));
    if (c.edited) head.append(el('span', { class: 'muted' }, '수정됨'));
    li.append(head);
  }
  const body = el('p', { class: 'comment-body' });
  if (c.replyTo) body.append(el('span', { class: 'mention' }, '@' + c.replyTo));
  body.append(document.createTextNode(c.content));
  li.append(body);

  if (!c.deleted) {
    const tools = el('div', { class: 'comment-tools' });
    if (me) {
      const reply = el('button', { type: 'button' }, '답글');
      reply.addEventListener('click', () => openReply(li, c));
      tools.append(reply);
    }
    if (c.mine) {
      const edit = el('button', { type: 'button' }, '수정');
      edit.addEventListener('click', () => openEdit(li, body, c));
      tools.append(edit);
    }
    if (c.canDelete) {
      const del = el('button', { type: 'button' }, '삭제');
      del.addEventListener('click', async () => {
        if (!confirm('댓글을 삭제할까요?')) return;
        try {
          await api.del(`/api/comments/${c.id}${keyQuery}`);
          loadComments();
        } catch (err) {
          toast(err.message);
        }
      });
      tools.append(del);
    }
    if (!c.mine) {
      const report = el('button', { type: 'button' }, '신고');
      report.addEventListener('click', () => openReport({ targetType: 'COMMENT', targetId: c.id, key }, !!me));
      tools.append(report);
    }
    li.append(tools);
  }
  if (c.replies && c.replies.length) {
    const ul = el('ul', { class: 'replies' });
    ul.append(...c.replies.map((r) => commentItem(r, rootId)));
    li.append(ul);
  }
  return li;
}

/** 답글에 다시 답하면 서버가 같은 줄기에 붙이고 @닉네임을 단다 (D-78). */
function openReply(li, c) {
  if (li.querySelector(':scope > .reply-form')) return;
  const form = miniForm(`@${c.authorName}에게 답글`, '', async (content) => {
    await api.post(`/api/posts/${post.id}/comments${keyQuery}`, { content, parentId: c.id });
    loadComments();
  });
  li.append(form);
  form.querySelector('textarea').focus();
}

function openEdit(li, body, c) {
  if (li.querySelector(':scope > .reply-form')) return;
  body.hidden = true;
  const form = miniForm('댓글 수정', c.content, async (content) => {
    await api.put(`/api/comments/${c.id}${keyQuery}`, { content });
    loadComments();
  }, () => { body.hidden = false; });
  li.append(form);
}

function miniForm(placeholder, value, onSubmit, onCancel) {
  const form = el('form', { class: 'reply-form' });
  const area = el('textarea', { maxlength: '500', placeholder, 'aria-label': placeholder });
  area.value = value;
  const actions = el('div', { class: 'actions' });
  const cancel = el('button', { type: 'button' }, '취소');
  const save = el('button', { type: 'submit', class: 'dark' }, '등록');
  const error = el('p', { class: 'error', role: 'alert' });
  actions.append(cancel, save);
  form.append(area, actions, error);
  cancel.addEventListener('click', () => { form.remove(); if (onCancel) onCancel(); });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    save.disabled = true;
    try {
      await onSubmit(area.value);
    } catch (err) {
      error.textContent = err instanceof ApiError ? err.message : '저장하지 못했어요.';
      save.disabled = false;
    }
  });
  return form;
}

if (post) {
  const input = $('comment-input');
  input.addEventListener('input', () => { $('comment-length').textContent = `${[...input.value].length} / 500`; });
  $('comment-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const length = [...input.value.trim()].length;
    if (length < 1 || length > 500) {
      $('comment-error').textContent = '댓글은 1~500자로 써 주세요.';
      return;
    }
    try {
      await api.post(`/api/posts/${post.id}/comments${keyQuery}`, { content: input.value });
      input.value = '';
      $('comment-length').textContent = '0 / 500';
      $('comment-error').textContent = '';
      loadComments();
    } catch (err) {
      $('comment-error').textContent = err.message;
    }
  });
}
