// 블로그 관리 화면의 멤버 관리·제재·신고·블랙리스트·위임·폐쇄 (002 US4·US5, 6단계).
// 권한이 없는 부분은 서버가 403을 주므로 그 칸은 숨긴다.
import { api } from '../api.js';
import { roleName } from '../blog-card.js';
import { el } from '../layout.js';
import { renderPager } from '../pager.js';
import { SANCTION_REASONS, sanctionReasonSelect } from '../sanction-reasons.js';

const $ = (id) => document.getElementById(id);

const DURATIONS = { D3: '3일', D14: '14일', D30: '30일', FOREVER: '영구' };
const SANCTION_LABEL = { WARNING: '경고', SUSPEND: '활동 정지', LIFT: '정지 풀기', KICK: '강제 퇴장' };
const TARGET_LABEL = { MEMBER: '회원', POST: '글', COMMENT: '댓글', BLOG: '블로그', BLOG_OWNER: '블로그장' };
const CLOSE_BY = { OWNER: '블로그장', ADMIN: '메인 관리자', OWNER_KICKED: '블로그장 권한 박탈' };

function durationSelect() {
  const select = el('select', { 'aria-label': '정지 기간' });
  select.append(...Object.entries(DURATIONS).map(([v, label]) => el('option', { value: v }, label)));
  return select;
}

function button(label, cls, onClick) {
  const b = el('button', { type: 'button', class: cls || '' }, label);
  b.addEventListener('click', async () => {
    b.disabled = true;
    try {
      await onClick();
    } finally {
      b.disabled = false;
    }
  });
  return b;
}

/** 처리한 신고를 제재 사유 항목별로 나눠 보는 단추 (D-95). counts가 없으면 숨긴다. */
function renderReasonChips(box, counts, current, onPick) {
  box.replaceChildren();
  box.hidden = !counts;
  if (!counts) return;
  const all = Object.values(counts).reduce((a, b) => a + b, 0);
  const options = [['', '전체', all], ...SANCTION_REASONS.filter(([v]) => counts[v]).map(([v, text]) => [v, text, counts[v]])];
  // 문제 없음과 사유 항목이 생기기 전 처리는 건수만 보여 준다
  if (counts.NONE) options.push(['NONE', '문제 없음', counts.NONE]);
  if (counts.UNSET) options.push(['UNSET', '사유 없음(예전 처리)', counts.UNSET]);
  options.forEach(([v, text, n]) => {
    const b = el('button', { type: 'button', class: 'tab' + (v === current ? ' active' : '') }, text);
    b.append(el('span', { class: 'count' }, String(n)));
    const countOnly = v === 'NONE' || v === 'UNSET';
    b.disabled = countOnly;
    if (!countOnly) b.addEventListener('click', () => onPick(v));
    box.append(b);
  });
}

