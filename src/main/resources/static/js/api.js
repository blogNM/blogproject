// 모든 서버 요청은 이 파일을 거친다.
// - 상태를 바꾸는 요청에 X-XSRF-TOKEN 헤더를 붙인다 (SEC-10, 헌법 III)
// - Access Token이 만료되면(401) Refresh Token으로 한 번 갱신하고 다시 보낸다 (SEC-04)
// - 30분 무활동은 클릭·키 입력만 활동으로 센다. 스크롤·마우스 움직임·자동 요청은 세지 않는다 (D-62)

const XSRF_COOKIE = 'XSRF-TOKEN';
const HEARTBEAT_MS = 4 * 60 * 1000;

// 페이지를 연 것 자체가 사용자의 직접 행동이다.
let userActed = true;
['click', 'keydown'].forEach((type) =>
  document.addEventListener(type, () => { userActed = true; }, { capture: true, passive: true }));

export class ApiError extends Error {
  constructor(status, body) {
    super(body.message || '요청을 처리하지 못했습니다.');
    this.status = status;
    this.code = body.code;
    this.details = body.details || {};
  }
}

function readCookie(name) {
  const prefix = name + '=';
  const found = document.cookie.split('; ').find((c) => c.startsWith(prefix));
  return found ? decodeURIComponent(found.substring(prefix.length)) : null;
}

async function csrfToken() {
  let token = readCookie(XSRF_COOKIE);
  if (!token) {
    await fetch('/api/csrf', { credentials: 'same-origin' });
    token = readCookie(XSRF_COOKIE);
  }
  return token;
}

async function send(method, url, body) {
  const headers = { Accept: 'application/json' };
  if (method !== 'GET') {
    headers['X-XSRF-TOKEN'] = await csrfToken();
  }
  // 파일(FormData)은 브라우저가 Content-Type(경계값 포함)을 정한다.
  const isForm = body instanceof FormData;
  if (body !== undefined && !isForm) {
    headers['Content-Type'] = 'application/json';
  }
  return fetch(url, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
  });
}

let refreshing = null;

/** Refresh Token으로 로그인을 이어간다. 성공하면 true. 여러 요청이 동시에 불러도 한 번만 보낸다. */
export function refreshLogin() {
  if (!refreshing) {
    const active = userActed;
    userActed = false;
    refreshing = send('POST', '/api/auth/refresh', { active })
      .then((res) => res.ok)
      .catch(() => false)
      .finally(() => { refreshing = null; });
  }
  return refreshing;
}

async function request(method, url, body) {
  let res = await send(method, url, body);
  if (res.status === 401 && !url.startsWith('/api/auth/') && !url.startsWith('/api/admin/')) {
    if (await refreshLogin()) {
      res = await send(method, url, body);
    }
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new ApiError(res.status, data);
  }
  return data;
}

export const api = {
  get: (url) => request('GET', url),
  post: (url, body = {}) => request('POST', url, body),
  put: (url, body = {}) => request('PUT', url, body),
  del: (url) => request('DELETE', url),
  /** 사진 올리기. FormData에 file을 담아 보낸다. */
  upload: (url, formData) => request('POST', url, formData),
};

let heartbeatStarted = false;

/** 로그인한 화면에서만 켠다. 사용자가 행동했을 때만 서버의 무활동 시간을 다시 시작한다. */
export function startActivityHeartbeat() {
  if (heartbeatStarted) return;
  heartbeatStarted = true;
  setInterval(() => {
    if (userActed) refreshLogin();
  }, HEARTBEAT_MS);
}
