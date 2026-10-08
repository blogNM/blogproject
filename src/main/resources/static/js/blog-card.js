// 블로그 목록 카드. 사용자 입력(이름·소개·태그·닉네임)은 모두 textContent로 넣는다 (SEC-06).
import { el } from './layout.js';

const ROLE = { OWNER: '블로그장', SUB_OWNER: '부블로그장', MEMBER: '멤버' };
export const VISIBILITY = { PUBLIC: '공개', LINK_ONLY: '일부 공개', PRIVATE: '비공개' };
export const JOIN_POLICY = { FREE: '자유 참여', APPROVAL: '승인제' };

export function roleName(role) {
  return ROLE[role] || '';
}

export function blogUrl(slug) {
  return '/blog/' + encodeURIComponent(slug);
}

export function blogCard(b) {
  const li = el('li', { class: 'blog-card' });
  const title = el('a', { href: blogUrl(b.slug), class: 'blog-card-title' }, b.name);
  li.append(title);
  const badges = el('div', { class: 'badges' });
  if (b.myRole) badges.append(el('span', { class: 'badge' }, roleName(b.myRole)));
  badges.append(el('span', { class: 'badge muted-badge' }, JOIN_POLICY[b.joinPolicy] || ''));
  if (b.status === 'CLOSING') badges.append(el('span', { class: 'badge warn' }, '폐쇄 예정'));
  li.append(badges);
  if (b.description) li.append(el('p', { class: 'blog-card-desc' }, b.description));
  if (b.tags && b.tags.length) {
    const tags = el('ul', { class: 'tags' });
    b.tags.forEach((t) => tags.append(el('li', {}, '#' + t)));
    li.append(tags);
  }
  li.append(el('p', { class: 'muted' },
    `블로그장 ${b.ownerNickname} · 멤버 ${b.memberCount}명 · 구독 ${b.subscriberCount}명 · ${b.createdAt} 개설`));
  return li;
}
