# Prism 1.29.0 (MIT)와 Toast UI 코드 문법 강조 플러그인 3.1.0 (MIT)

코드 블럭에 언어별 색을 입힙니다 (D-117). CSP `script-src 'self'`를 지키려고 바깥 CDN 대신 서버에 둡니다.

- `prism-creat.min.js`: npm `prismjs@1.29.0`과 고른 언어(java, javascript, typescript, python, c, cpp, csharp, kotlin, go, rust, swift, php, ruby, dart, r, sql, html, css, json, yaml, bash, markdown, diff)를 한 파일로 묶은 것. `window.Prism`으로 쓰고, `Prism.manual = true`라 페이지가 열릴 때 저절로 칠하지 않습니다. 칠하는 곳은 `js/code-highlight.js`(글 화면)와 에디터 플러그인이 정합니다.
- `../toastui/toastui-code-syntax-highlight.min.js`: npm `@toast-ui/editor-plugin-code-syntax-highlight@3.1.0`의 Prism을 뺀 판(`dist/toastui-editor-plugin-code-syntax-highlight.js`)을 묶은 것. `window.toastui.codeSyntaxHighlight`로 씁니다.
- `../toastui/toastui-code-syntax-highlight.css`: 같은 패키지의 `dist/toastui-editor-plugin-code-syntax-highlight.css` 그대로.
- 라이선스 문구는 각 파일 끝에 있습니다.

다시 만들 때:

```bash
npm i prismjs@1.29.0 @toast-ui/editor-plugin-code-syntax-highlight@3.1.0 esbuild
echo "globalThis.Prism = globalThis.Prism || {}; globalThis.Prism.manual = true;" > prism-manual.js
# prism-entry.js: import './prism-manual.js'; import Prism from 'prismjs';
#   import 'prismjs/components/prism-markup-templating.js'; 이어서 위 언어마다 import 'prismjs/components/prism-<언어>.js';
#   window.Prism = Prism;
npx esbuild prism-entry.js --bundle --minify --format=iife --legal-comments=eof --outfile=prism-creat.min.js
echo "import p from '@toast-ui/editor-plugin-code-syntax-highlight'; window.toastui = window.toastui || {}; window.toastui.codeSyntaxHighlight = p;" > plugin-entry.js
npx esbuild plugin-entry.js --bundle --minify --format=iife --legal-comments=eof --outfile=toastui-code-syntax-highlight.min.js
```

html·css·javascript는 Prism 기본 파일에 들어 있어 따로 import하지 않습니다. 언어를 더하면 `js/rich-editor.js`의 `CODE_LANGUAGES`도 함께 고칩니다.
