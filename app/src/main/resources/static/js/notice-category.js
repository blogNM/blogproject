// 메인 공지 카테고리 표시 (D-98). 서버 값 NOTICE·UPDATE를 "공지"·"업데이트" 배지로 보여 준다.
import { el } from './layout.js';

export const NOTICE_CATEGORIES = [['NOTICE', '공지'], ['UPDATE', '업데이트']];

export function categoryBadge(category) {
  const update = category === 'UPDATE';
  return el('span', { class: update ? 'badge notice-cat' : 'badge muted-badge notice-cat' }, update ? '업데이트' : '공지');
}
