// 공지 편집 도구 (D-102, D-106). 쓰는 칸에 보이는 모양 그대로 공지를 쓴다.
// 굵게·기울임·취소선, 글자 크기·색·형광펜, 제목·목록·인용·구분선, 링크, 사진을 도구 막대에서 고른다.
// 본문은 그대로 마크다운이고, 크기·색·형광펜은 정해 둔 class의 <span>이다. 서버가 그 class만 남긴다 (MarkdownRenderer).
// HTML 구조는 [HTML 보기]로 옆 칸에 읽기 전용 글자로 보인다.
import { api } from '../api.js';
import { IMAGE_EXTENSIONS, IMAGE_MAX_BYTES } from '../rules.js';
import { createRichEditor, loadToastUi } from '../rich-editor.js';
import { el } from './common.js';

/** 편집기 묶음을 만든다. Toast UI 파일을 처음 한 번 불러오므로 기다려야 한다. */
export async function noticeEditor(msg) {
  const box = el('div', { class: 'adm-editor', id: 'notice-content' });
  await loadToastUi();

  async function uploadImage(blob, callback) {
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
      const res = await api.upload('/api/admin/notices/images', form);
      callback(res.url, name.replace(/\.[^.]+$/, '').replace(/[[\]]/g, ''));
    } catch (err) {
      msg.textContent = err.message;
    }
  }

  const rich = createRichEditor({
    host: box,
    height: '420px',
    placeholder: '공지 내용을 쓰세요. 위 버튼으로 글자 크기·색·형광펜·사진을 넣을 수 있어요. 5000자까지.',
    uploadImage,
  });

  return {
    element: box,
    getValue: () => rich.getMarkdown(),
    setValue(markdown) {
      rich.setMarkdown(markdown);
    },
    /** 다른 공지를 고치기 시작하거나 다 쓰면 빈 칸으로 돌린다. */
    reset() {
      rich.setMarkdown('');
      rich.closeHtml();
    },
  };
}
