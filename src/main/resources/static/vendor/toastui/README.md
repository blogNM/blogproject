# Toast UI Editor 3.2.2 (MIT)

글쓰기 화면의 에디터(D-74). CSP `script-src 'self'`를 지키려고 바깥 CDN 대신 이 폴더에 둡니다.

- `toastui-editor-all.min.js`: npm `@toast-ui/editor@3.2.2`와 한국어 파일(`i18n/ko-kr`)을 esbuild로 한 파일로 묶은 것. `window.toastui.Editor`로 씁니다. 라이선스 문구는 파일 끝에 있습니다.
- `toastui-editor.css`: npm 패키지의 `dist/toastui-editor.css` 그대로.

다시 만들 때:

```bash
npm i @toast-ui/editor@3.2.2 esbuild
echo "import Editor from '@toast-ui/editor'; import '@toast-ui/editor/dist/i18n/ko-kr'; window.toastui = { Editor };" > entry.js
npx esbuild entry.js --bundle --minify --format=iife --legal-comments=eof --outfile=toastui-editor-all.min.js
```
