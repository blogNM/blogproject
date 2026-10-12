// 공지 편집 도구 (D-102). 마크다운 칸 위에 서식 버튼을 두어 관리자가 공지를 쉽게 꾸미게 한다.
// 글자 크기·색·형광펜은 정해 둔 class의 <span>으로 넣고, 서버가 그 class만 남긴다 (MarkdownRenderer).
// 미리보기도 서버가 실제 공지와 똑같이 변환·거른 HTML이라, 본문에만 innerHTML을 쓴다는 규칙 안에 있다.
import { api } from '../api.js';
import { IMAGE_EXTENSIONS, IMAGE_MAX_BYTES } from '../rules.js';
import { el } from './common.js';

const COLORS = [['red', '빨강'], ['orange', '주황'], ['green', '초록'], ['blue', '파랑'], ['purple', '보라'],
  ['mauve', '자주'], ['gray', '회색']];
const HIGHLIGHTS = [['yellow', '노랑'], ['green', '연두'], ['pink', '분홍'], ['blue', '하늘']];
const SIZES = [['small', '작게'], ['large', '크게'], ['xlarge', '아주 크게']];

/** textarea를 받아 위에 도구 막대, 아래에 미리보기를 붙인 묶음을 돌려준다. */
export function noticeEditor(textarea, msg) {
  const box = el('div', { class: 'adm-editor' });
  const bar = el('div', { class: 'adm-editor-bar', role: 'toolbar', 'aria-label': '공지 서식' });
  const preview = el('div', { class: 'adm-editor-preview post-content', hidden: '' });

  function focusRange(start, end) {
    textarea.focus();
    textarea.setSelectionRange(start, end);
    textarea.dispatchEvent(new Event('input'));
  }

  /** 고른 글자를 앞뒤로 감싼다. 고른 글자가 없으면 placeholder를 넣고 그 부분을 골라 둔다. */
  function wrap(before, after, placeholder) {
    const { selectionStart: s, selectionEnd: e, value } = textarea;
    const text = value.slice(s, e) || placeholder;
    textarea.setRangeText(before + text + after, s, e, 'end');
    focusRange(s + before.length, s + before.length + text.length);
  }

  /** 고른 줄마다 앞에 붙인다 (제목, 목록, 인용). */
  function prefixLines(prefix) {
    const { selectionStart: s, selectionEnd: e, value } = textarea;
    const start = value.lastIndexOf('\n', s - 1) + 1;
    const lines = value.slice(start, e).split('\n').map((line) => prefix + line.replace(/^(#{1,6} |- |> )/, ''));
    const text = lines.join('\n');
    textarea.setRangeText(text, start, e, 'end');
    focusRange(start, start + text.length);
  }

  function insert(text) {
    const { selectionStart: s, selectionEnd: e } = textarea;
    textarea.setRangeText(text, s, e, 'end');
    focusRange(s + text.length, s + text.length);
  }

  function span(cls) {
    wrap(`<span class="${cls}">`, '</span>', '글자');
  }

  function tool(text, title, onClick, cls = '') {
    const b = el('button', { type: 'button', class: 'adm-tool ' + cls, title, 'aria-label': title }, text);
    b.addEventListener('click', onClick);
    return b;
  }

  function group(name, ...items) {
    const g = el('div', { class: 'adm-tool-group', role: 'group', 'aria-label': name });
    g.append(...items);
    return g;
  }

  // 글자 크기: 고른 크기로 감싼다
  const size = el('select', { class: 'adm-tool-select', 'aria-label': '글자 크기' });
  size.append(el('option', { value: '' }, '글자 크기'), ...SIZES.map(([v, n]) => el('option', { value: v }, n)));
  size.addEventListener('change', () => {
    if (size.value) span('ts-' + size.value);
    size.value = '';
  });

  // 사진 넣기: 공지 사진으로 올리고 본문에 마크다운 그림을 넣는다
  const file = el('input', { type: 'file', accept: '.jpg,.jpeg,.png,.gif,.webp,image/jpeg,image/png,image/gif,image/webp', hidden: '' });
  file.addEventListener('change', async () => {
    const picked = file.files[0];
    file.value = '';
    if (!picked) return;
    msg.textContent = '';
    if (!IMAGE_EXTENSIONS.includes(picked.name.split('.').pop().toLowerCase())) {
      msg.textContent = 'jpg·jpeg·png·gif·webp 사진만 올릴 수 있습니다.';
      return;
    }
    if (picked.size > IMAGE_MAX_BYTES) {
      msg.textContent = '사진은 한 장에 3MB까지 올릴 수 있습니다.';
      return;
    }
    const form = new FormData();
    form.append('file', picked);
    try {
      const res = await api.upload('/api/admin/notices/images', form);
      const alt = picked.name.replace(/\.[^.]+$/, '').replace(/[[\]]/g, '');
      insert(`\n![${alt}](${res.url})\n`);
    } catch (err) {
      msg.textContent = err.message;
    }
  });

  const previewButton = tool('미리보기', '미리보기', async () => {
    if (!preview.hidden) {
      preview.hidden = true;
      textarea.hidden = false;
      previewButton.textContent = '미리보기';
      return;
    }
    try {
      const res = await api.post('/api/admin/notices/preview', { content: textarea.value });
      preview.innerHTML = res.html;
      preview.hidden = false;
      textarea.hidden = true;
      previewButton.textContent = '다시 쓰기';
    } catch (err) {
      msg.textContent = err.message;
    }
  }, 'adm-tool-wide');

  bar.append(
    group('글자 모양',
      tool('B', '굵게', () => wrap('**', '**', '굵은 글자'), 'adm-tool-bold'),
      tool('I', '기울임', () => wrap('*', '*', '기울인 글자'), 'adm-tool-italic'),
      tool('S', '취소선', () => wrap('~~', '~~', '지운 글자'), 'adm-tool-strike'),
      size),
    group('글자 색', ...COLORS.map(([c, n]) => tool('', '글자 색 ' + n, () => span('tc-' + c), 'adm-swatch tc-bg-' + c))),
    group('형광펜', ...HIGHLIGHTS.map(([c, n]) => tool('가', '형광펜 ' + n, () => span('hl-' + c), 'adm-swatch hl-' + c))),
    group('문단',
      tool('제목', '제목', () => prefixLines('## '), 'adm-tool-wide'),
      tool('목록', '목록', () => prefixLines('- '), 'adm-tool-wide'),
      tool('인용', '인용', () => prefixLines('> '), 'adm-tool-wide'),
      tool('―', '구분선', () => insert('\n\n---\n\n'))),
    group('넣기',
      tool('링크', '링크', () => wrap('[', '](https://)', '링크 글자'), 'adm-tool-wide'),
      tool('사진', '사진 넣기', () => file.click(), 'adm-tool-wide'),
      file),
    group('보기', previewButton));

  box.append(bar, textarea, preview);
  return {
    element: box,
    /** 다른 공지를 고치기 시작하거나 다 쓰면 쓰기 화면으로 돌린다. */
    reset() {
      preview.hidden = true;
      preview.replaceChildren();
      textarea.hidden = false;
      previewButton.textContent = '미리보기';
    },
  };
}