export function setupModeration({ blog, base, me, reloadBlog }) {
  const isOwner = blog.myRole === 'OWNER';
  let members = [];

  async function refreshAll() {
    await Promise.all([loadMembers(), loadSanctions(), loadBlacklist()]);
  }

  // ----- 멤버 -----

  function permissionBox(m) {
    const box = el('div', { class: 'perm-box' });
    const roleSel = el('select', { 'aria-label': m.nickname + ' 역할' });
    roleSel.append(el('option', { value: 'MEMBER' }, '멤버'), el('option', { value: 'SUB_OWNER' }, '부블로그장'));
    roleSel.value = m.role;
    const checks = [
      ['editInfo', '블로그 정보·테마 수정'],
      ['manageMembers', '멤버 관리·제재·신고 처리'],
      ['managePosts', '글·카테고리 관리'],
    ].map(([key, label]) => {
      const input = el('input', { type: 'checkbox' });
      input.checked = Boolean(m.permissions && m.permissions[key]);
      input.dataset.key = key;
      const l = el('label', { class: 'checkbox' });
      l.append(input, document.createTextNode(' ' + label));
      return l;
    });
    const permWrap = el('div', { class: 'perm-checks' });
    permWrap.append(...checks);
    permWrap.hidden = roleSel.value !== 'SUB_OWNER';
    roleSel.addEventListener('change', () => { permWrap.hidden = roleSel.value !== 'SUB_OWNER'; });
    const save = button('역할 저장', 'primary inline', async () => {
      const permissions = {};
      permWrap.querySelectorAll('input').forEach((i) => { permissions[i.dataset.key] = i.checked; });
      try {
        await api.put(`${base}/members/${m.id}/role`, { role: roleSel.value, permissions });
        $('members-message').className = 'info';
        $('members-message').textContent = `${m.nickname}님의 역할을 저장했어요.`;
        await loadMembers();
      } catch (err) {
        $('members-message').className = 'error';
        $('members-message').textContent = err.message;
      }
    });
    box.append(roleSel, permWrap, save);
    return box;
  }

  async function sanction(m, action, duration, code) {
    const names = { WARNING: '경고를', SUSPEND: '활동 정지를', LIFT: '정지 풀기를', KICK: '강제 퇴장을' };
    let reason = '';
    if (action !== 'LIFT' && !code) {
      $('members-message').className = 'error';
      $('members-message').textContent = `${m.nickname}님의 제재 사유를 먼저 골라 주세요.`;
      return;
    }
    if (action !== 'LIFT') {
      reason = prompt(`${m.nickname}님에게 ${duration ? DURATIONS[duration] + ' ' : ''}${names[action]} 할까요?\n사유를 적어 주세요 (200자까지, 본인에게 알림으로 가요).`, '');
      if (reason === null) return;
    }
    if (action === 'KICK' && !confirm(`${m.nickname}님을 강제 퇴장할까요? 블랙리스트에 올라 다시 들어올 수 없어요.`)) return;
    const msg = $('members-message');
    try {
      const res = await api.post(`${base}/members/${m.id}/sanctions`, { action, duration, reasonCode: code || null, reason });
      msg.className = 'info';
      msg.textContent = res.kickSuggested
        ? `${m.nickname}님은 최근 1년에 ${res.suspensions}번 정지됐어요. 강제 퇴장을 생각해 보세요.`
        : `${m.nickname}님에게 ${names[action]} 했어요.`;
      await refreshAll();
    } catch (err) {
      msg.className = 'error';
      msg.textContent = err.message;
    }
  }

  function memberItem(m) {
    const li = el('li', { class: 'member-card' });
    const head = el('div', { class: 'member-head' });
    head.append(el('span', { class: 'strong' }, m.nickname), el('span', { class: 'badge' + (m.role === 'MEMBER' ? ' muted-badge' : '') }, roleName(m.role)),
      el('span', { class: 'muted' }, m.joinedAt + ' 참여'));
    if (m.suspendedUntil) head.append(el('span', { class: 'badge warn' }, `정지 중 · ${m.suspendedUntil}까지`));
    if (m.kickSuggested) head.append(el('span', { class: 'badge danger-badge' }, '정지 3번 · 강퇴 고려'));
    li.append(head);
    li.append(el('p', { class: 'muted member-info' }, [m.name, m.email, m.phone].filter(Boolean).join(' · ')));
    if (m.warnings || m.suspensions) {
      li.append(el('p', { class: 'muted member-info' }, `최근 1년 경고 ${m.warnings}번 · 정지 ${m.suspensions}번`));
    }
    const self = m.id === me.id;
    if (self || m.role === 'OWNER') return li;
    // 부블로그장은 블로그장만 제재할 수 있다
    const canSanction = isOwner || m.role !== 'SUB_OWNER';
    const actions = el('div', { class: 'member-actions' });
    if (canSanction) {
      const why = sanctionReasonSelect();
      const dur = durationSelect();
      actions.append(
        why,
        button('경고', '', () => sanction(m, 'WARNING', null, why.value)),
        dur,
        button('정지', '', () => sanction(m, 'SUSPEND', dur.value, why.value)),
      );
      if (m.suspendedUntil) actions.append(button('정지 풀기', '', () => sanction(m, 'LIFT')));
      actions.append(button('강제 퇴장', 'danger-outline', () => sanction(m, 'KICK', null, why.value)));
    }
    li.append(actions);
    if (isOwner) li.append(permissionBox(m));
    return li;
  }

  async function loadMembers() {
    try {
      members = await api.get(base + '/members');
    } catch {
      $('members-section').hidden = true;
      return false;
    }
    $('members').replaceChildren(...members.map(memberItem));
    $('members-section').hidden = false;
    fillTransferOptions();
    return true;
  }

  // ----- 신고 -----

  let reportStatus = 'PENDING';
  let reportPage = 1;
  let reportReason = '';

  function reportItem(r) {
    const li = el('li', { class: 'report-item' });
    const head = el('div', { class: 'member-head' });
    head.append(el('span', { class: 'badge' }, TARGET_LABEL[r.targetType] || r.targetType),
      el('span', { class: 'strong' }, r.reasonLabel || r.reason), el('span', { class: 'muted' }, r.createdAt));
    if (r.sameTargetPending > 1) head.append(el('span', { class: 'badge warn' }, `같은 대상 신고 ${r.sameTargetPending}건`));
    li.append(head);
    const target = el('p', { class: 'report-target' });
    if (r.link && !r.targetGone) {
      const a = el('a', { href: r.link, target: '_blank', rel: 'noopener' }, r.summary || '대상 보기');
      target.append(a);
    } else {
      target.append(el('span', { class: 'muted' }, r.targetGone ? '이미 지워졌거나 숨겨진 대상이에요.' : (r.summary || '')));
    }
    if (r.authorNickname) target.append(el('span', { class: 'muted' }, ' · 작성자 ' + r.authorNickname));
    li.append(target);
    if (r.detail) li.append(el('p', { class: 'report-detail' }, r.detail));
    if (r.status === 'DONE') {
      li.append(el('p', { class: 'muted' }, `처리 결과: ${r.resultLabel || r.result}`
        + (r.sanctionReasonLabel ? ` · 제재 사유: ${r.sanctionReasonLabel}` : '') + ` · ${r.processedAt}`));
      return li;
    }
    const form = el('div', { class: 'member-actions' });
    const result = el('select', { 'aria-label': '처리 결과' });
    result.append(el('option', { value: 'NONE' }, '문제 없음'), el('option', { value: 'WARNING' }, '경고'),
      el('option', { value: 'SUSPEND' }, '활동 정지'), el('option', { value: 'KICK' }, '강제 퇴장'));
    const dur = durationSelect();
    dur.hidden = true;
    // 조치를 고르면 제재 사유 항목도 고른다 (D-95). 신고 사유와 같은 항목을 먼저 골라 둔다.
    const why = sanctionReasonSelect(r.reason);
    why.hidden = true;
    result.addEventListener('change', () => {
      dur.hidden = result.value !== 'SUSPEND';
      why.hidden = result.value === 'NONE';
    });
    const reason = el('input', { maxlength: '200', placeholder: '사유 (선택)', 'aria-label': '사유' });
    const msg = el('p', { class: 'error', role: 'alert' });
    form.append(result, why, dur, reason, button('처리', 'primary inline', async () => {
      if (result.value !== 'NONE' && !why.value) {
        msg.textContent = '제재 사유를 골라 주세요.';
        return;
      }
      if (result.value === 'KICK' && !confirm('신고된 사람을 강제 퇴장할까요? 블랙리스트에 올라 다시 들어올 수 없어요.')) return;
      try {
        await api.post(`${base}/reports/${r.id}`, {
          result: result.value,
          duration: result.value === 'SUSPEND' ? dur.value : null,
          sanctionReason: result.value === 'NONE' ? null : why.value,
          reason: reason.value,
        });
        await Promise.all([loadReports(), refreshAll()]);
      } catch (err) {
        msg.textContent = err.message;
      }
    }));
    li.append(form, msg);
    return li;
  }

  async function loadReports() {
    let res;
    try {
      const reasonQuery = reportStatus === 'DONE' && reportReason ? `&reason=${reportReason}` : '';
      res = await api.get(`${base}/reports?status=${reportStatus}&page=${reportPage}${reasonQuery}`);
    } catch {
      $('reports-section').hidden = true;
      return;
    }
    if (reportStatus === 'PENDING') $('reports-pending-count').textContent = String(res.total);
    $('reports').replaceChildren(...(res.items.length === 0
      ? [el('li', { class: 'muted' }, reportStatus === 'PENDING' ? '처리할 신고가 없어요.' : '처리한 신고가 없어요.')]
      : res.items.map(reportItem)));
    renderPager($('reports-pager'), res.page, res.totalPages, (p) => { reportPage = p; loadReports(); });
    renderReasonChips($('report-reasons'), reportStatus === 'DONE' ? res.reasonCounts : null, reportReason, (v) => {
      reportReason = v;
      reportPage = 1;
      loadReports();
    });
    $('reports-section').hidden = false;
  }

  document.querySelectorAll('#reports-section .tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('#reports-section .tab').forEach((t) => t.classList.toggle('active', t === tab));
      reportStatus = tab.dataset.status;
      reportPage = 1;
      reportReason = '';
      loadReports();
    });
  });

  // ----- 제재 기록 -----

  let sanctionReason = '';

  async function loadSanctions() {
    let rows;
    try {
      rows = await api.get(base + '/sanctions' + (sanctionReason ? `?reason=${sanctionReason}` : ''));
    } catch {
      $('sanctions-section').hidden = true;
      return;
    }
    $('sanctions').replaceChildren(...(rows.length === 0 ? [el('li', { class: 'muted' }, '아직 제재 기록이 없어요.')]
      : rows.map((s) => {
        const li = el('li', { class: 'list-row' });
        li.append(el('span', { class: 'badge' + (s.type === 'LIFT' ? ' muted-badge' : ' warn') },
          SANCTION_LABEL[s.type] + (s.duration ? ' ' + s.duration : '')),
        el('span', { class: 'strong' }, s.target), el('span', { class: 'muted' }, `${s.actor} · ${s.createdAt}${s.fromReport ? ' · 신고 처리' : ''}`));
        if (s.reasonLabel) li.append(el('span', { class: 'badge' }, s.reasonLabel));
        if (s.reason) li.append(el('span', { class: 'sanction-reason' }, s.reason));
        return li;
      })));
    $('sanctions-section').hidden = false;
  }

  const sanctionFilter = $('sanction-reason-filter');
  sanctionFilter.replaceChildren(el('option', { value: '' }, '모든 사유'),
    ...SANCTION_REASONS.map(([v, text]) => el('option', { value: v }, text)));
  sanctionFilter.addEventListener('change', () => {
    sanctionReason = sanctionFilter.value;
    loadSanctions();
  });

  // ----- 블랙리스트 -----

  async function loadBlacklist() {
    let list;
    let appeals;
    try {
      [list, appeals] = await Promise.all([api.get(base + '/blacklist'), api.get(base + '/blacklist-appeals')]);
    } catch {
      $('blacklist').hidden = true;
      return;
    }
    $('appeals').replaceChildren(...(appeals.length === 0 ? [el('li', { class: 'muted' }, '들어온 이의 신청이 없어요.')]
      : appeals.map((a) => {
        const li = el('li', { class: 'report-item' });
        const head = el('div', { class: 'member-head' });
        head.append(el('span', { class: 'strong' }, a.nickname), el('span', { class: 'muted' }, a.createdAt));
        const match = [a.nameMatched ? '이름' : null, a.phoneMatched ? '전화번호' : null].filter(Boolean);
        if (match.length) head.append(el('span', { class: 'badge warn' }, `강퇴된 사람과 ${match.join('·')} 같음`));
        li.append(head, el('p', { class: 'report-detail' }, a.message));
        if (a.status === 'PENDING') {
          const actions = el('div', { class: 'member-actions' });
          const decide = (d) => async () => {
            try {
              await api.post(`${base}/blacklist-appeals/${a.id}/${d}`);
              await loadBlacklist();
            } catch (err) {
              alert(err.message);
            }
          };
          actions.append(button('풀어 주기', 'primary inline', decide('lift')), button('거절', '', decide('reject')));
          li.append(actions);
        } else {
          li.append(el('p', { class: 'muted' }, a.status === 'LIFTED' ? '풀어 줬어요' : '거절했어요'));
        }
        return li;
      })));
    $('blacklist-list').replaceChildren(...(list.length === 0 ? [el('li', { class: 'muted' }, '블랙리스트가 비어 있어요.')]
      : list.map((b) => {
        const li = el('li', { class: 'list-row' });
        li.append(el('span', { class: 'strong' }, b.nickname), el('span', { class: 'muted' }, b.createdAt + ' 강퇴'));
        if (b.liftedAt) li.append(el('span', { class: 'badge muted-badge' }, b.liftedAt + ' 풀림'));
        return li;
      })));
    $('blacklist').hidden = false;
    if (location.hash === '#blacklist') $('blacklist').scrollIntoView();
  }

  // ----- 위임·폐쇄 (블로그장만) -----

  function fillTransferOptions() {
    if (!isOwner) return;
    const candidates = members.filter((m) => m.role !== 'OWNER' && !m.suspendedUntil);
    $('transfer-to').replaceChildren(el('option', { value: '' }, '넘겨받을 멤버 고르기'),
      ...candidates.map((m) => el('option', { value: String(m.id) }, `${m.nickname} (${roleName(m.role)})`)));
  }

  function renderOwnerBox() {
    const pending = blog.transfer;
    $('transfer-pending').hidden = !pending;
    if (pending) $('transfer-pending').textContent = `${pending.toNickname}님에게 블로그장 위임을 요청했어요. 수락을 기다리는 중이에요.`;
    $('transfer-form').hidden = Boolean(pending) || blog.status === 'CLOSING';
    $('transfer-cancel').hidden = !pending;
    const closing = blog.status === 'CLOSING';
    $('close-blog').hidden = closing;
    $('close-cancel').hidden = !closing;
    $('close-state').textContent = closing
      ? `${CLOSE_BY[blog.closeRequestedBy] || ''} 요청으로 ${String(blog.closeScheduledAt).replace('T', ' ').substring(0, 16)}에 닫혀요. 그 전에는 철회할 수 있어요.`
      : '폐쇄를 누르면 7일 뒤 새벽 4시에 닫히고, 그 전에는 철회할 수 있어요. 닫힌 블로그는 30일 뒤 글과 사진이 모두 지워져요.';
    $('owner-section').hidden = false;
  }

  async function ownerAction(fn) {
    const msg = $('owner-message');
    try {
      await fn();
      msg.textContent = '';
      blog = await reloadBlog();
      renderOwnerBox();
    } catch (err) {
      msg.textContent = err.message;
    }
  }

  if (isOwner) {
    $('transfer-request').addEventListener('click', () => ownerAction(async () => {
      const id = $('transfer-to').value;
      if (!id) throw new Error('넘겨받을 멤버를 골라 주세요.');
      const name = $('transfer-to').selectedOptions[0].textContent;
      if (!confirm(`${name}님에게 블로그장을 넘길까요? 수락하면 나는 일반 멤버가 돼요.`)) return;
      await api.post(base + '/transfer', { memberId: Number(id) });
    }));
    $('transfer-cancel').addEventListener('click', () => ownerAction(() => api.del(base + '/transfer')));
    $('close-blog').addEventListener('click', () => ownerAction(async () => {
      if (!confirm('블로그를 폐쇄할까요? 7일 뒤 새벽 4시에 닫히고, 멤버와 구독자에게 알림이 가요.')) return;
      await api.post(base + '/close');
    }));
    $('close-cancel').addEventListener('click', () => ownerAction(async () => {
      if (!confirm('폐쇄를 철회할까요?')) return;
      await api.del(base + '/close');
    }));
  }

  const ready = (async () => {
    await refreshAll();
    await loadReports();
    if (isOwner) renderOwnerBox();
  })();
  return { ready, refresh: refreshAll };
}
