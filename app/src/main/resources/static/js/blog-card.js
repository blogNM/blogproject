// 블로그 목록 카드. 사용자 입력(이름·소개·태그·닉네임)은 모두 textContent로 넣는다 (SEC-06).
import { el } from './layout.js';
import { withFrom } from './visit.js';
import { testBadge } from './test-badge.js';

const ROLE = { OWNER: '블로그장', SUB_OWNER: '부블로그장', MEMBER: '멤버' };
export const VISIBILITY = { PUBLIC: '공개', LINK_ONLY: '일부 공개', PRIVATE: '비공개' };
export const JOIN_POLICY = { FREE: '자유 참여', APPROVAL: '승인제' };

export function roleName(role) {
  return ROLE[role] || '';
}

export function blogUrl(slug) {
  return '/blog/' + encodeURIComponent(slug);
}

/** from: 어디서 눌렀는지 블로그 통계에 남길 표시, q: 검색어 (D-112). */
export function blogCard(b, { from, q } = {}) {
  const li = el('li', { class: 'blog-card' });
  // 머리 사진이 없으면 블로그 강조 색으로 칠한다. 색은 서버가 #RRGGBB만 받는다 (D-89).
  const cover = el('div', { class: 'blog-card-cover' });
  if (b.coverUrl) {
    cover.append(el('img', { src: b.coverUrl, alt: '', loading: 'lazy' }));
  } else if (/^#[0-9A-Fa-f]{6}$/.test(b.accent || '')) {
    cover.style.setProperty('background', `linear-gradient(135deg, ${b.accent}, #DFB79F)`);
  }
  const body = el('div', { class: 'blog-card-body' });
  li.append(cover, body);
  const title = el('a', { href: withFrom(blogUrl(b.slug), from, q), class: 'blog-card-title' }, b.name);
  body.append(title);
  const badges = el('div', { class: 'badges' });
  if (b.myRole) badges.append(el('span', { class: 'badge' }, roleName(b.myRole)));
  badges.append(el('span', { class: 'badge muted-badge' }, JOIN_POLICY[b.joinPolicy] || ''));
  if (b.status === 'CLOSING') badges.append(el('span', { class: 'badge warn' }, '폐쇄 예정'));
  body.append(badges);
  if (b.description) body.append(el('p', { class: 'blog-card-desc' }, b.description));
  if (b.tags && b.tags.length) {
    const tags = el('ul', { class: 'tags' });
    b.tags.forEach((t) => tags.append(el('li', {}, '#' + t)));
    body.append(tags);
  }
  // 블로그장이 테스트 회원이면 블로그장 이름 옆에 "테스트" 표시 (D-113)
  const owner = el('p', { class: 'muted' }, `블로그장 ${b.ownerNickname}`);
  if (b.ownerTest) owner.append(testBadge());
  owner.append(document.createTextNode(` · 멤버 ${b.memberCount}명 · 구독 ${b.subscriberCount}명`
    + (b.createdAt ? ` · ${b.createdAt} 개설` : '')));
  body.append(owner);
  return li;
}

/** 그 블로그에서 정지된 멤버에게 보이는 안내. 기간과 사유를 함께 보인다 (BLG-13). */
export function suspendedText(details = {}) {
  const until = details.suspendedUntil === '영구' ? '영구 정지되어' : `활동이 정지되어 ${details.suspendedUntil}까지`;
  return `이 블로그에서 ${until} 들어갈 수 없습니다.` + (details.reason ? ` 사유: ${details.reason}` : '');
}
