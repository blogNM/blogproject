import { api } from '../api.js';
import { JOIN_POLICY, VISIBILITY, roleName, suspendedText } from '../blog-card.js';
import { renderShowcase, renderSideRecent } from '../blog-theme.js';
import { el, initLayout, renderNotFound, toast } from '../layout.js';
import { initPostList, postUrl } from '../post-list.js';
import { openReport } from '../report.js';
import { FONTS, THEMES, applyBlogLook, isAccent } from '../theme.js';

const me = await initLayout();

const $ = (id) => document.getElementById(id);
const slug = decodeURIComponent(location.pathname.split('/')[2] || '');
// 일부 공개 블로그의 공유 링크 값 (D-49)
const key = new URLSearchParams(location.search).get('key');

function showError(text) {
  $('blog-shell').hidden = true;
  $('error').textContent = text;
  $('error').hidden = false;
}

function button(text, onClick, cls = '') {
  const b = el('button', { type: 'button', class: cls }, text);
  b.addEventListener('click', async () => {
    b.disabled = true;
    $('action-message').textContent = '';
    try {
      await onClick();
    } catch (err) {
      $('action-message').textContent = err.message;
      b.disabled = false;
    }
  });
  return b;
}

async function load() {
  let blog;
  try {
    blog = await api.get(`/api/blogs/${encodeURIComponent(slug)}` + (key ? '?key=' + encodeURIComponent(key) : ''));
  } catch (err) {
    if (err.code === 'SUSPENDED') {
      showError(suspendedText(err.details));
    } else {
      if (err.status === 404) {
        renderNotFound('블로그를 찾을 수 없어요. 주소를 확인하거나, 비공개 블로그라면 멤버만 볼 수 있어요.');
      } else {
        showError(err.message);
      }
    }
    return;
  }
  document.title = blog.name + ' - Creat_';
  $('blog-name').textContent = blog.name;
  $('blog-address').textContent = '/blog/' + blog.slug;
  $('blog-description').textContent = blog.description || '';
  $('blog-tags').replaceChildren(...blog.tags.map((t) => el('li', {}, '#' + t)));
  // 블로그장 닉네임을 누르면 프로필로 간다 (SOC-03).
  $('blog-meta').replaceChildren(document.createTextNode('블로그장 '),
    el('a', { href: '/members/' + blog.ownerId, class: 'author-link' }, blog.ownerNickname),
    document.createTextNode(` · 멤버 ${blog.memberCount}명 · 구독 ${blog.subscribers}명 · `
      + `${VISIBILITY[blog.visibility]} · ${JOIN_POLICY[blog.joinPolicy]} · ${blog.createdAt} 개설`));
  if (blog.status === 'CLOSING') {
    $('status-badge').textContent = '폐쇄 예정인 블로그입니다.';
    $('status-badge').hidden = false;
  }
  renderBanners(blog);
  renderActions(blog);
  $('blog').hidden = false;
  renderLook(blog);
  $('blog-shell').hidden = false;
  return blog;
}

// ----- 알림 띠: 폐쇄 예정, 숨김, 나에게 온 블로그장 위임 (BLG-09, ADM-02, 6단계) -----

const CLOSE_BY = {
  OWNER: '블로그장이 블로그를 닫기로 했어요.',
  ADMIN: '운영 정책에 따라 메인 관리자가 블로그를 닫기로 했어요.',
  OWNER_KICKED: '블로그장 권한 박탈로 인한 폐쇄 조치예요.',
};

function banner(text, cls = '') {
  const div = el('div', { class: 'blog-banner ' + cls, role: 'status' });
  div.append(el('p', {}, text));
  return div;
}

function renderBanners(blog) {
  const base = `/api/blogs/${encodeURIComponent(blog.slug)}`;
  const box = $('blog-banners');
  box.replaceChildren();
  if (blog.status === 'CLOSING' && blog.closeScheduledAt) {
    const when = String(blog.closeScheduledAt).replace('T', ' ').substring(0, 16);
    const extra = blog.myRole === 'OWNER' ? ' 블로그 관리에서 철회할 수 있어요.' : '';
    box.append(banner(`${CLOSE_BY[blog.closeRequestedBy] || ''} ${when}에 닫혀요.${extra}`, 'warn'));
  }
  if (blog.hidden && blog.myRole) {
    box.append(banner('운영 정책에 따라 숨겨진 블로그예요. 목록·검색에 나오지 않고 멤버만 볼 수 있어요.', 'warn'));
  }
  if (blog.transfer && blog.transfer.toMe) {
    const div = banner(`${blog.transfer.fromNickname}님이 나에게 블로그장을 넘기려고 해요. 수락하면 이 블로그의 블로그장이 돼요.`);
    const row = el('div', { class: 'banner-actions' });
    const respond = (decision) => async () => {
      if (decision === 'accept' && !confirm('블로그장 위임을 수락할까요?')) return;
      await api.post(`${base}/transfer/${decision}`);
      location.reload();
    };
    row.append(button('수락', respond('accept'), 'primary inline'), button('거절', respond('reject')));
    div.append(row);
    box.append(div);
  }
}

