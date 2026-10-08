import { api } from '../api.js';
import { blogUrl } from '../blog-card.js';
import { requireLogin } from '../layout.js';
import { SLUG_RULE, parseTags } from '../rules.js';

await requireLogin();

const $ = (id) => document.getElementById(id);
const checked = (name) => document.querySelector(`input[name=${name}]:checked`).value;

$('slug').addEventListener('input', () => {
  $('slug').value = $('slug').value.toLowerCase();
  $('slug-message').className = 'hint';
  $('slug-message').textContent = '영문 소문자·숫자·-로 3~30자. 만든 뒤에는 바꿀 수 없습니다.';
});

$('check-slug').addEventListener('click', async () => {
  const slug = $('slug').value.trim();
  const msg = $('slug-message');
  if (!SLUG_RULE.test(slug)) {
    msg.className = 'error';
    msg.textContent = '블로그 주소는 영문 소문자·숫자·-로 3~30자입니다.';
    return;
  }
  try {
    const res = await api.get('/api/blog-slug-available?slug=' + encodeURIComponent(slug));
    msg.className = res.available ? 'info' : 'error';
    msg.textContent = res.available ? '쓸 수 있는 주소입니다.' : '이미 쓰이는 주소입니다.';
  } catch (err) {
    msg.className = 'error';
    msg.textContent = err.message;
  }
});

$('blog-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('error').textContent = '';
  const button = e.target.querySelector('button[type=submit]');
  button.disabled = true;
  try {
    const res = await api.post('/api/blogs', {
      name: $('name').value,
      slug: $('slug').value,
      description: $('description').value,
      tags: parseTags($('tags').value),
      visibility: checked('visibility'),
      joinPolicy: checked('joinPolicy'),
    });
    location.href = blogUrl(res.slug);
  } catch (err) {
    $('error').textContent = err.message;
    button.disabled = false;
  }
});
