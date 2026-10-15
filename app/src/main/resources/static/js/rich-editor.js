// 보이는 그대로 쓰는 편집기 (D-106). 회원 글쓰기와 관리자 공지 쓰기가 함께 쓴다.
// Toast UI Editor를 WYSIWYG로 열어, 글자 색·크기·형광펜·굵게 등이 쓰는 칸에 바로 보이게 한다.
// 저장 형식은 그대로 마크다운이고, 색·크기·형광펜은 정해 둔 class 하나씩의 <span>이다 (D-93, D-102).
// HTML 구조는 [HTML 보기]를 누르면 옆 칸에 읽기 전용 글자로 보인다 (textContent만 쓴다).

import { languageName } from './code-highlight.js';

export const TEXT_COLORS = [['red', '빨강'], ['orange', '주황'], ['green', '초록'], ['blue', '파랑'], ['purple', '보라'],
  ['mauve', '자주'], ['gray', '회색']];
export const HIGHLIGHTS = [['yellow', '노랑'], ['green', '연두'], ['pink', '분홍'], ['blue', '하늘']];
export const SIZES = [['small', '작게'], ['large', '크게'], ['xlarge', '아주 크게']];

// 사진 크기 (D-114). 글 칸 너비의 10~100%를 10% 단위로 고른다. 주소 끝에 "#w=60"처럼 붙여 마크다운에 남기고,
// 편집 칸과 글 화면은 CSS로 그 너비만큼 보여 준다. 100%(원래 크기)는 표시를 붙이지 않는다.
// 서버(MarkdownRenderer)도 정해 둔 값만 남기고 다른 #표시는 지운다. CSP가 style 속성을 막으므로 class·주소로 준다.
export const IMAGE_WIDTHS = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100];
const WIDTH_MARK = /#w=(\d{2,3})$/;

/** 사진 주소에 붙은 크기(%). 없거나 정해 둔 값이 아니면 100. */
export function imageWidthOf(url) {
  const m = WIDTH_MARK.exec(String(url || ''));
  const w = m ? Number(m[1]) : 100;
  return IMAGE_WIDTHS.includes(w) ? w : 100;
}

