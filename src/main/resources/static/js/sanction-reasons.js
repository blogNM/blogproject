// 제재 사유 항목 (D-95). 서버 SanctionReason과 같은 순서·이름이다.
import { el } from './layout.js';

export const SANCTION_REASONS = [
  ['SPAM', '스팸·광고'],
  ['ABUSE', '욕설·괴롭힘'],
  ['OBSCENE', '음란·불쾌한 내용'],
  ['PRIVACY', '개인정보 노출'],
  ['COPYRIGHT', '저작권 침해'],
  ['FLOOD', '도배'],
  ['IMPERSONATION', '사칭'],
  ['ETC', '기타'],
];

export const SANCTION_REASON_LABEL = Object.fromEntries(SANCTION_REASONS);

/** 제재 사유 고르기. 신고 사유와 같은 항목이 있으면 그것을 먼저 골라 둔다. */
export function sanctionReasonSelect(preset, cls = '') {
  const select = el('select', { class: cls, 'aria-label': '제재 사유' });
  select.append(el('option', { value: '' }, '제재 사유 고르기'),
    ...SANCTION_REASONS.map(([v, label]) => el('option', { value: v }, label)));
  if (preset && SANCTION_REASON_LABEL[preset]) select.value = preset;
  return select;
}
