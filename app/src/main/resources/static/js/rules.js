// 화면에서 먼저 확인하는 입력 규칙. 서버도 같은 규칙으로 다시 확인한다.

// 비밀번호 8~15자, 영문·숫자·특수문자 모두 (SEC-02)
export const PASSWORD_RULE = /^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[\x21-\x7E]{8,15}$/;
export const PASSWORD_MESSAGE = '비밀번호는 8~15자로 영문·숫자·특수문자를 모두 넣어 주세요.';

// 블로그 주소: 영문 소문자·숫자·- 3~30자 (002 입력 규칙)
export const SLUG_RULE = /^[a-z0-9-]{3,30}$/;

/** "여행, #사진 ,java" → ["여행", "사진", "java"] */
export function parseTags(text) {
  return text.split(',').map((t) => t.trim().replace(/^#/, '')).filter((t) => t.length > 0);
}

// 글 (004 6.6). 제목 1~30자, 본문 1000자(사진 문법 ![](주소)는 빼고 셈, 추천안 #14), 태그 10개
export const POST_TITLE_MAX = 30;
export const POST_CONTENT_MAX = 1000;
export const POST_TAGS_MAX = 10;

export function contentLength(markdown) {
  // 사진 문법과 글자 색·크기·형광펜 표시(<span class="tc-…">, ts-…, hl-…)는 세지 않는다. 서버(PostRules)와 같다.
  // 체크박스 목록의 "[ ] "·"[x] "도 세지 않는다. 글머리 목록처럼 "- "까지만 센다 (D-110).
  return [...markdown.replace(/\r\n/g, '\n').replace(/!\[[^\]\n]*\]\([^)\n]*\)/g, '')
    .replace(/<span class="(?:tc|ts|hl)-[a-z]+">|<\/span>/g, '')
    .replace(/^([ \t]*(?:[-*+]|\d{1,9}[.)])[ \t]+)\[[ xX]\](?:[ \t]|$)/gm, '$1')].length;
}

// 사진: jpg·jpeg·png·gif·webp, 한 장 3MB (SEC-08). 서버가 확장자·MIME·시그니처를 다시 본다.
export const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'webp'];
export const IMAGE_MAX_BYTES = 3 * 1024 * 1024;
