// 모든 서버 요청은 이 파일을 거친다.
// - 상태를 바꾸는 요청에 X-XSRF-TOKEN 헤더를 붙인다 (SEC-10, 헌법 III)
// - Access Token이 만료되면(401) Refresh Token으로 한 번 갱신하고 다시 보낸다 (SEC-04)
// - 30분 무활동은 클릭·키 입력만 활동으로 센다. 스크롤·마우스 움직임·자동 요청은 세지 않는다 (D-62)
// - 만료 5분 전에 "로그인을 연장할까요?" 창을 띄운다 (USR-03)
// - 5초 안에 응답이 없으면 요청을 멈추고 다시 시도하라고 알린다 (D-48). 사진 올리기는 30초까지 기다린다.

const XSRF_COOKIE = 'XSRF-TOKEN';
const HEARTBEAT_MS = 4 * 60 * 1000;
const IDLE_MS = 30 * 60 * 1000;
const WARN_BEFORE_MS = 5 * 60 * 1000;
const TIMEOUT_MS = 5000;
const UPLOAD_TIMEOUT_MS = 30000;
const TIMEOUT_MESSAGE = '응답이 늦어 요청을 멈췄어요. 다시 시도해 주세요.';

// 페이지를 연 것 자체가 사용자의 직접 행동이다.
let userActed = true;
let lastActedAt = Date.now();
['click', 'keydown'].forEach((type) =>
  document.addEventListener(type, () => { userActed = true; lastActedAt = Date.now(); }, { capture: true, passive: true }));

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

async function send(method, url, body, timeoutMs = TIMEOUT_MS) {
  const headers = { Accept: 'application/json' };
  if (method !== 'GET') {
    headers['X-XSRF-TOKEN'] = await csrfToken();
  }
  // 파일(FormData)은 브라우저가 Content-Type(경계값 포함)을 정한다.
  const isForm = body instanceof FormData;
  if (body !== undefined && !isForm) {
    headers['Content-Type'] = 'application/json';
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, {
      method,
      headers,
      credentials: 'same-origin',
      signal: controller.signal,
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
    });
  } catch (err) {
    if (err.name === 'AbortError') throw new ApiError(0, { code: 'TIMEOUT', message: TIMEOUT_MESSAGE });
    throw new ApiError(0, { code: 'NETWORK', message: '서버에 연결하지 못했어요. 다시 시도해 주세요.' });
  } finally {
    clearTimeout(timer);
  }
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

async function request(method, url, body, timeoutMs) {
  let res = await send(method, url, body, timeoutMs);
  if (res.status === 401 && !url.startsWith('/api/auth/') && !url.startsWith('/api/admin/')) {
    if (await refreshLogin()) {
      res = await send(method, url, body, timeoutMs);
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
  upload: (url, formData) => request('POST', url, formData, UPLOAD_TIMEOUT_MS),
};

let heartbeatStarted = false;

/** 로그인한 화면에서만 켠다. 사용자가 행동했을 때만 서버의 무활동 시간을 다시 시작한다. */
export function startActivityHeartbeat() {
  if (heartbeatStarted) return;
  heartbeatStarted = true;
  setInterval(() => {
    if (userActed) refreshLogin();
  }, HEARTBEAT_MS);
  setInterval(checkExpiry, 30 * 1000);
}

// ----- 로그인 연장 창 (USR-03) -----
let extendDialog = null;

/** 마지막 행동에서 25분이 지나면 연장 창을 띄운다. 서버는 그보다 늦게 끝나므로 일찍 묻는 쪽이다. */
function checkExpiry() {
  const idle = Date.now() - lastActedAt;
  if (idle < IDLE_MS - WARN_BEFORE_MS || (extendDialog && extendDialog.open)) return;
  if (idle >= IDLE_MS) {
    if (extendDialog && extendDialog.open) extendDialog.close();
    return;
  }
  showExtendDialog();
}

function showExtendDialog() {
  if (!extendDialog) {
    extendDialog = document.createElement('dialog');
    extendDialog.className = 'report-dialog';
    extendDialog.setAttribute('aria-labelledby', 'extend-title');
    const title = document.createElement('h2');
    title.id = 'extend-title';
    title.textContent = '로그인을 연장할까요?';
    const text = document.createElement('p');
    text.className = 'muted';
    text.textContent = '5분 뒤 로그인이 끝나요. 쓰던 글은 연장한 뒤 저장해 주세요.';
    const actions = document.createElement('div');
    actions.className = 'actions';
    const later = document.createElement('button');
    later.type = 'button';
    later.textContent = '괜찮아요';
    const extend = document.createElement('button');
    extend.type = 'button';
    extend.className = 'dark';
    extend.textContent = '연장하기';
    actions.append(later, extend);
    extendDialog.append(title, text, actions);
    document.body.append(extendDialog);
    later.addEventListener('click', () => extendDialog.close());
    // 단추를 누른 것 자체가 행동이라 userActed가 켜진 채로 갱신된다.
    extend.addEventListener('click', async () => {
      extendDialog.close();
      if (!(await refreshLogin())) location.href = '/login.html?next=' + encodeURIComponent(location.pathname + location.search);
    });
  }
  extendDialog.showModal();
}
