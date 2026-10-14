// 블로그 통계용 방문 기록 (D-112).
// - 블로그·글로 가는 우리 사이트 링크에는 어디서 눌렀는지 ?from= 표시를 붙인다 (검색이면 &q=검색어도).
// - 블로그 홈·글 화면은 열릴 때 표시와 이전 화면 주소(document.referrer)를 서버에 한 번 보낸다. 서버가 경로를 가르고,
//   블로그 멤버·로봇·짧은 시간 안에 다시 연 화면은 세지 않는다.
// - 보낸 뒤에는 주소창에서 from·q를 지워 주소를 복사해 나눠도 표시가 따라가지 않게 한다.
import { api } from './api.js';

/** 링크 주소에 들어온 경로 표시를 붙인다. #댓글 자리 같은 해시는 그대로 둔다. */
export function withFrom(href, from, q) {
  if (!from) return href;
  const url = new URL(href, location.origin);
  url.searchParams.set('from', from);
  if (q) url.searchParams.set('q', q);
  return url.pathname + url.search + url.hash;
}

/** 지금 주소의 from 표시를 읽고 주소창에서 지운다. 읽은 값을 돌려준다. */
export function takeFrom() {
  const url = new URL(location.href);
  const from = url.searchParams.get('from');
  const q = url.searchParams.get('q');
  if (from !== null || q !== null) {
    url.searchParams.delete('from');
    url.searchParams.delete('q');
    history.replaceState(history.state, '', url.pathname + url.search + url.hash);
  }
  return { from, q };
}

/** 블로그 홈(postId 없음)이나 글 화면을 연 것을 남긴다. 실패해도 화면에는 아무 영향이 없다. */
export function recordVisit(slug, { postId = null, key = null } = {}) {
  const { from, q } = takeFrom();
  api.post(`/api/blogs/${encodeURIComponent(slug)}/visits`, {
    postId, from, q, key, referrer: document.referrer || null,
  }).catch(() => {});
}
