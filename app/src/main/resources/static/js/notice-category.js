// 메인 공지 카테고리 표시 (D-98). 서버 값을 화면 이름과 배지로 바꾼다.
import { el } from './layout.js';

export const NOTICE_CATEGORIES = [['NOTICE', '일반'], ['UPDATE', '업데이트'], ['FIX', '수정'], ['MAINTENANCE', '점검']];

const BADGE_CLASS = { NOTICE: 'muted-badge', UPDATE: '', FIX: 'fix-badge', MAINTENANCE: 'warn' };

export function categoryLabel(category) {
  return (NOTICE_CATEGORIES.find(([value]) => value === category) || NOTICE_CATEGORIES[0])[1];
}

export function categoryBadge(category) {
  const cls = BADGE_CLASS[category] ?? BADGE_CLASS.NOTICE;
  return el('span', { class: `badge ${cls} notice-cat`.replace('  ', ' ') }, categoryLabel(category));
}