/** 사진 주소의 크기 표시를 w(%)로 바꾼다. 100이면 표시를 뺀다. */
export function withImageWidth(url, w) {
  const base = String(url || '').replace(/#.*$/, '');
  return w >= 100 ? base : `${base}#w=${w}`;
}

// 서버의 MarkdownRenderer.TEXT_SIZES·HIGHLIGHTS·TEXT_COLORS와 같다. 마크다운에는 바깥부터 이 순서로 감싼다.
const GROUPS = [
  ['ts-', SIZES.map(([c]) => 'ts-' + c)],
  ['hl-', HIGHLIGHTS.map(([c]) => 'hl-' + c)],
  ['tc-', TEXT_COLORS.map(([c]) => 'tc-' + c)],
];
const ALLOWED = new Set(GROUPS.flatMap(([, list]) => list));

/** class 글자에서 정해 둔 것만, 종류(크기·형광펜·색)마다 하나씩 (나중 것이 이김), 정해 둔 순서로. */
function cleanClasses(value) {
  const picked = {};
  String(value || '').split(/\s+/).filter((c) => ALLOWED.has(c)).forEach((c) => { picked[c.slice(0, 3)] = c; });
  return GROUPS.map(([prefix]) => picked[prefix]).filter(Boolean);
}

/** 마크다운에서 코드 블록과 `코드`를 뺀 글자 부분에만 fn을 적용한다. */
function mapText(markdown, fn) {
  let fence = null;
  return String(markdown || '').split('\n').map((line) => {
    const f = /^\s{0,3}(`{3,}|~{3,})/.exec(line);
    if (fence) {
      if (f && f[1][0] === fence[0] && f[1].length >= fence.length) fence = null;
      return line;
    }
    if (f) {
      fence = f[1];
      return line;
    }
    return line.split(/(`+[^`]*`+)/).map((part, i) => (i % 2 ? part : fn(part))).join('');
  }).join('\n');
}

const SPAN_TAG = /<span\b[^>]*>|<\/span\s*>/gi;
const CLASS_ATTR = /\bclass\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i;

/**
 * 겹친 span(<span class="ts-large"><span class="tc-red">가</span></span>)을 class를 합친 span 하나로 편다.
 * 편집기 안에서는 span 하나에 class 여러 개로 다루고, 저장할 때 다시 하나씩 감싼다 (customMarkdownRenderer).
 * 코드 블록과 `코드` 안의 글자는 건드리지 않는다.
 */
export function flattenSpans(markdown) {
  const stack = [];
  const flattenPart = (text) => text.replace(SPAN_TAG, (tag) => {
    const before = cleanClasses(stack.join(' '));
    if (tag[1] === '/') {
      if (!stack.length) return '';
      stack.pop();
    } else {
      const m = CLASS_ATTR.exec(tag);
      stack.push(m ? (m[1] ?? m[2] ?? m[3]) : '');
    }
    const after = cleanClasses(stack.join(' '));
    if (before.join(' ') === after.join(' ')) return '';
    return (before.length ? '</span>' : '') + (after.length ? `<span class="${after.join(' ')}">` : '');
  });
  return mapText(markdown, flattenPart);
}

const PAIR = /(\*\*|\*|~~)((?:<span class="[a-z-]+">)+)([^<>\n]*?)((?:<\/span>)+)\1/g;

/**
 * 굵게·기울임·취소선과 서식 span이 같은 글자를 감싸면 표시(**)를 span 안쪽으로 옮긴다.
 * `**<span class="tc-red">맛집</span>**이`는 마크다운 규칙상 굵게가 되지 않지만,
 * `<span class="tc-red">**맛집**</span>이`는 된다. 보이는 모양은 같다.
 */
export function moveDelimitersInside(markdown) {
  return mapText(markdown, (part) => {
    let out = part;
    for (let n = 0; n < 3; n += 1) {
      const next = out.replace(PAIR, (all, d, opens, text, closes) => (
        opens.split('<span').length - 1 === closes.length / 7 ? opens + d + text + d + closes : all));
      if (next === out) break;
      out = next;
    }
    return out;
  });
}

function make(tag, attrs = {}, text) {
  const e = document.createElement(tag);
  Object.entries(attrs).forEach(([k, v]) => e.setAttribute(k, v));
  if (text != null) e.textContent = text;
  return e;
}

// ----- HTML 구조 보기: 줄을 나누고 들여 써서 읽기 쉽게 -----
const BLOCKS = new Set(['p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'blockquote', 'pre', 'table', 'thead',
  'tbody', 'tr', 'th', 'td', 'hr', 'div']);
const VOIDS = new Set(['br', 'hr', 'img', 'input']);

const BOOLEAN_ATTRS = new Set(['checked', 'disabled']);

function openTag(node) {
  const attrs = [...node.attributes].map((a) => (BOOLEAN_ATTRS.has(a.name) ? ` ${a.name}` : ` ${a.name}="${a.value}"`)).join('');
  return `<${node.localName}${attrs}>`;
}

const KEEP_ATTRS = { a: ['href'], img: ['src', 'alt'], span: ['class'], ol: ['start'] };

/**
 * 편집 칸의 HTML을 글 화면에 실제로 나갈 모양에 가깝게 바꾼다.
 * class 여러 개인 span은 하나씩 겹쳐 감싸고, 이어진 줄(문단)은 <br>로 잇고, 편집기용 속성은 뺀다.
 */
function toStoredShape(doc) {
  const root = doc.body;
  // 체크박스 목록 줄은 글 화면처럼 맨 앞에 누를 수 없는 checkbox를 둔다 (D-110)
  const tasks = [...root.querySelectorAll('li[data-task="true"], li.task-list-item')].map((li) => {
    const checked = li.getAttribute('data-task-checked') === 'true' || li.classList.contains('checked');
    return [li, checked];
  });
  root.querySelectorAll('*').forEach((node) => {
    const keep = KEEP_ATTRS[node.localName] || [];
    [...node.attributes].forEach((a) => { if (!keep.includes(a.name)) node.removeAttribute(a.name); });
  });
  root.querySelectorAll('span').forEach((span) => {
    const classes = cleanClasses(span.getAttribute('class'));
    if (!classes.length) {
      span.replaceWith(...span.childNodes);
      return;
    }
    span.setAttribute('class', classes[0]);
    let inner = span;
    classes.slice(1).forEach((c) => {
      const child = doc.createElement('span');
      child.setAttribute('class', c);
      child.append(...inner.childNodes);
      inner.append(child);
      inner = child;
    });
  });
  [root, ...root.querySelectorAll('li, blockquote')].forEach((box) => {
    let prev = null;
    [...box.children].forEach((child) => {
      if (child.localName !== 'p') {
        prev = null;
        return;
      }
      if (!child.textContent.trim() && !child.querySelector('img')) {
        child.remove();
        prev = null;
        return;
      }
      if (prev) {
        prev.append(doc.createElement('br'), ...child.childNodes);
        child.remove();
      } else {
        prev = child;
      }
    });
    if (box.localName === 'li' && box.children.length === 1 && box.firstElementChild.localName === 'p') {
      box.firstElementChild.replaceWith(...box.firstElementChild.childNodes);
    }
  });
  tasks.forEach(([li, checked]) => {
    const input = doc.createElement('input');
    input.setAttribute('type', 'checkbox');
    if (checked) input.setAttribute('checked', '');
    input.setAttribute('disabled', '');
    li.setAttribute('class', 'task-list-item');
    li.prepend(input, ' ');
  });
}

/** HTML을 [{kind: 'tag'|'text', text}] 줄 목록으로. 글자만 만들고 화면에는 textContent로 넣는다. */
function prettyLines(html) {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  toStoredShape(doc);
  const lines = [];
  const pad = (depth) => '  '.repeat(depth);
  // 줄바꿈(<br>) 뒤는 다음 줄에 이어 써서 글 모양처럼 읽히게 한다
  const inline = (node, out, depth) => {
    node.childNodes.forEach((child) => {
      if (child.nodeType === Node.TEXT_NODE) {
        if (child.data) out.push({ kind: 'text', text: child.data.replace(/\s+/g, ' ') });
      } else if (child.nodeType === Node.ELEMENT_NODE) {
        out.push({ kind: 'tag', text: openTag(child) });
        if (child.localName === 'br' && child.nextSibling) out.push({ kind: 'text', text: '\n' + pad(depth + 1) });
        if (!VOIDS.has(child.localName)) {
          inline(child, out, depth);
          out.push({ kind: 'tag', text: `</${child.localName}>` });
        }
      }
    });
  };
  const walk = (node, depth) => {
    node.childNodes.forEach((child) => {
      if (child.nodeType === Node.TEXT_NODE) {
        if (child.data.trim()) lines.push([{ kind: 'text', text: pad(depth) + child.data.trim() }]);
        return;
      }
      if (child.nodeType !== Node.ELEMENT_NODE) return;
      const name = child.localName;
      const hasBlock = [...child.children].some((c) => BLOCKS.has(c.localName));
      if (!BLOCKS.has(name) || VOIDS.has(name)) {
        const out = [{ kind: 'text', text: pad(depth) }];
        inline({ childNodes: [child] }, out, depth);
        lines.push(out);
      } else if (!hasBlock || name === 'pre') {
        const out = [{ kind: 'text', text: pad(depth) }, { kind: 'tag', text: openTag(child) }];
        if (name === 'pre') out.push({ kind: 'text', text: child.textContent });
        else inline(child, out, depth);
        out.push({ kind: 'tag', text: `</${name}>` });
        lines.push(out);
      } else {
        lines.push([{ kind: 'text', text: pad(depth) }, { kind: 'tag', text: openTag(child) }]);
        walk(child, depth + 1);
        lines.push([{ kind: 'text', text: pad(depth) }, { kind: 'tag', text: `</${name}>` }]);
      }
    });
  };
  walk(doc.body, 0);
  return lines;
}

/** 코드 블록에서 고를 수 있는 언어 (D-117). 이 이름이 마크다운 ```이름 과 글 화면의 언어 표시가 된다. */
const CODE_LANGUAGES = ['java', 'javascript', 'typescript', 'python', 'c', 'cpp', 'csharp', 'kotlin', 'go', 'rust',
  'swift', 'php', 'ruby', 'dart', 'r', 'sql', 'html', 'css', 'json', 'yaml', 'bash', 'markdown', 'diff', 'text'];
/** ⋮ 메뉴의 언어 목록: 맨 위 "일반 텍스트"(언어 없음), 나머지는 보이는 이름 순서 (D-120) */
const MENU_LANGUAGES = CODE_LANGUAGES.filter((l) => l !== 'text')
  .sort((a, b) => languageName(a).localeCompare(languageName(b), 'en', { sensitivity: 'base' }));
const DEFAULT_LANGUAGE_KEY = 'creat.codeBlockLanguage';

/** [기본 언어]로 고른 언어. 이 브라우저에만 남는다. 없거나 못 읽으면 '' */
function defaultCodeLanguage() {
  try {
    const v = localStorage.getItem(DEFAULT_LANGUAGE_KEY) || '';
    return CODE_LANGUAGES.includes(v) && v !== 'text' ? v : '';
  } catch {
    return '';
  }
}
function setDefaultCodeLanguage(lang) {
  try {
    if (lang) localStorage.setItem(DEFAULT_LANGUAGE_KEY, lang);
    else localStorage.removeItem(DEFAULT_LANGUAGE_KEY);
  } catch {
    // 저장하지 못해도 이번 코드 블록에는 영향 없다
  }
}

/**
 * 편집기를 만든다.
 * @param {object} o
 * @param {HTMLElement} o.host 편집기를 넣을 칸
 * @param {string} o.height 편집 칸 높이
 * @param {string} o.placeholder 빈 칸 안내 글
 * @param {(blob: Blob, callback: Function) => void} o.uploadImage 사진 올리기 (Toast UI addImageBlobHook)
 * @param {boolean} [o.withCode] 코드·표 도구도 보일지
 * @returns {{ editor: object, getMarkdown: () => string, setMarkdown: (md: string) => void, closeHtml: () => void }}
 */
export function createRichEditor({ host, height, placeholder, uploadImage, withCode = false }) {
  host.classList.add('rich-editor');
  // 도구 막대가 붙을 높이 = 위쪽 메뉴 높이 (관리자 화면처럼 메뉴가 붙지 않으면 0)
  const header = document.querySelector('.site-header');
  const stickyHeader = header && getComputedStyle(header).position === 'sticky';
  host.style.setProperty('--sticky-top', stickyHeader ? `${header.offsetHeight}px` : '0px');
  const head = make('div', { class: 'rich-editor-head' });
  head.append(make('p', { class: 'rich-editor-tip' }, '쓰는 칸에 보이는 모양 그대로 올라가요. 색·크기·형광펜을 먼저 고르고 쓰거나, 글자를 골라 바꿀 수 있어요.'));
  const htmlButton = make('button', { type: 'button', class: 'rich-html-toggle', 'aria-pressed': 'false' }, 'HTML 보기');
  head.append(htmlButton);

  const body = make('div', { class: 'rich-editor-body' });
  const editorBox = make('div', { class: 'rich-editor-main' });
  const panel = make('aside', { class: 'rich-html', 'aria-label': 'HTML 구조' });
  panel.hidden = true;
  const panelHead = make('div', { class: 'rich-html-head' });
  panelHead.append(make('strong', {}, 'HTML 구조'), make('span', { class: 'rich-html-note' }, '읽기 전용 · 쓰는 대로 바로 바뀌어요'));
  const closeButton = make('button', { type: 'button', class: 'rich-html-close', 'aria-label': 'HTML 구조 닫기' }, '닫기');
  panelHead.append(closeButton);
  const code = make('pre', { class: 'rich-html-code', tabindex: '0' });
  panel.append(panelHead, code);
  body.append(editorBox, panel);
  host.replaceChildren(head, body);

  // 색·크기·형광펜 고르는 작은 창
  function popupItem(name, tooltip, text, className, entries, clearText, prefix) {
    const box = make('div', { class: 'color-popup' });
    entries.forEach(([value, label, cls, shown]) => {
      const b = make('button', { type: 'button', class: cls, title: label, 'aria-label': label }, shown ?? '');
      b.addEventListener('click', () => format(prefix, value));
      box.append(b);
    });
    const clear = make('button', { type: 'button', class: 'color-clear' }, clearText);
    clear.addEventListener('click', () => format(prefix, null));
    box.append(clear);
    return { name, tooltip, text, className, popup: { body: box, style: { width: 'auto' } } };
  }
  const sizeItem = popupItem('fontSize', '글자 크기', '가', 'toolbar-size',
    SIZES.map(([c, n]) => ['ts-' + c, n, 'size-choice ts-' + c, n]), '보통 크기', 'ts-');
  const colorItem = popupItem('color', '글자 색', 'A', 'toolbar-color',
    TEXT_COLORS.map(([c, n]) => ['tc-' + c, '글자 색 ' + n, 'color-swatch tc-bg-' + c]), '색 빼기', 'tc-');
  const highlightItem = popupItem('highlight', '형광펜', '형', 'toolbar-highlight',
    HIGHLIGHTS.map(([c, n]) => ['hl-' + c, '형광펜 ' + n, 'color-swatch hl-' + c, '가']), '형광펜 빼기', 'hl-');

  // 목록: 글머리 기호(ul)·번호(ol)·체크박스(task, D-110). 체크박스는 "- [ ] 할 일"·"- [x] 한 일"로 저장한다
  // 좁은 화면에서는 뒤쪽 도구가 [⋯] 안으로 들어가므로 자주 쓰는 꾸미기와 목록을 앞에 둔다
  const toolbarItems = [['bold', 'italic', 'strike', colorItem, highlightItem, sizeItem], ['ul', 'ol', 'task'],
    ['heading', 'hr', 'quote'], withCode ? ['table', 'image', 'link'] : ['image', 'link']];
  if (withCode) toolbarItems.push(['code', 'codeblock']);

  /** 편집기 안의 span 표시를 정리한다: 정해 둔 class만, 종류마다 하나씩, 다른 속성(style 등)은 없이. */
  function normalizePlugin(context) {
    return {
      wysiwygPlugins: [() => new context.pmState.Plugin({
        appendTransaction(transactions, _old, state) {
          if (!transactions.some((t) => t.docChanged)) return null;
          const type = state.schema.marks.span;
          if (!type) return null;
          let tr = null;
          state.doc.descendants((node, pos) => {
            if (!node.isText) return;
            const mark = type.isInSet(node.marks);
            if (!mark) return;
            const attrs = mark.attrs.htmlAttrs || {};
            const classes = cleanClasses(attrs.class);
            if (Object.keys(attrs).length === 1 && attrs.class === classes.join(' ')) return;
            tr = tr || state.tr;
            tr.removeMark(pos, pos + node.nodeSize, type);
            if (classes.length) tr.addMark(pos, pos + node.nodeSize, type.create({ htmlAttrs: { class: classes.join(' ') } }));
          });
          return tr;
        },
      })],
    };
  }

  // 편집기를 다 만들기 전(처음 그릴 때)에는 크기 막대를 맞추지 않는다
  let sizeBarReady = false;
  /** 사진을 고르면(눌러 선택) 크기 막대를 보여 준다. 편집 상태가 바뀔 때마다 다시 맞춘다. */
  function imageSizePlugin(context) {
    return { wysiwygPlugins: [() => new context.pmState.Plugin({ view: () => ({ update: () => syncSizeBar() }) })] };
  }

  /**
   * 빈 글에서 바로 코드 블록을 넣으면 Toast UI가 "빈 글" 안내 문구를 코드 블록 안에 그려, 그 문구에 막혀 글자가 들어가지 않았다.
   * 코드 블록만 있는 글이면 아래에 빈 문단을 하나 두어 빈 글로 보지 않게 한다. 빈 문단은 저장할 때 없어진다 (D-117).
   * 새로 넣은 빈 코드 블록에는 [기본 언어]로 고른 언어를 붙인다 (D-120).
   */
  function codeBlockPlugin(context) {
    const countBlocks = (doc) => {
      let n = 0;
      doc.descendants((node) => { if (node.type.name === 'codeBlock') n += 1; });
      return n;
    };
    return {
      wysiwygPlugins: [() => new context.pmState.Plugin({
        appendTransaction(transactions, oldState, state) {
          if (!transactions.some((t) => t.docChanged)) return null;
          let tr = null;
          const lang = defaultCodeLanguage();
          if (lang && countBlocks(state.doc) > countBlocks(oldState.doc)) {
            state.doc.descendants((node, pos) => {
              if (node.type.name !== 'codeBlock' || node.attrs.language || node.content.size) return;
              tr = tr || state.tr;
              tr.setNodeMarkup(pos, null, { ...node.attrs, language: lang });
            });
          }
          const doc = tr ? tr.doc : state.doc;
          if (doc.childCount === 1 && doc.firstChild.type.name === 'codeBlock') {
            tr = tr || state.tr;
            tr.insert(doc.content.size, state.schema.nodes.paragraph.create());
          }
          return tr;
        },
      })],
    };
  }

  // 코드 블록 언어를 고르면 그 언어의 색으로 칠한다 (D-117). Prism과 Toast UI 플러그인은 글쓰기 화면이 미리 읽어 둔다
  const plugins = [normalizePlugin, imageSizePlugin];
  if (withCode) {
    plugins.push(codeBlockPlugin);
    if (window.toastui.codeSyntaxHighlight && window.Prism) {
      // 고르는 목록에는 자주 쓰는 이름만 (Prism에는 js·py 같은 줄임말과 내부용 이름도 있어 목록이 길어진다)
      const languages = {};
      CODE_LANGUAGES.forEach((name) => { if (window.Prism.languages[name]) languages[name] = window.Prism.languages[name]; });
      const highlighter = Object.assign(Object.create(window.Prism), { languages });
      plugins.push([window.toastui.codeSyntaxHighlight, { highlighter }]);
    }
  }

  const editor = new window.toastui.Editor({
    el: editorBox,
    // 쓰는 칸은 글·사진만큼 늘어난다. 큰 사진을 넣어도 이어 쓰는 글이 칸 밖으로 밀려 가려지지 않는다 (D-114).
    // 도구 막대는 화면 위에 붙어 따라온다 (common.css .rich-editor .toastui-editor-toolbar)
    height: 'auto',
    minHeight: height,
    initialEditType: 'wysiwyg',
    hideModeSwitch: true,
    language: 'ko-KR',
    usageStatistics: false,
    placeholder,
    toolbarItems,
    plugins,
    // 사진을 넣으면 바로 다음 줄로 내려가서, 이어 쓰는 글이 사진 옆에 붙지 않고 사진 아래에 쓰인다 (D-114)
    hooks: { addImageBlobHook: (blob, callback) => uploadImage(blob, (url, alt) => {
      callback(url, alt);
      breakAfterImage();
    }) },
    // 마크다운의 <span class="…">을 편집 칸에서도 그 모양으로 보이게 한다
    customHTMLRenderer: {
      htmlInline: {
        span(node, { entering }) {
          if (!entering) return { type: 'closeTag', tagName: 'span' };
          const classes = cleanClasses(node.attrs && node.attrs.class);
          return { type: 'openTag', tagName: 'span', attributes: classes.length ? { class: classes.join(' ') } : {} };
        },
      },
    },
    // 편집 칸의 span(class 여러 개)을 저장할 때는 class 하나씩 겹쳐 감싼다. 서버가 그 모양만 남긴다 (D-102).
    customMarkdownRenderer: {
      // 글머리 목록 표시는 "- "로 쓴다 (Toast UI 기본은 "* "). 체크박스 목록도 "- [ ] "가 된다 (D-110)
      bulletList(nodeInfo, context) {
        return { ...context.origin(), delim: '-' };
      },
      html(nodeInfo, context) {
        const mark = nodeInfo.node;
        if (!mark || !mark.type || mark.type.name !== 'span') return context.origin();
        const classes = cleanClasses(mark.attrs.htmlAttrs && mark.attrs.htmlAttrs.class);
        if (context.entering) return { rawHTML: classes.map((c) => `<span class="${c}">`).join(''), delim: '' };
        return { rawHTML: '</span>'.repeat(classes.length), delim: '' };
      },
    },
  });
  editor.on('beforeConvertWysiwygToMarkdown', (markdown) => moveDelimitersInside(markdown));
  const view = () => editor.wwEditor.view;
  const spanType = view().state.schema.marks.span;

  /**
   * 크기·색·형광펜 하나를 바꾼다. 고른 글자가 있으면 그 글자에, 없으면 이제부터 쓸 글자에 적용한다.
   * value가 null이면 그 종류(prefix)를 뺀다.
   */
  function format(prefix, value) {
    const v = view();
    const { state } = v;
    const type = spanType;
    const merged = (mark) => {
      const rest = cleanClasses(mark && mark.attrs.htmlAttrs && mark.attrs.htmlAttrs.class).filter((c) => !c.startsWith(prefix));
      return cleanClasses([...rest, value].filter(Boolean).join(' '));
    };
    const create = (classes) => (classes.length ? type.create({ htmlAttrs: { class: classes.join(' ') } }) : null);
    const { from, to, empty, $from } = state.selection;
    const tr = state.tr;
    if (empty) {
      const next = create(merged(type.isInSet(state.storedMarks || $from.marks())));
      tr.removeStoredMark(type);
      if (next) tr.addStoredMark(next);
    } else {
      state.doc.nodesBetween(from, to, (node, pos) => {
        if (!node.isText) return;
        const start = Math.max(pos, from);
        const end = Math.min(pos + node.nodeSize, to);
        const next = create(merged(type.isInSet(node.marks)));
        tr.removeMark(start, end, type);
        if (next) tr.addMark(start, end, next);
      });
    }
    v.dispatch(tr);
    editor.eventEmitter.emit('closePopup');
    v.focus();
    showCurrent();
  }

  // 도구 막대의 A·형 버튼 밑줄에 지금 커서 자리의 색·형광펜을 보여 준다
  function showCurrent() {
    const { state } = view();
    const mark = spanType.isInSet(state.storedMarks || state.selection.$from.marks());
    const classes = cleanClasses(mark && mark.attrs.htmlAttrs.class);
    const color = classes.find((c) => c.startsWith('tc-'));
    const hl = classes.find((c) => c.startsWith('hl-'));
    const size = classes.find((c) => c.startsWith('ts-'));
    host.querySelectorAll('.toolbar-color').forEach((b) => { b.dataset.now = color ? color.slice(3) : ''; });
    host.querySelectorAll('.toolbar-highlight').forEach((b) => { b.dataset.now = hl ? hl.slice(3) : ''; });
    host.querySelectorAll('.toolbar-size').forEach((b) => { b.dataset.now = size ? size.slice(3) : ''; });
  }
  editor.on('caretChange', showCurrent);

  // ----- 사진 크기 막대 (D-114) -----
  // 사진을 누르면 위에 막대가 뜬다. [−]·[+]·끌기 막대로 바꾸거나, 사진 위에서 마우스 휠·두 손가락 확대/축소로 바꾼다.
  const sizeBar = make('div', { class: 'image-size-bar', role: 'group', 'aria-label': '사진 크기' });
  sizeBar.hidden = true;
  const smaller = make('button', { type: 'button', class: 'image-size-step', 'aria-label': '사진 작게' }, '−');
  const range = make('input', { type: 'range', min: String(IMAGE_WIDTHS[0]), max: '100', step: '10', 'aria-label': '사진 크기 (%)' });
  const bigger = make('button', { type: 'button', class: 'image-size-step', 'aria-label': '사진 크게' }, '+');
  const shown = make('output', { class: 'image-size-value' }, '100%');
  const reset = make('button', { type: 'button', class: 'image-size-reset' }, '원래 크기');
  sizeBar.title = '사진 위에서 마우스 휠을 돌리거나 두 손가락으로 벌리고 오므려도 크기가 바뀌어요';
  sizeBar.append(make('span', { class: 'image-size-label' }, '크기'), smaller, range, bigger, shown, reset);
  editorBox.append(sizeBar);
  // 막대를 누르는 동안 편집 칸이 선택을 잃지 않게 한다 (끌기 막대는 그대로 끌린다)
  sizeBar.addEventListener('mousedown', (e) => { if (e.target !== range) e.preventDefault(); });

  /** pos의 노드(사진) 하나를 고른 선택. Toast UI가 NodeSelection을 내보내지 않아 Selection.fromJSON으로 만든다. */
  function nodeSelection(state, doc, pos) {
    const Selection = Object.getPrototypeOf(state.selection.constructor);
    return Selection.fromJSON(doc, { type: 'node', anchor: pos });
  }

  /** 커서 바로 앞이 방금 넣은 사진이면 그 뒤에서 줄(문단)을 나눈다. 글 화면에서도 사진 다음 줄이 된다. */
  function breakAfterImage() {
    const v = view();
    const { state } = v;
    const { $from, empty } = state.selection;
    if (!empty || !$from.nodeBefore || $from.nodeBefore.type.name !== 'image') return;
    v.dispatch(state.tr.split($from.pos).scrollIntoView());
  }

  /** 지금 골라 둔 사진: { pos, node, dom } 또는 null */
  function selectedImage() {
    const v = view();
    const sel = v.state.selection;
    if (!sel.node || sel.node.type.name !== 'image') return null;
    const dom = v.nodeDOM(sel.from);
    return dom && dom.nodeName === 'IMG' ? { pos: sel.from, node: sel.node, dom } : null;
  }

  function syncSizeBar() {
    if (!sizeBarReady) return;
    const picked = selectedImage();
    if (!picked) {
      sizeBar.hidden = true;
      return;
    }
    const w = imageWidthOf(picked.node.attrs.imageUrl);
    range.value = String(w);
    shown.textContent = `${w}%`;
    smaller.disabled = w <= IMAGE_WIDTHS[0];
    bigger.disabled = w >= 100;
    reset.disabled = w >= 100;
    sizeBar.hidden = false;
    placeSizeBar(picked.dom);
  }

  /** 막대는 사진 왼쪽 위 안쪽에 겹쳐 둔다. 사진이 작아 다 들어가지 않으면 사진 위(자리가 없으면 아래)에 둔다. */
  function placeSizeBar(img) {
    const box = editorBox.getBoundingClientRect();
    const r = img.getBoundingClientRect();
    const barW = sizeBar.offsetWidth;
    const barH = sizeBar.offsetHeight;
    let top;
    if (r.width >= barW + 16 && r.height >= barH + 16) top = r.top - box.top + 8;
    else if (r.top - box.top >= barH + 6 + 48) top = r.top - box.top - barH - 6; // 48: 도구 막대 높이
    else top = r.bottom - box.top + 6;
    const left = r.width >= barW + 16 ? r.left - box.left + 8 : r.left - box.left;
    // 화면을 내려 사진 윗부분이 도구 막대 밑으로 들어가면, 막대는 도구 막대 바로 아래에 둔다
    const toolbar = host.querySelector('.toastui-editor-toolbar');
    if (toolbar) {
      const minTop = toolbar.getBoundingClientRect().bottom - box.top + 6;
      if (top < minTop && r.bottom - box.top > minTop + barH) top = minTop;
    }
    sizeBar.style.top = `${top}px`;
    sizeBar.style.left = `${Math.max(8, Math.min(left, box.width - barW - 8))}px`;
  }

  function setImageWidth(w) {
    const picked = selectedImage();
    if (!picked) return;
    const next = IMAGE_WIDTHS.reduce((a, b) => (Math.abs(b - w) < Math.abs(a - w) ? b : a));
    const url = withImageWidth(picked.node.attrs.imageUrl, next);
    if (url === picked.node.attrs.imageUrl) return;
    const v = view();
    const tr = v.state.tr.setNodeMarkup(picked.pos, undefined, { ...picked.node.attrs, imageUrl: url });
    tr.setSelection(nodeSelection(v.state, tr.doc, picked.pos));
    v.dispatch(tr);
  }
  const stepBy = (d) => {
    const picked = selectedImage();
    if (picked) setImageWidth(imageWidthOf(picked.node.attrs.imageUrl) + d);
  };
  smaller.addEventListener('click', () => stepBy(-10));
  bigger.addEventListener('click', () => stepBy(10));
  reset.addEventListener('click', () => setImageWidth(100));
  range.addEventListener('input', () => setImageWidth(Number(range.value)));

  // 사진을 누르면 그 사진을 고른다. 휠은 고른 사진 위에서만 크기를 바꾸고, 그 밖에서는 화면을 그대로 내린다.
  const wwRoot = editor.getEditorElements().wwEditor;
  wwRoot.addEventListener('mousedown', (e) => {
    if (e.target.nodeName !== 'IMG' || e.target.classList.contains('ProseMirror-separator')) return;
    const v = view();
    const pos = v.posAtDOM(e.target, 0);
    const node = v.state.doc.nodeAt(pos);
    if (!node || node.type.name !== 'image') return;
    e.preventDefault();
    v.dispatch(v.state.tr.setSelection(nodeSelection(v.state, v.state.doc, pos)));
    v.focus();
  });
  let wheelSum = 0;
  wwRoot.addEventListener('wheel', (e) => {
    const picked = selectedImage();
    if (!picked || e.target !== picked.dom) return;
    e.preventDefault();
    // 두 손가락 확대/축소(ctrlKey)는 작은 값이 여러 번 오므로 모아서 한 칸씩 바꾼다
    wheelSum += e.deltaY;
    const threshold = e.ctrlKey ? 12 : 40;
    if (Math.abs(wheelSum) < threshold) return;
    stepBy(wheelSum < 0 ? 10 : -10);
    wheelSum = 0;
  }, { passive: false });
  window.addEventListener('scroll', () => { if (!sizeBar.hidden) syncSizeBar(); }, { passive: true });
  window.addEventListener('resize', () => { if (!sizeBar.hidden) syncSizeBar(); });
  sizeBarReady = true;

  // ----- 코드 블록 ⋮ 메뉴 (D-120) -----
  // 코드 블록 위에 마우스를 올리거나 그 안에 커서가 있으면 오른쪽 위에 "언어 이름 ⋮" 버튼이 뜬다.
  // 누르면 복사·잘라내기·삭제·언어 ▸·기본 언어 ▸ 메뉴가 열린다. Toast UI의 언어 입력 칸 대신 쓴다.
  if (withCode) setupCodeMenu();

  function setupCodeMenu() {
    const button = make('button', { type: 'button', class: 'code-menu-button', 'aria-haspopup': 'menu', 'aria-expanded': 'false' });
    const buttonLang = make('span', { class: 'code-menu-lang' });
    button.append(buttonLang, make('span', { class: 'code-menu-dots', 'aria-hidden': 'true' }, '⋮'));
    button.hidden = true;
    const menu = make('div', { class: 'code-menu', role: 'menu', 'aria-label': '코드 블록' });
    menu.hidden = true;
    editorBox.append(button, menu);

    // 코드 블록 바깥 div. 문법 강조 플러그인이 있으면 그 플러그인의 class가 붙는다
    const CODE_BLOCK_DOM = '.toastui-editor-ww-code-block, .toastui-editor-ww-code-block-highlighting';
    let target = null; // 지금 버튼이 붙은 코드 블록의 바깥 div
    let hovered = null;

    /** 코드 블록 바깥 div → { pos, node }. 블록이 지워졌으면 null */
    function blockOf(dom) {
      if (!dom || !dom.isConnected) return null;
      const v = view();
      let found = null;
      v.state.doc.descendants((node, pos) => {
        if (found) return false;
        if (node.type.name === 'codeBlock' && v.nodeDOM(pos) === dom) found = { pos, node };
        return !found;
      });
      return found;
    }
    /** 커서가 들어 있는 코드 블록의 바깥 div */
    function cursorBlock() {
      const v = view();
      const { $from } = v.state.selection;
      for (let d = $from.depth; d > 0; d -= 1) {
        if ($from.node(d).type.name === 'codeBlock') return v.nodeDOM($from.before(d));
      }
      return null;
    }

    function place() {
      if (!menu.hidden) return; // 메뉴가 열린 동안에는 그 블록에 붙어 있는다
      if (hovered && !hovered.isConnected) hovered = null; // 언어를 바꾸면 Toast UI가 블록을 새로 그린다
      const dom = hovered || cursorBlock();
      const block = blockOf(dom);
      if (!block) {
        target = null;
        button.hidden = true;
        return;
      }
      target = dom;
      const lang = block.node.attrs.language;
      buttonLang.textContent = lang && lang !== 'text' ? languageName(lang) : '일반 텍스트';
      button.setAttribute('aria-label', `코드 블록 메뉴 (${buttonLang.textContent})`);
      button.hidden = false;
      const box = editorBox.getBoundingClientRect();
      const r = dom.getBoundingClientRect();
      button.style.top = `${r.top - box.top + 6}px`;
      button.style.left = `${r.right - box.left - button.offsetWidth - 6}px`;
    }

    wwRoot.addEventListener('mouseover', (e) => {
      const dom = e.target.closest && e.target.closest(CODE_BLOCK_DOM);
      if (dom === hovered) return;
      hovered = dom;
      place();
    });
    editorBox.addEventListener('mouseleave', () => { hovered = null; place(); });
    editor.on('caretChange', place);
    editor.on('change', place);
    window.addEventListener('scroll', place, { passive: true });
    window.addEventListener('resize', place);
    // 버튼·메뉴를 누르는 동안 편집 칸이 커서를 잃지 않게 한다
    button.addEventListener('mousedown', (e) => e.preventDefault());
    menu.addEventListener('mousedown', (e) => e.preventDefault());

    function item(label, onClick, extra = {}) {
      const b = make('button', { type: 'button', role: 'menuitem', class: 'code-menu-item', ...extra });
      b.append(make('span', {}, label));
      b.addEventListener('click', onClick);
      return b;
    }
    /** 언어 고르기 하위 메뉴. current는 체크 표시할 언어(없으면 ''). */
    function languageList(current, pick) {
      const list = make('div', { class: 'code-menu-sub', role: 'menu' });
      const choices = [['', '일반 텍스트'], ...MENU_LANGUAGES.map((l) => [l, languageName(l)])];
      choices.forEach(([lang, name]) => {
        const b = make('button', { type: 'button', role: 'menuitemradio', class: 'code-menu-item', 'aria-checked': String(lang === current) });
        b.append(make('span', { class: 'code-menu-check', 'aria-hidden': 'true' }, lang === current ? '✓' : ''), make('span', {}, name));
        b.addEventListener('click', () => pick(lang));
        list.append(b);
      });
      return list;
    }
    function submenu(label, list) {
      const wrap = make('div', { class: 'code-menu-group' });
      const open = item(label, () => {
        const opened = !wrap.classList.contains('open');
        menu.querySelectorAll('.code-menu-group.open').forEach((g) => g.classList.remove('open'));
        wrap.classList.toggle('open', opened);
        if (opened) placeSub(wrap, list);
      }, { 'aria-haspopup': 'menu' });
      open.classList.add('has-sub');
      open.append(make('span', { class: 'code-menu-arrow', 'aria-hidden': 'true' }, '›'));
      wrap.addEventListener('mouseenter', () => {
        menu.querySelectorAll('.code-menu-group.open').forEach((g) => { if (g !== wrap) g.classList.remove('open'); });
        wrap.classList.add('open');
        placeSub(wrap, list);
      });
      wrap.append(open, list);
      return wrap;
    }
    /** 하위 메뉴는 메뉴 오른쪽에, 자리가 없으면 왼쪽에 둔다. 화면 아래로 넘치면 그만큼 위로 올린다. */
    function placeSub(wrap, list) {
      list.classList.remove('left');
      list.style.marginTop = '';
      const r = list.getBoundingClientRect();
      if (r.right > document.documentElement.clientWidth - 8) list.classList.add('left');
      const overflow = r.bottom - (window.innerHeight - 8);
      if (overflow > 0) list.style.marginTop = `${-Math.min(overflow, Math.max(0, r.top - 8))}px`;
    }

    async function copyText(text) {
      try {
        await navigator.clipboard.writeText(text);
        return true;
      } catch {
        return false;
      }
    }
    function removeBlock(block) {
      const v = view();
      v.dispatch(v.state.tr.delete(block.pos, block.pos + block.node.nodeSize).scrollIntoView());
      v.focus();
    }

    function openMenu(byKeyboard) {
      const block = blockOf(target);
      if (!block) return;
      const current = block.node.attrs.language && block.node.attrs.language !== 'text' ? block.node.attrs.language : '';
      menu.replaceChildren(
        item('복사', async () => { await copyText(block.node.textContent); closeMenu(); }),
        item('잘라내기', async () => {
          await copyText(block.node.textContent);
          const now = blockOf(target);
          closeMenu();
          if (now) removeBlock(now);
        }),
        item('삭제', () => {
          const now = blockOf(target);
          closeMenu();
          if (now) removeBlock(now);
        }),
        make('hr', { class: 'code-menu-line' }),
        submenu('언어', languageList(current, (lang) => {
          const now = blockOf(target);
          closeMenu();
          if (!now) return;
          const v = view();
          v.dispatch(v.state.tr.setNodeMarkup(now.pos, null, { ...now.node.attrs, language: lang || null }));
          v.focus();
        })),
        submenu('기본 언어', languageList(defaultCodeLanguage(), (lang) => {
          setDefaultCodeLanguage(lang);
          closeMenu();
        })),
      );
      menu.hidden = false;
      button.setAttribute('aria-expanded', 'true');
      const box = editorBox.getBoundingClientRect();
      const b = button.getBoundingClientRect();
      menu.style.top = `${b.bottom - box.top + 4}px`;
      menu.style.left = `${Math.max(8, b.right - box.left - menu.offsetWidth)}px`;
      if (byKeyboard) menu.querySelector('button').focus();
    }
    function closeMenu() {
      if (menu.hidden) return;
      menu.hidden = true;
      button.setAttribute('aria-expanded', 'false');
      place();
    }
    button.addEventListener('click', (e) => (menu.hidden ? openMenu(e.detail === 0) : closeMenu()));
    document.addEventListener('mousedown', (e) => {
      if (!menu.hidden && !menu.contains(e.target) && !button.contains(e.target)) closeMenu();
    });
    menu.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        closeMenu();
        button.focus();
      }
    });
  }

  // ----- HTML 구조 칸 -----
  let timer = null;
  function renderHtml() {
    const lines = prettyLines(editor.getHTML());
    code.replaceChildren(...lines.map((parts) => {
      const line = make('span', { class: 'rich-html-line' });
      parts.forEach((p) => line.append(p.kind === 'tag' ? make('span', { class: 'rich-html-tag' }, p.text) : document.createTextNode(p.text)));
      line.append('\n');
      return line;
    }));
    if (!lines.length) code.textContent = '(아직 쓴 내용이 없어요)';
  }
  function setOpen(open) {
    panel.hidden = !open;
    host.classList.toggle('with-html', open);
    htmlButton.setAttribute('aria-pressed', String(open));
    htmlButton.textContent = open ? 'HTML 닫기' : 'HTML 보기';
    if (open) renderHtml();
  }
  htmlButton.addEventListener('click', () => setOpen(panel.hidden));
  closeButton.addEventListener('click', () => { setOpen(false); htmlButton.focus(); });
  editor.on('change', () => {
    if (panel.hidden) return;
    clearTimeout(timer);
    timer = setTimeout(renderHtml, 150);
  });

  return {
    editor,
    getMarkdown: () => editor.getMarkdown(),
    setMarkdown(markdown) {
      editor.setMarkdown(flattenSpans(markdown), false);
      if (!panel.hidden) renderHtml();
    },
    closeHtml: () => setOpen(false),
  };
}

/** Toast UI 파일을 한 번만 불러온다 (관리자 화면처럼 처음부터 넣지 않은 화면용). */
let loading = null;
export function loadToastUi() {
  if (window.toastui) return Promise.resolve();
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const css = document.createElement('link');
      css.rel = 'stylesheet';
      css.href = '/vendor/toastui/toastui-editor.css';
      document.head.prepend(css);
      const script = document.createElement('script');
      script.src = '/vendor/toastui/toastui-editor-all.min.js';
      script.onload = () => resolve();
      script.onerror = () => { loading = null; reject(new Error('편집기를 불러오지 못했습니다. 새로 고침해 주세요.')); };
      document.head.append(script);
    });
  }
  return loading;
}
