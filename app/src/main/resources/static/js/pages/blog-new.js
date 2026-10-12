import { api } from '../api.js';
import { blogUrl } from '../blog-card.js';
import { el, requireLogin } from '../layout.js';
import { IMAGE_EXTENSIONS, IMAGE_MAX_BYTES, SLUG_RULE, parseTags } from '../rules.js';
import { FONTS, SWATCHES, THEMES, isAccent } from '../theme.js';

await requireLogin();

const $ = (id) => document.getElementById(id);
const checked = (name) => document.querySelector(`input[name=${name}]:checked`).value;

// ----- 테마: 만들 때부터 틀·색·글꼴·머리 문구를 고른다 (D-101). 고르는 방식은 블로그 관리와 같다 -----

function setAccent(value) {
  $('accent').value = value.toUpperCase();
  $('accent-picker').value = value.toLowerCase();
  document.querySelectorAll('.swatch').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.color === value.toUpperCase())));
}

$('theme-cards').replaceChildren(...Object.entries(THEMES).map(([value, info]) => {
  const label = el('label', { class: 'theme-card' });
  const input = el('input', { type: 'radio', name: 'theme', value });
  input.checked = value === 'ODYSSEY';
  // 틀을 바꾸면 그 틀의 기본 색을 권한다. 만들기 전에는 언제든 다른 색으로 바꿀 수 있다.
  input.addEventListener('change', () => {
    setAccent(info.accent);
    showSummary(info);
  });
  // 한눈에 고르도록 정사각형 칸에는 그림·이름·태그만 두고, 설명은 아래 한 줄에 보여 준다.
  label.title = info.summary;
  label.append(input, el('img', { src: `/img/themes/${value.toLowerCase()}.svg`, alt: '', width: '240', height: '160' }),
    el('strong', {}, info.name), el('span', { class: 'muted' }, info.tags.map((x) => '#' + x).join(' ')));
  return label;
}));
function showSummary(info) {
  $('theme-summary').textContent = `${info.name}: ${info.summary}`;
}
showSummary(THEMES.ODYSSEY);
$('swatches').replaceChildren(...SWATCHES.map((c) => {
  const b = el('button', { type: 'button', class: 'swatch', 'data-color': c, 'aria-label': '색 ' + c, 'aria-pressed': 'false' });
  b.style.setProperty('background', c);
  b.addEventListener('click', () => setAccent(c));
  return b;
}));
$('font').replaceChildren(...Object.entries(FONTS).map(([value, name]) => el('option', { value }, name)));
$('font').value = 'SANS';
setAccent(THEMES.ODYSSEY.accent);
$('accent-picker').addEventListener('input', () => setAccent($('accent-picker').value));
$('accent').addEventListener('change', () => {
  if (isAccent($('accent').value.trim())) setAccent($('accent').value.trim());
});

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
  const cover = $('cover').files[0];
  if (cover && !IMAGE_EXTENSIONS.includes(cover.name.split('.').pop().toLowerCase())) {
    $('error').textContent = '머리 사진은 jpg·jpeg·png·gif·webp 사진만 올릴 수 있습니다.';
    return;
  }
  if (cover && cover.size > IMAGE_MAX_BYTES) {
    $('error').textContent = '머리 사진은 3MB까지 올릴 수 있습니다.';
    return;
  }
  const accent = $('accent').value.trim();
  if (!isAccent(accent)) {
    $('error').textContent = '강조 색은 #7C0CB4처럼 #과 6자리로 적어 주세요.';
    return;
  }
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
      theme: {
        theme: checked('theme'),
        accent,
        font: $('font').value,
        heroTitle: $('hero-title').value,
        heroSubtitle: $('hero-subtitle').value,
      },
    });
    // 블로그는 이미 만들어졌으니, 사진이 실패해도 블로그로 넘어가고 관리에서 다시 올리게 한다 (BLG-01).
    if (cover) {
      const form = new FormData();
      form.append('file', cover);
      try {
        await api.upload(`/api/blogs/${encodeURIComponent(res.slug)}/cover`, form);
      } catch (err) {
        alert(`블로그는 만들었지만 머리 사진을 올리지 못했어요. 블로그 관리에서 다시 올려 주세요. (${err.message})`);
      }
    }
    location.href = blogUrl(res.slug);
  } catch (err) {
    $('error').textContent = err.message;
    button.disabled = false;
  }
});
