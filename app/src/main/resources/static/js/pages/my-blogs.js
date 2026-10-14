import { api } from '../api.js';
import { blogCard, blogUrl } from '../blog-card.js';
import { el, requireLogin } from '../layout.js';

await requireLogin();

const $ = (id) => document.getElementById(id);
const res = await api.get('/api/me/blogs');

function fill(list, items, emptyText, emptyLink) {
  if (items.length === 0) {
    const li = el('li', { class: 'empty' });
    li.append(el('span', { class: 'muted' }, emptyText + ' '));
    if (emptyLink) li.append(el('a', { href: emptyLink.href }, emptyLink.text));
    list.replaceChildren(li);
    return;
  }
  list.replaceChildren(...items.map((b) => blogCard(b)));
}

// 내가 만든 블로그와 참여한 블로그를 나눠 보여준다 (BLG-06)
fill($('owned'), res.owned, '아직 만든 블로그가 없습니다.', { href: '/blog-new.html', text: '블로그 만들기' });
fill($('joined'), res.joined, '아직 참여한 블로그가 없습니다.', { href: '/', text: '블로그 둘러보기' });

if (res.pending.length) {
  $('pending').replaceChildren(...res.pending.map((p) => {
    const li = el('li', { class: 'list-row' });
    li.append(el('a', { href: blogUrl(p.slug) }, p.name), el('span', { class: 'muted' }, p.requestedAt + ' 신청'));
    return li;
  }));
  $('pending-section').hidden = false;
}