/** 블랙리스트에 막혔을 때 해제 문의 (BLG-13, D-79). 블로그장이 받아 주면 다시 참여할 수 있다. */
function appealForm(base) {
  const form = el('form', { class: 'appeal-form' });
  const area = el('textarea', { maxlength: '500', rows: '3', placeholder: '왜 잘못 막혔다고 생각하는지 적어 주세요 (500자까지)', 'aria-label': '해제 문의 내용' });
  const send = el('button', { type: 'submit', class: 'primary inline' }, '해제 문의 보내기');
  form.append(area, send);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    send.disabled = true;
    try {
      await api.post(base + '/blacklist-appeals', { key, message: area.value });
      form.replaceWith(el('p', { class: 'info' }, '해제 문의를 보냈어요. 블로그장이 확인하면 알림으로 알려 드려요.'));
    } catch (err) {
      $('action-message').textContent = err.message;
      send.disabled = false;
    }
  });
  return form;
}

// ----- 테마 틀과 꾸미기 (D-89) -----

/** 정보 수정 권한이 있는 사람은 ?preview=틀&accent=색&font=글꼴 로 저장 전에 미리 본다. */
function previewTheme(blog) {
  const q = new URLSearchParams(location.search);
  if (!blog.canEditInfo || !THEMES[q.get('preview')]) return null;
  return {
    ...blog.theme,
    theme: q.get('preview'),
    accent: isAccent(q.get('accent')) ? q.get('accent') : blog.theme.accent,
    font: FONTS[q.get('font')] ? q.get('font') : blog.theme.font,
    heroTitle: q.has('heroTitle') ? q.get('heroTitle') : blog.theme.heroTitle,
    heroSubtitle: q.has('heroSubtitle') ? q.get('heroSubtitle') : blog.theme.heroSubtitle,
  };
}

let look;

function renderLook(blog) {
  const preview = previewTheme(blog);
  look = preview || blog.theme;
  const shell = $('blog-shell');
  Object.keys(THEMES).forEach((t) => shell.classList.remove('theme-' + t.toLowerCase().replace('_', '-')));
  shell.classList.add('theme-' + look.theme.toLowerCase().replace('_', '-'));
  applyBlogLook(look, shell);

  const home = `/blog/${encodeURIComponent(blog.slug)}` + (key ? '?key=' + encodeURIComponent(key) : '');
  $('blog-logo').textContent = blog.name;
  $('blog-logo').href = home;
  $('hero-eyebrow').textContent = THEMES[look.theme].name;
  $('hero-title').textContent = look.heroTitle || blog.name;
  $('hero-sub').textContent = look.heroSubtitle || blog.description || '';
  const hero = $('blog-hero');
  hero.classList.toggle('has-cover', !!look.coverUrl);
  // 머리 사진 주소는 서버가 만든 /uploads/{UUID}라 CSS 변수로 넣어도 안전하다.
  if (look.coverUrl && /^\/uploads\/[0-9a-f-]{36}$/.test(look.coverUrl)) {
    hero.style.setProperty('--hero-image', `url("${look.coverUrl}")`);
  } else {
    hero.style.removeProperty('--hero-image');
  }
  const profile = $('profile');
  profile.replaceChildren(look.coverUrl
    ? el('img', { src: look.coverUrl, alt: '' })
    : el('span', {}, [...blog.name][0] || 'C'));

  if (preview) {
    $('preview-text').textContent = `${THEMES[look.theme].name} 틀을 미리 보는 중이에요. 저장하기 전이라 다른 사람에게는 보이지 않아요.`;
    $('preview-close').href = home;
    $('preview-bar').hidden = false;
    $('preview-apply').onclick = async () => {
      try {
        await api.put(`/api/blogs/${encodeURIComponent(blog.slug)}/theme`, {
          theme: look.theme, accent: look.accent, font: look.font, heroTitle: look.heroTitle, heroSubtitle: look.heroSubtitle,
        });
        location.replace(home);
      } catch (err) {
        toast(err.message);
      }
    };
  }
}

/** 메뉴: 홈과 카테고리 몇 개. 누르면 그 카테고리의 글 목록으로 간다. */
async function renderMenu(blog) {
  const menu = $('blog-menu');
  const base = `/blog/${encodeURIComponent(blog.slug)}`;
  const keyQ = key ? { key } : {};
  const categories = await api.get(`/api/blogs/${encodeURIComponent(blog.slug)}/categories?` + new URLSearchParams(keyQ))
    .catch(() => []);
  const items = [el('a', { href: base + (key ? '?key=' + encodeURIComponent(key) : '') }, '홈')];
  categories.slice(0, 5).forEach((c) => {
    items.push(el('a', { href: `${base}?${new URLSearchParams({ ...keyQ, category: c.id })}#all-posts` }, c.name));
  });
  if (blog.myRole) items.push(el('a', { href: `${base}/write`, class: 'menu-write' }, '글쓰기'));
  menu.replaceChildren(...items);
}

