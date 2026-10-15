// Google reCAPTCHA v2 "로봇이 아닙니다" 사람 확인 상자 (SEC-13, D-79, D-118). 회원 로그인과 관리자 로그인이 함께 쓴다.
// 서버가 captchaRequired와 사이트 키를 보내면 상자를 띄우고, 받은 토큰을 다음 로그인 요청에 실어 보낸다.

const SCRIPT_URL = 'https://www.google.com/recaptcha/api.js?render=explicit&hl=ko&onload=';
const ONLOAD = '__creatRecaptchaReady';
const LOAD_FAILED = '로봇 확인 상자를 불러오지 못했어요. 새로 고침하거나 광고 차단을 끄고 다시 해 주세요.';

let loading = null;

function loadScript() {
  if (window.grecaptcha && window.grecaptcha.render) return Promise.resolve(window.grecaptcha);
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      // api.js는 준비되면 onload로 적은 이름의 함수를 부른다 (script의 load보다 늦게 준비된다)
      window[ONLOAD] = () => resolve(window.grecaptcha);
      const s = document.createElement('script');
      s.src = SCRIPT_URL + ONLOAD;
      s.async = true;
      s.onerror = () => reject(new Error('recaptcha script'));
      document.head.append(s);
    }).catch((err) => {
      loading = null; // 다음 시도에서 다시 받아 본다
      throw err;
    });
  }
  return loading;
}

/** box 안에 사람 확인 상자를 띄운다. token()은 확인을 마쳤을 때만 값이 있다. */
export function createCaptcha(box) {
  let token = null;
  let widgetId = null;
  const holder = document.createElement('div');
  holder.className = 'captcha-widget';
  const message = document.createElement('p');
  message.className = 'captcha-message';
  box.append(holder, message);

  return {
    token: () => token,
    async show(siteKey) {
      box.hidden = false;
      token = null;
      message.textContent = '로봇이 아닌지 확인해 주세요.';
      try {
        const g = await loadScript();
        if (widgetId === null) {
          widgetId = g.render(holder, {
            sitekey: siteKey,
            // 다크모드면 확인 상자도 어둡게 (D-116). 관리자 화면은 다크모드를 읽지 않아 늘 밝다
            theme: document.documentElement.dataset.mode === 'dark' ? 'dark' : 'light',
            callback: (t) => { token = t; message.textContent = ''; },
            'expired-callback': () => { token = null; message.textContent = '확인 시간이 지났어요. 다시 확인해 주세요.'; },
            'error-callback': () => { token = null; message.textContent = LOAD_FAILED; },
          });
        } else {
          g.reset(widgetId);
        }
      } catch {
        message.textContent = LOAD_FAILED;
      }
      box.scrollIntoView({ block: 'nearest' });
    },
  };
}
