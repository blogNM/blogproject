import { initLayout } from '../layout.js';

const me = await initLayout();
if (me) {
  document.getElementById('welcome').textContent = me.nickname + '님, 환영합니다.';
}
