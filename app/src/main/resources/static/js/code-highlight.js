// 글 화면 코드 블록 색칠 (D-117). 서버가 남긴 <pre><code class="language-java">만 Prism으로 칠한다.
// Prism 파일은 코드 블록이 있는 글에서만 처음 한 번 불러온다. Prism은 글자를 span(class="token …")으로 감쌀 뿐 스크립트를 넣지 않는다.

const PRISM_URL = '/vendor/prism/prism-creat.min.js';
/** 화면 오른쪽 위에 보여 줄 언어 이름. 없으면 class의 이름 그대로. */
const NAMES = {
  js: 'JavaScript', javascript: 'JavaScript', ts: 'TypeScript', typescript: 'TypeScript', jsx: 'JSX', java: 'Java',
  kotlin: 'Kotlin', kt: 'Kotlin', py: 'Python', python: 'Python', c: 'C', cpp: 'C++', csharp: 'C#', cs: 'C#',
  go: 'Go', rust: 'Rust', swift: 'Swift', php: 'PHP', ruby: 'Ruby', rb: 'Ruby', sql: 'SQL', bash: 'Bash', sh: 'Shell',
  shell: 'Shell', json: 'JSON', yaml: 'YAML', yml: 'YAML', html: 'HTML', xml: 'XML', markup: 'HTML', css: 'CSS',
  markdown: 'Markdown', md: 'Markdown', diff: 'Diff', dart: 'Dart', r: 'R', text: 'Text', plain: 'Text',
};

let loading = null;
function loadPrism() {
  if (window.Prism && window.Prism.highlightElement) return Promise.resolve(window.Prism);
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = PRISM_URL;
      s.onload = () => resolve(window.Prism);
      s.onerror = () => { loading = null; reject(new Error('prism')); };
      document.head.append(s);
    });
  }
  return loading;
}

/** root 안의 언어가 있는 코드 블록을 칠하고 언어 이름을 단다. 못 불러오면 색 없이 그대로 둔다. */
export async function highlightCode(root) {
  const blocks = [...root.querySelectorAll('pre > code[class^="language-"]')];
  if (!blocks.length) return;
  blocks.forEach((code) => {
    const lang = code.className.slice('language-'.length);
    code.parentElement.classList.add('code-block');
    code.parentElement.dataset.lang = NAMES[lang] || lang;
  });
  try {
    const Prism = await loadPrism();
    blocks.forEach((code) => {
      const lang = code.className.slice('language-'.length);
      if (Prism.languages[lang]) Prism.highlightElement(code);
    });
  } catch {
    // 색 없이도 코드는 그대로 읽을 수 있다
  }
}