/** 첫 화면(필터 없음)에서만 테마 칸을 그린다. 카테고리·태그·페이지를 고르면 전체 글 목록만 보인다. */
async function renderFront(blog) {
  const q = new URLSearchParams(location.search);
  const filtered = q.has('category') || q.has('tag') || (Number(q.get('page')) || 1) > 1;
  $('blog-shell').classList.toggle('filtered', filtered);
  const href = (p) => postUrl(blog.slug, p.id, key);
  const res = await api.get(`/api/blogs/${encodeURIComponent(blog.slug)}/posts?` + new URLSearchParams({ ...(key ? { key } : {}), size: 30 }))
    .catch(() => null);
  if (!res) return;
  renderSideRecent($('side-recent'), res.items, res.notices, href);
  if (filtered) return;
  renderShowcase($('showcase'), look.theme, res.items, blog, href);
  // Poster 틀은 "최근 올라온 글" 띠를 머리 사진 위에 둔다.
  const strip = $('showcase').querySelector('.po-strip');
  if (strip) {
    strip.classList.add('po-top');
    $('blog-hero').before(strip);
  }
}

function renderActions(blog) {
  const actions = $('actions');
  actions.replaceChildren();
  const base = `/api/blogs/${encodeURIComponent(blog.slug)}`;
  const keyQ = key ? '?key=' + encodeURIComponent(key) : '';

  // 구독 (SOC-01): 누르면 구독, 한 번 더 누르면 취소. 폐쇄 예정이어도 이미 한 구독은 풀 수 있다.
  if (blog.status !== 'CLOSING' || blog.subscribed) {
    const sub = el('button', { type: 'button', class: blog.subscribed ? 'subscribe on' : 'subscribe' },
      blog.subscribed ? '구독 중' : '+ 구독');
    sub.setAttribute('aria-pressed', String(blog.subscribed));
    sub.addEventListener('click', async () => {
      if (!me) {
        location.href = '/login.html?next=' + encodeURIComponent(location.pathname + location.search);
        return;
      }
      sub.disabled = true;
      try {
        const res = await api.post(`${base}/subscribe${keyQ}`);
        blog.subscribed = res.on;
        blog.subscribers = res.count;
        toast(res.on ? '구독했어요. 메인의 구독 피드에서 새 글을 볼 수 있어요.' : '구독을 취소했어요.');
        await load();
      } catch (err) {
        toast(err.message);
        sub.disabled = false;
      }
    });
    actions.append(sub);
  }
  if (!blog.myRole || blog.myRole !== 'OWNER') {
    const report = el('button', { type: 'button', class: 'link-button muted-link' }, '블로그 신고');
    report.addEventListener('click', () => openReport({ targetType: 'BLOG', slug: blog.slug, key }, !!me));
    // 참여·관리 버튼을 다 그린 뒤 맨 끝에 둔다.
    queueMicrotask(() => actions.append(report));
  }

  if (!me) {
    actions.append(el('a', { class: 'button', href: '/login.html?next=' + encodeURIComponent(location.pathname + location.search) },
      '로그인하고 참여하기'));
    return;
  }
  if (blog.myRole) {
    actions.append(el('span', { class: 'badge' }, '내 역할: ' + roleName(blog.myRole)));
    if (blog.myRole === 'OWNER' || blog.myRole === 'SUB_OWNER') {
      // 부블로그장은 받은 권한이 있을 때 관리 화면에서 그 부분만 보인다.
      actions.append(el('a', { class: 'button', href: `/blog/${encodeURIComponent(blog.slug)}/manage` }, '블로그 관리'));
    }
    if (blog.myRole !== 'OWNER') {
      actions.append(button('블로그 나가기', async () => {
        if (!confirm('이 블로그에서 나갈까요? 쓴 글의 작성자는 "탈퇴한 계정"으로 보입니다.')) {
          throw new Error('');
        }
        await api.post(base + '/leave');
        await load();
      }, 'danger-outline'));
    }
    return;
  }
  if (blog.status === 'CLOSING') return;
  if (blog.myRequest === 'PENDING') {
    actions.append(el('span', { class: 'badge' }, '참여 승인을 기다리는 중'));
    actions.append(button('신청 취소', async () => {
      await api.del(base + '/join');
      await load();
    }));
    return;
  }
  if (blog.myRequest === 'REJECTED') {
    actions.append(el('span', { class: 'muted' }, `참여 신청이 거절되었습니다. ${blog.canRequestAt}부터 다시 신청할 수 있습니다.`));
    return;
  }
  const label = blog.joinPolicy === 'FREE' ? '참여하기' : '참여 신청하기';
  actions.append(button(label, async () => {
    let res;
    try {
      res = await api.post(base + '/join', key ? { key } : {});
    } catch (err) {
      if (err.code === 'JOIN_BLACKLISTED' && err.details.canAppeal && !actions.querySelector('.appeal-form')) {
        actions.after(appealForm(base));
      }
      throw err;
    }
    await load();
    $('action-message').textContent = res.joined ? '멤버가 되었습니다.' : '참여를 신청했습니다. 블로그장이 승인하면 멤버가 됩니다.';
  }, 'primary inline'));
}

const loaded = await load();
if (loaded) {
  await Promise.all([renderMenu(loaded), renderFront(loaded), initPostList(loaded, key)]);
}
