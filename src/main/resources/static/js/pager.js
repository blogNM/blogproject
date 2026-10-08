// 쪽 번호. 다섯 쪽씩 묶어 보이고, 양옆 둥근 화살표로 앞뒤 쪽으로 간다 (사용자 요청 2026-10-08).
import { el } from './layout.js';

const GROUP = 5;

function arrow(direction, target, go) {
  const disabled = target === null;
  const b = el('button', {
    type: 'button',
    class: 'pager-arrow',
    'aria-label': direction === 'prev' ? '이전 쪽' : '다음 쪽',
  }, direction === 'prev' ? '‹' : '›');
  if (disabled) b.disabled = true;
  else b.addEventListener('click', () => go(target));
  return b;
}

/** pager 요소 안을 다시 그린다. current·total은 1부터 센다. */
export function renderPager(pager, current, total, go) {
  pager.replaceChildren();
  if (total <= 1) return;
  pager.setAttribute('aria-label', '쪽 넘기기');
  const start = Math.floor((current - 1) / GROUP) * GROUP + 1;
  const end = Math.min(total, start + GROUP - 1);
  pager.append(arrow('prev', current > 1 ? current - 1 : null, go));
  const pages = el('div', { class: 'pager-pages' });
  for (let p = start; p <= end; p += 1) {
    const b = el('button', { type: 'button', class: p === current ? 'pager-page current' : 'pager-page' }, String(p));
    if (p === current) b.setAttribute('aria-current', 'page');
    else b.addEventListener('click', () => go(p));
    pages.append(b);
  }
  pager.append(pages, arrow('next', current < total ? current + 1 : null, go));
}
