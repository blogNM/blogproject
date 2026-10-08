// 화면에서 먼저 확인하는 입력 규칙. 서버도 같은 규칙으로 다시 확인한다.

// 비밀번호 8~15자, 영문·숫자·특수문자 모두 (SEC-02)
export const PASSWORD_RULE = /^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[\x21-\x7E]{8,15}$/;
export const PASSWORD_MESSAGE = '비밀번호는 8~15자로 영문·숫자·특수문자를 모두 넣어 주세요.';

// 블로그 주소: 영문 소문자·숫자·- 3~30자 (002 입력 규칙)
export const SLUG_RULE = /^[a-z0-9-]{3,30}$/;

/** "여행, #사진 ,java" → ["여행", "사진", "java"] */
export function parseTags(text) {
  return text.split(',').map((t) => t.trim().replace(/^#/, '')).filter((t) => t.length > 0);
}
