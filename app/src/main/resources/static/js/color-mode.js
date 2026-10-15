// 다크모드 (D-116). 화면을 그리기 전에 <html data-mode="dark">를 붙여 밝은 화면이 잠깐 보이지 않게 한다.
// 모듈이 아닌 보통 스크립트로 <head>에서 읽는다 (인라인 스크립트는 CSP로 막혀 있음).
// 고른 값은 이 브라우저에만 남긴다. 서버에는 보내지 않는다.
(function () {
  var mode = null;
  try { mode = localStorage.getItem('creat-color-mode'); } catch (e) { /* 저장소를 못 쓰면 밝은 화면 */ }
  if (mode === 'dark') document.documentElement.setAttribute('data-mode', 'dark');
  document.documentElement.setAttribute('data-mode-ready', '');
})();
