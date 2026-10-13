// 사용 방법 안내를 옆으로 넘겨 본다 (D-104). /guides?n=2 또는 /guides?id=공지번호
// 안내는 공지사항 일반 카테고리에서 제목이 "사용 방법"으로 시작하는 글이다.
// 제목은 textContent, 본문은 서버가 마크다운을 HTML로 바꾸고 jsoup으로 거른 것만 innerHTML로 넣는다 (헌법 III).
import { api } from '../api.js';
import { el, initLayout } from '../layout.js';

await initLayout();
const $ = (id) => document.getElementById(id);
const PREFIX = /^사용 방법\s*·?\s*/;
const shortTitle = (title) => title.replace(PREFIX, '') || title;
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;

let guides = [];
try {
  guides = await api.get('/api/notices/guides');
} catch (err) {
  $('error').textContent = err.message;
  $('error').hidden = false;
}

if (guides.length === 0) {
  if ($('error').hidden) $('empty').hidden = false;
} else {
  setup();
}

function setup() {
  const track = $('guide-track');
  const total = guides.length;

  const slides = guides.map((g, i) => {
    const slide = el('div', { class: 'guide-slide', role: 'group', 'aria-roledescription': 'slide', 'aria-label': `${i + 1} / ${total}` });
    const card = el('article', { class: 'card post guide-card' });
    const content = el('div', { class: 'post-content guide-content' });
    content.innerHTML = g.html;
    const link = el('p', { class: 'guide-link' });
    link.append(el('a', { href: '/notices/' + g.id }, '공지사항에서 이 글 보기'));
    card.append(el('p', { class: 'guide-num' }, `사용 방법 ${i + 1}`), el('h2', { class: 'post-heading guide-title' }, shortTitle(g.title)),
      content, link);
    slide.append(card);
    return slide;
  });
  track.replaceChildren(...slides);

  const tabs = guides.map((g, i) => {
    const tab = el('button', { type: 'button', class: 'tab' }, shortTitle(g.title));
    tab.addEventListener('click', () => go(i));
    return tab;
  });
  $('guide-tabs').replaceChildren(...tabs);

  const dots = guides.map((g, i) => {
    const dot = el('button', { type: 'button', class: 'guide-dot', 'aria-label': `${i + 1}번째: ${shortTitle(g.title)}` });
    dot.addEventListener('click', () => go(i));
    return dot;
  });
  $('guide-dots').replaceChildren(...dots);

  let current = -1;

  function show(i) {
    if (i === current) return;
    current = i;
    tabs.forEach((t, k) => {
      t.classList.toggle('active', k === i);
      if (k === i) t.setAttribute('aria-current', 'true');
      else t.removeAttribute('aria-current');
    });
    dots.forEach((d, k) => {
      d.classList.toggle('active', k === i);
      d.setAttribute('aria-pressed', String(k === i));
    });
    // 보이지 않는 칸으로는 Tab 키가 들어가지 않게 한다
    slides.forEach((s, k) => { s.inert = k !== i; });
    fitHeight();
    $('guide-count').textContent = `${i + 1} / ${total}`;
    $('guide-prev').disabled = i === 0;
    $('guide-next').disabled = i === total - 1;
    footStep($('guide-prev-foot'), i - 1, '‹ 이전');
    footStep($('guide-next-foot'), i + 1, '다음 ›');
    document.title = `${shortTitle(guides[i].title)} - 사용 방법 - Creat_`;
    history.replaceState(null, '', '/guides?n=' + (i + 1));
  }

  // 칸 높이는 지금 보는 안내에 맞춘다. 짧은 안내 아래에 긴 안내만큼 빈칸이 생기지 않게 (CSSOM이라 CSP 그대로)
  function fitHeight() {
    if (current < 0) return;
    const slide = slides[current];
    track.style.setProperty('height', slide.offsetHeight + 20 + 'px');
  }
  if ('ResizeObserver' in window) new ResizeObserver(fitHeight).observe(track);
  slides.forEach((s) => s.querySelectorAll('img').forEach((img) => img.addEventListener('load', fitHeight)));

  function footStep(button, i, label) {
    button.hidden = i < 0 || i >= total;
    if (button.hidden) return;
    button.replaceChildren(el('span', { class: 'guide-step-label' }, label), el('span', { class: 'guide-step-title' }, shortTitle(guides[i].title)));
    button.onclick = () => {
      go(i);
      // 아래 버튼으로 넘겼으면 새 안내의 처음부터 읽게 올려 준다
      $('guide').scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
    };
  }

  // 버튼·키보드로 넘기는 동안에는 지나가는 칸을 지금 칸으로 보지 않는다
  let moving = null;
  let movingTimer = 0;

  function go(i, smooth = !reduceMotion) {
    const target = Math.max(0, Math.min(total - 1, i));
    moving = target;
    clearTimeout(movingTimer);
    movingTimer = setTimeout(() => { moving = null; }, 1000);
    track.scrollTo({ left: target * track.clientWidth, behavior: smooth ? 'smooth' : 'auto' });
    show(target);
  }

  // 손가락·트랙패드로 넘기면 scroll-snap이 멈춘 칸을 지금 칸으로 본다
  let frame = 0;
  track.addEventListener('scroll', () => {
    if (dragging) return;
    if (moving !== null) {
      if (Math.abs(track.scrollLeft - moving * track.clientWidth) < 2) moving = null;
      return;
    }
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(() => show(Math.round(track.scrollLeft / track.clientWidth)));
  }, { passive: true });

  // 창 크기가 바뀌면 칸 폭이 바뀌므로 지금 칸에 다시 맞춘다
  addEventListener('resize', () => go(current, false));

  $('guide-prev').addEventListener('click', () => go(current - 1));
  $('guide-next').addEventListener('click', () => go(current + 1));

  // 키보드 ←/→. 글자를 쓰는 칸에서는 그대로 둔다.
  document.addEventListener('keydown', (e) => {
    if (e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
    if (e.target.closest && e.target.closest('input, textarea, select, [contenteditable], .notice-panel')) return;
    if (e.key === 'ArrowLeft') { e.preventDefault(); go(current - 1); }
    if (e.key === 'ArrowRight') { e.preventDefault(); go(current + 1); }
  });

  // 컴퓨터에서 마우스로 끌어서 넘기기. 6px 넘게 움직였을 때만 끌기로 보고, 그때는 링크 클릭을 막는다.
  let dragging = false;
  let start = null;
  track.addEventListener('pointerdown', (e) => {
    if (e.pointerType !== 'mouse' || e.button !== 0) return;
    start = { x: e.clientX, left: track.scrollLeft, id: e.pointerId, time: performance.now() };
  });
  track.addEventListener('pointermove', (e) => {
    if (!start || e.pointerId !== start.id) return;
    const dx = e.clientX - start.x;
    if (!dragging) {
      if (Math.abs(dx) < 6) return;
      dragging = true;
      track.setPointerCapture(e.pointerId);
      track.classList.add('dragging');
      getSelection()?.removeAllRanges();
    }
    track.scrollLeft = start.left - dx;
  });
  const endDrag = (e) => {
    if (!start || e.pointerId !== start.id) return;
    const wasDragging = dragging;
    const dx = e.clientX - start.x;
    const fast = Math.abs(dx) / Math.max(1, performance.now() - start.time) > 0.4;
    start = null;
    if (!wasDragging) return;
    track.classList.remove('dragging');
    let target = current;
    if (Math.abs(dx) > track.clientWidth * 0.18 || (fast && Math.abs(dx) > 30)) target = current + (dx < 0 ? 1 : -1);
    dragging = false;
    go(target);
    // 끌기를 끝낸 자리에서 바로 이어지는 클릭(링크 이동)은 막는다
    suppressClick = true;
    setTimeout(() => { suppressClick = false; }, 0);
  };
  let suppressClick = false;
  track.addEventListener('click', (e) => {
    if (suppressClick) {
      e.preventDefault();
      e.stopPropagation();
    }
  }, { capture: true });
  track.addEventListener('pointerup', endDrag);
  track.addEventListener('pointercancel', endDrag);
  // 사진·링크를 끌면 브라우저가 파일 끌기를 시작하지 않게
  track.addEventListener('dragstart', (e) => e.preventDefault());

  $('guide').hidden = false;
  const query = new URLSearchParams(location.search);
  const byId = guides.findIndex((g) => String(g.id) === query.get('id'));
  const n = Number(query.get('n'));
  go(byId >= 0 ? byId : (Number.isInteger(n) && n >= 1 ? n - 1 : 0), false);
}
