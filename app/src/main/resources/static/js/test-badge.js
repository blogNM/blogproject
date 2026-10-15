// 테스트 회원 표시 (D-113). 테스트 회원의 닉네임 옆, 테스트 회원이 만든 블로그 이름 옆에 작은 "테스트" 표시를 붙인다.
// 글자는 textContent로만 넣는다 (SEC-06).
import { el } from './layout.js';

export function testBadge() {
  return el('span', { class: 'test-badge', title: '기능을 확인하려고 만든 테스트 계정이에요' }, '테스트');
}

/** test가 참이면 node 뒤에 표시를 붙여 함께 돌려준다. 아니면 node만. */
export function withTestBadge(node, test) {
  if (!test) return node;
  const box = el('span', { class: 'with-test-badge' });
  box.append(node, testBadge());
  return box;
}
