import { api } from '../api.js';
import { el, initLayout } from '../layout.js';

await initLayout();

const $ = (id) => document.getElementById(id);
const [, , rawSlug, , rawId] = location.pathname.split('/');
const slug = decodeURIComponent(rawSlug || '');
const postId = Number(rawId);
// 일부 공개 블로그의 공유 링크 값 (D-49)
const key = new URLSearchParams(location.search).get('key');
const blogHref = `/blog/${encodeURIComponent(slug)}` + (key ? '?key=' + encodeURIComponent(key) : '');

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
    showError(`이 블로그에서 활동이 정지되어 ${err.details.suspendedUntil.replace('T', ' ')}까지 들어갈 수 없습니다.`);
  } else {
    showError(err.status === 404 ? '글을 찾을 수 없습니다. 지워졌거나 볼 수 없는 글입니다.' : err.message);
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
  $('meta').textContent = `${post.authorName} · ${post.createdAt}` + (post.updatedAt ? ` (수정 ${post.updatedAt})` : '');
  $('content').innerHTML = post.contentHtml;
  $('tags').replaceChildren(...post.tags.map((t) => {
    const li = el('li');
    li.append(el('a', { href: `${blogHref}${key ? '&' : '?'}tag=${encodeURIComponent(t)}` }, '#' + t));
    return li;
  }));
  $('counts').textContent = `조회 ${post.counts.views} · 공감 ${post.counts.likes} · 댓글 ${post.counts.comments}`;

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
  $('post').hidden = false;
}
