// 블로그 테마 틀 7가지와 꾸미기 값 (D-89). 색은 #RRGGBB만, 글꼴·틀은 정해진 값만 쓴다.
export const THEMES = {
  ODYSSEY: { name: 'Odyssey', accent: '#7C0CB4', summary: '큰 머리 사진, 인기 글 하나를 크게, 최근 글 목록과 오른쪽 사이드바', tags: ['블로그형', '사이드바'] },
  POSTER: { name: 'Poster', accent: '#1E1D33', summary: '최근 글 4칸 띠, 어두운 머리 사진, 사진 위주의 엇갈린 격자', tags: ['사진 위주', '단정함'] },
  WHATEVER: { name: 'Whatever', accent: '#9C7A54', summary: '넘겨 보는 머리 사진, 사진과 글이 번갈아 나오는 매거진 칸', tags: ['매거진', '따뜻함'] },
  LETTER: { name: 'Letter', accent: '#2FA58C', summary: '가운데 한 줄, 맨 위 글을 크게, 그 아래 두 칸 카드', tags: ['미니멀', '읽기 좋음'] },
  BOOK_CLUB: { name: 'Book Club', accent: '#39385B', summary: '위쪽 소개 영역, 표지형 카드 3개, 썸네일 목록과 사이드바', tags: ['소개형', '사이드바'] },
  MAGAZINE: { name: 'Magazine', accent: '#D93A49', summary: '사진 위에 제목, Hot Issue 띠, Trend 두 칸, News 목록, 사진 모음', tags: ['매거진', '전문가'] },
  PORTFOLIO: { name: 'Portfolio', accent: '#FF4F7B', summary: '꽉 찬 머리 사진, 사진 격자, 인기 글 목록', tags: ['사진', '포트폴리오'] },
};

export const FONTS = { SANS: '고딕 (Pretendard)', SERIF: '명조', MONO: '고정폭' };

/** 강조 색으로 고를 수 있는 견본. 보내 주신 Ready 색감 6가지와 테마 기본색. */
export const SWATCHES = ['#7C0CB4', '#5720C3', '#DFB79F', '#925B8F', '#39385B', '#C39496',
  '#1E1D33', '#9C7A54', '#2FA58C', '#D93A49', '#FF4F7B'];

export function isAccent(value) {
  return /^#[0-9A-Fa-f]{6}$/.test(value || '');
}

/** 블로그 화면·글 화면에 강조 색과 글꼴을 입힌다. 값은 CSS 변수와 정해진 클래스로만 넣는다. */
export function applyBlogLook(theme, target = document.body) {
  if (!theme) return;
  if (isAccent(theme.accent)) document.documentElement.style.setProperty('--accent', theme.accent);
  Object.keys(FONTS).forEach((f) => target.classList.remove('font-' + f.toLowerCase()));
  if (FONTS[theme.font]) target.classList.add('font-' + theme.font.toLowerCase());
}
