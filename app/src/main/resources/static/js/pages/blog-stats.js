// 블로그 관리 화면의 "블로그 통계" (D-112). 블로그장·부블로그장만 서버가 값을 준다.
// 시간별(하루 24칸)·일별(최근 7~90일)·월별(최근 12달) 방문자와 조회수, 들어온 경로, 검색어, 다른 사이트, 많이 본 글.
// 그래프는 외부 라이브러리 없이 SVG로 그린다. 검색어·글 제목·사이트 이름은 모두 textContent (SEC-06).
import { api } from '../api.js';
import { el } from '../layout.js';

const SVG = 'http://www.w3.org/2000/svg';
const $ = (id) => document.getElementById(id);
const fmt = new Intl.NumberFormat('ko-KR');

function svg(tag, attrs = {}) {
  const node = document.createElementNS(SVG, tag);
  Object.entries(attrs).forEach(([k, v]) => node.setAttribute(k, v));
  return node;
}

/** 한국 시간 오늘 (YYYY-MM-DD). */
function seoulToday() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date());
}

function addDays(iso, days) {
  const d = new Date(iso + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().substring(0, 10);
}

function korDate(iso, withYear) {
  const [y, m, d] = iso.split('-').map(Number);
  return (withYear ? `${y}년 ` : '') + `${m}월 ${d}일`;
}

/** 4칸 눈금에 맞는 깔끔한 최댓값 (한 칸이 1·2·2.5·3·4·5·6·8 × 10ⁿ인 정수). */
function niceMax(max) {
  if (max <= 4) return 4;
  const step = max / 4;
  const pow = 10 ** Math.floor(Math.log10(step));
  // 눈금 값이 늘 정수가 되도록 (방문자·조회수는 정수)
  const nice = [1, 2, 2.5, 3, 4, 5, 6, 8, 10].map((m) => m * pow).filter(Number.isInteger).find((s) => s >= step);
  return nice * 4;
}

export function setupStats(base) {
  const state = { unit: 'day', days: 30, date: seoulToday(), data: null };
  const section = $('stats');
  const chartBox = $('stats-chart');

  function tile(label, count, sub) {
    const box = el('div', { class: 'stat-tile' });
    const value = el('span', { class: 'st-value' }, fmt.format(count.visitors));
    value.append(el('small', {}, '명'));
    box.append(el('span', { class: 'st-label' }, label), value,
      el('span', { class: 'st-sub' }, `조회 ${fmt.format(count.views)}회` + (sub ? ` · ${sub}` : '')));
    return box;
  }

  function renderTiles(s) {
    $('stat-tiles').replaceChildren(tile('오늘 방문자', s.today), tile('어제 방문자', s.yesterday),
      tile('최근 30일 방문자', s.last30Days), tile('전체 방문자', s.total, `최근 ${s.keptDays}일`));
  }

  function caption(d) {
    const sum = ` · 방문자 ${fmt.format(d.period.visitors)}명 · 조회 ${fmt.format(d.period.views)}회`;
    if (d.unit === 'hour') return `${korDate(d.from, true)} 시간별${sum}`;
    if (d.unit === 'month') {
      const [fy, fm] = d.from.split('-').map(Number);
      const [ty, tm] = d.to.split('-').map(Number);
      return `${fy}년 ${fm}월 ~ ${ty}년 ${tm}월 월별${sum}`;
    }
    return `${korDate(d.from, false)} ~ ${korDate(d.to, false)} 일별${sum}`;
  }

  // ----- 그래프: 방문자는 막대, 조회수는 선. 눈금은 한 축(둘 다 횟수) -----
  function drawChart() {
    const d = state.data;
    if (!d) return;
    const points = d.series;
    const width = Math.max(280, chartBox.clientWidth);
    const height = 240;
    const pad = { left: 40, right: 8, top: 14, bottom: 26 };
    const plotW = width - pad.left - pad.right;
    const plotH = height - pad.top - pad.bottom;
    const max = niceMax(Math.max(0, ...points.map((p) => Math.max(p.views, p.visitors))));
    const y = (v) => pad.top + plotH - (v / max) * plotH;
    const band = plotW / points.length;
    const cx = (i) => pad.left + band * (i + 0.5);
    const barW = Math.max(2, Math.min(28, band * 0.62));

    const root = svg('svg', { viewBox: `0 0 ${width} ${height}`, role: 'img',
      'aria-label': '방문자(막대)와 조회수(선) 그래프. 아래 [표로 보기]에 같은 값이 있어요.' });
    for (let t = 0; t <= 4; t += 1) {
      const v = (max / 4) * t;
      const gy = y(v);
      root.append(svg('line', { class: 'grid', x1: pad.left, x2: width - pad.right, y1: gy, y2: gy }));
      const label = svg('text', { class: 'axis-text', x: pad.left - 8, y: gy + 4, 'text-anchor': 'end' });
      label.textContent = fmt.format(Math.round(v));
      root.append(label);
    }
    const every = Math.max(1, Math.ceil(points.length / Math.max(1, Math.floor(plotW / 46))));
    const bars = [];
    points.forEach((p, i) => {
      if (p.visitors > 0) {
        // 막대 위쪽만 둥글게(4px), 바닥은 눈금선에 붙인다
        const x0 = cx(i) - barW / 2;
        const top = y(p.visitors);
        const h = pad.top + plotH - top;
        const r = Math.min(4, barW / 2, h);
        const path = svg('path', { class: 'bar', d: `M${x0},${pad.top + plotH} V${top + r} Q${x0},${top} ${x0 + r},${top} `
          + `H${x0 + barW - r} Q${x0 + barW},${top} ${x0 + barW},${top + r} V${pad.top + plotH} Z` });
        bars[i] = path;
        root.append(path);
      }
      if (i % every === 0) {
        const label = svg('text', { class: 'axis-text', x: cx(i), y: height - 8, 'text-anchor': 'middle' });
        label.textContent = p.label;
        root.append(label);
      }
    });
    const line = points.map((p, i) => `${i ? 'L' : 'M'}${cx(i).toFixed(1)},${y(p.views).toFixed(1)}`).join(' ');
    root.append(svg('path', { class: 'line', d: line }));
    if (points.length <= 31) {
      points.forEach((p, i) => root.append(svg('circle', { class: 'dot', cx: cx(i), cy: y(p.views), r: 3.5 })));
    }
    if (points.every((p) => p.views === 0)) {
      const note = svg('text', { class: 'empty-note', x: pad.left + plotW / 2, y: pad.top + plotH / 2, 'text-anchor': 'middle' });
      note.textContent = '이 기간에는 아직 방문 기록이 없어요.';
      root.append(note);
    }

    // 마우스를 올리거나 누르면 그 칸의 값을 보여 준다. 칸 전체가 누르는 자리다
    const hover = svg('line', { class: 'hover-line', y1: pad.top, y2: pad.top + plotH, visibility: 'hidden' });
    root.append(hover);
    const tip = el('div', { class: 'chart-tip', hidden: '' });
    const show = (i) => {
      const p = points[i];
      hover.setAttribute('x1', cx(i));
      hover.setAttribute('x2', cx(i));
      hover.setAttribute('visibility', 'visible');
      bars.forEach((b, j) => b && b.classList.toggle('dim', j !== i));
      const title = d.unit === 'hour' ? `${korDate(d.from, false)} ${p.label}` : d.unit === 'day' ? korDate(p.key, true)
        : `${p.key.substring(0, 4)}년 ${Number(p.key.substring(5))}월`;
      const visitors = el('span');
      const vKey = el('i');
      vKey.style.setProperty('background', 'var(--c-visitors)');
      visitors.append(vKey, `방문자 ${fmt.format(p.visitors)}명`);
      const views = el('span');
      const wKey = el('i');
      wKey.style.setProperty('background', 'var(--c-views)');
      views.append(wKey, `조회 ${fmt.format(p.views)}회`);
      tip.replaceChildren(el('strong', {}, title), visitors, el('br'), views);
      tip.hidden = false;
      const scale = chartBox.clientWidth / width;
      const left = Math.min(Math.max(cx(i) * scale, 70), chartBox.clientWidth - 70);
      tip.style.setProperty('left', left + 'px');
      tip.style.setProperty('top', Math.max(0, y(Math.max(p.views, p.visitors)) * scale - 10) + 'px');
    };
    const hide = () => {
      hover.setAttribute('visibility', 'hidden');
      bars.forEach((b) => b && b.classList.remove('dim'));
      tip.hidden = true;
    };
    points.forEach((p, i) => {
      const hit = svg('rect', { class: 'hit', x: pad.left + band * i, y: pad.top, width: band, height: plotH });
      hit.addEventListener('pointerenter', () => show(i));
      hit.addEventListener('pointerdown', () => show(i));
      root.append(hit);
    });
    root.addEventListener('pointerleave', hide);
    chartBox.replaceChildren(root, tip);
  }

  function renderTable(d) {
    const head = el('tr');
    head.append(el('th', {}, d.unit === 'hour' ? '시간' : d.unit === 'day' ? '날짜' : '달'), el('th', {}, '방문자'), el('th', {}, '조회수'));
    const rows = d.series.map((p) => {
      const tr = el('tr');
      tr.append(el('td', {}, d.unit === 'day' ? p.key : p.label), el('td', {}, fmt.format(p.visitors)), el('td', {}, fmt.format(p.views)));
      return tr;
    });
    $('stats-table').replaceChildren(el('thead'), el('tbody'));
    $('stats-table').tHead.append(head);
    $('stats-table').tBodies[0].append(...rows);
  }

  function emptyItem(text) {
    return el('li', { class: 'muted' }, text);
  }

  function renderSources(d) {
    const list = $('stats-sources');
    if (d.sources.items.length === 0) {
      list.replaceChildren(emptyItem('이 기간에 들어온 기록이 없어요.'));
    } else {
      list.replaceChildren(...d.sources.items.map((s) => {
        const li = el('li');
        const track = el('span', { class: 'bl-track' });
        const fill = el('span', { class: 'bl-fill' });
        fill.style.setProperty('width', Math.max(0, Math.min(100, s.percent)) + '%');
        track.append(fill);
        li.append(el('span', { class: 'bl-name' }, s.label), el('span', { class: 'bl-num' }, `${fmt.format(s.count)}회 · ${s.percent}%`), track);
        return li;
      }));
    }
    $('stats-inside').textContent = d.sources.inside > 0
      ? `블로그 홈에서 글로 옮겨 가는 것처럼 블로그 안에서 연 화면 ${fmt.format(d.sources.inside)}회는 빼고 셌어요.` : '';
  }

  function rankList(id, items, empty, render) {
    $(id).replaceChildren(...(items.length ? items.map(render) : [emptyItem(empty)]));
  }

  function renderLists(d) {
    rankList('stats-keywords', d.keywords, '이 기간에 검색해서 들어온 기록이 없어요.', (k) => {
      const li = el('li');
      li.append(el('span', { class: 'rl-name' }, k.name), el('span', { class: 'rl-num' }, `${fmt.format(k.count)}회`));
      return li;
    });
    rankList('stats-hosts', d.hosts, '다른 사이트에서 들어온 기록이 없어요.', (h) => {
      const li = el('li');
      li.append(el('span', { class: 'rl-name' }, h.name), el('span', { class: 'rl-num' }, `${fmt.format(h.count)}회`));
      return li;
    });
    const slug = decodeURIComponent(location.pathname.split('/')[2] || '');
    rankList('stats-posts', d.posts, '이 기간에 열어 본 글이 없어요.', (p) => {
      const li = el('li');
      li.append(el('a', { class: 'rl-name', href: `/blog/${encodeURIComponent(slug)}/posts/${p.id}` }, p.title),
        el('span', { class: 'rl-num' }, `조회 ${fmt.format(p.views)} · 방문자 ${fmt.format(p.visitors)}`));
      return li;
    });
  }

  function syncControls() {
    document.querySelectorAll('#stats-units .tab').forEach((t) => {
      const on = t.dataset.unit === state.unit;
      t.classList.toggle('active', on);
      t.setAttribute('aria-selected', String(on));
    });
    $('stats-days').hidden = state.unit !== 'day';
    $('stats-date').hidden = state.unit === 'month';
    $('stats-date').value = state.date;
  }

  async function load() {
    syncControls();
    const q = new URLSearchParams({ unit: state.unit });
    if (state.unit === 'hour') q.set('date', state.date);
    if (state.unit === 'day') {
      q.set('to', state.date);
      q.set('from', addDays(state.date, -(state.days - 1)));
    }
    try {
      state.data = await api.get(`${base}/stats?${q}`);
    } catch (err) {
      $('stats-caption').textContent = err.message;
      return false;
    }
    const d = state.data;
    renderTiles(d.summary);
    $('stats-caption').textContent = caption(d);
    drawChart();
    renderTable(d);
    renderSources(d);
    renderLists(d);
    return true;
  }

  document.querySelectorAll('#stats-units .tab').forEach((t) => t.addEventListener('click', () => {
    state.unit = t.dataset.unit;
    load();
  }));
  $('stats-days').addEventListener('change', () => {
    state.days = Number($('stats-days').value);
    load();
  });
  $('stats-date').max = seoulToday();
  $('stats-date').addEventListener('change', () => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test($('stats-date').value)) return;
    state.date = $('stats-date').value;
    load();
  });
  let lastWidth = 0;
  new ResizeObserver(() => {
    if (chartBox.clientWidth !== lastWidth) {
      lastWidth = chartBox.clientWidth;
      drawChart();
    }
  }).observe(chartBox);

  return load().then((ok) => {
    section.hidden = !ok;
    if (ok && location.hash === '#stats') section.scrollIntoView();
  });
}
