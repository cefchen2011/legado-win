'use strict';

/* legado 阅读 · Windows 前端
 * 通过本地 JSON API 调用 JVM 里运行的 legado 引擎。 */

const $ = (id) => document.getElementById(id);

const state = {
  view: 'shelf',
  sources: [],
  shelf: [],
  current: null,      // 当前阅读的书
  chapters: [],
  chapterIndex: -1,
  config: null,       // 引擎侧阅读配置（ReadBookConfig）
  paraGap: parseFloat(localStorage.getItem('reader-para-gap') || '1.0'),
  explorePage: 1,
  chapterOpenedAt: 0,   // 进入当前章节的时间戳，用于统计阅读时长
  groups: [],           // 书架分组
  ungrouped: 0,
  shelfGroup: 'all',    // 当前书架筛选的分组（'all' / '0' / groupId）
  rssSources: [],       // 订阅源
  rssArticles: [],
  searchBooks: [],   // 当前搜索结果（原始，含重复源）
  searchMap: new Map(),     // 聚合后的搜索结果：书名+作者 → 多个源
  searchExpanded: '',       // 当前展开了换源的条目
  sourceKeyword: '',      // 书源列表筛选关键词
  sourceGroupFilter: 'all',
};

/* ---------------------------------------------------------------- 工具 */
async function api(path, options) {
  const res = await fetch('/api' + path, options);
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch (e) {
    throw new Error('服务端返回了非 JSON 内容: ' + text.slice(0, 200));
  }
}

function toast(msg, ms = 2600) {
  let el = $('toast');
  if (!el) {
    el = document.createElement('div');
    el.id = 'toast';
    document.body.appendChild(el);
  }
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.remove('show'), ms);
}

function esc(s) {
  return (s == null ? '' : String(s)).replace(/[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function coverHtml(url, fallback) {
  if (url && /^https?:/.test(url)) {
    return `<img src="${esc(url)}" alt="" referrerpolicy="no-referrer"
              onerror="this.replaceWith(document.createTextNode('${esc(fallback)}'))">`;
  }
  return esc(fallback);
}

function q(obj) {
  return Object.entries(obj)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&');
}

/* ---------------------------------------------------------------- 视图切换 */
function switchView(name) {
  state.view = name;
  document.querySelectorAll('.nav-btn').forEach((b) =>
    b.classList.toggle('active', b.dataset.view === name));
  document.querySelectorAll('.view').forEach((v) =>
    v.classList.toggle('active', v.id === 'view-' + name));
  if (name === 'shelf') loadShelf();
  if (name === 'source') loadSources();
  if (name === 'explore') loadExploreSources();
  if (name === 'replace') loadReplaceRules();
  if (name === 'history') loadHistory();
  if (name === 'rss') loadRssSources();
  if (name === 'sync') loadWebDav();
  if (name === 'theme') { loadTheme(); loadThemeList(); }
}

document.querySelectorAll('.nav-btn').forEach((b) =>
  b.addEventListener('click', () => switchView(b.dataset.view)));

/* ---------------------------------------------------------------- 书源 */
async function loadSources() {
  try {
    const data = await api('/sources');
    state.sources = data.sources || [];
    renderSources();
    renderSourceSelect();
    $('stat').textContent = `书源 ${data.total || 0} · 书架 ${state.shelf.length}`;
    // 书源数会影响引导文案（有没有书源，"该干什么"完全不同）
    if (!state.shelf.length) renderShelfOnboarding();
    const enabled = state.sources.filter((s) => s.enabled).length;
    const st = $('search-status');
    if (st && !st.textContent) {
      st.textContent = enabled
        ? `已启用 ${enabled} 个书源，输入书名开始搜索`
        : '还没有书源 —— 先到「书源」页导入，或点「书源仓库」搜索导入';
    }
  } catch (e) {
    toast('加载书源失败: ' + e.message);
  }
}

function renderSources() {
  const box = $('source-list');
  if (!state.sources.length) {
    $('source-filters').classList.add('hide');
    box.innerHTML = '<div class="empty">还没有书源。粘贴书源 JSON 后点「导入书源」。</div>';
    return;
  }
  $('source-filters').classList.remove('hide');

  // ---- 分组 + 关键词筛选 ----
  const groups = [];
  state.sources.forEach((s) => {
    const g = s.group || '默认';
    if (!groups.includes(g)) groups.push(g);
  });
  groups.sort();

  const kw = (state.sourceKeyword || '').trim().toLowerCase();
  const gf = state.sourceGroupFilter || 'all';
  const list = state.sources.filter((s) => {
    if (gf !== 'all' && (s.group || '默认') !== gf) return false;
    if (!kw) return true;
    return (s.name || '').toLowerCase().includes(kw) ||
      (s.url || '').toLowerCase().includes(kw);
  });

  $('source-groups').innerHTML =
    [`<button class="chip ${gf === 'all' ? 'active' : ''}" data-sgroup="all">全部 (${state.sources.length})</button>`]
      .concat(groups.map((g) => {
        const n = state.sources.filter((s) => (s.group || '默认') === g).length;
        return `<button class="chip ${gf === g ? 'active' : ''}" data-sgroup="${esc(g)}">${esc(g)} (${n})</button>`;
      })).join('');
  $('source-groups').querySelectorAll('[data-sgroup]').forEach((b) =>
    b.addEventListener('click', () => {
      state.sourceGroupFilter = b.dataset.sgroup;
      renderSources();
    }));

  const enabled = list.filter((s) => s.enabled).length;
  $('source-filter-status').textContent = list.length === state.sources.length
    ? `${list.length} 个 · 启用 ${enabled}`
    : `筛出 ${list.length} / ${state.sources.length} 个 · 启用 ${enabled}`;

  if (!list.length) {
    box.innerHTML = '<div class="empty">没有匹配的书源</div>';
    return;
  }

  // 整包导入后可能有上千条，全量塞进 DOM 会很卡；
  // 只渲染前若干条，其余靠关键词/分组继续筛。
  const RENDER_CAP = 300;
  const shown = list.slice(0, RENDER_CAP);
  if (list.length > RENDER_CAP) {
    $('source-filter-status').textContent += `（只显示前 ${RENDER_CAP} 个，请用筛选缩小范围）`;
  }

  box.innerHTML = shown.map((s) => `
    <div class="item">
      <div class="thumb">源</div>
      <div class="meta">
        <div class="title">${esc(s.name)}${s.enabled ? '' : ' <span class="si-badge si-warn">已停用</span>'}</div>
        <div class="sub">${esc(s.url)}</div>
        <div class="intro">${s.hasSearch ? '支持搜索' : '不支持搜索'} · ${s.hasExplore ? '支持发现' : '无发现'} · 分组 ${esc(s.group || '默认')}</div>
      </div>
      <div class="ops">
        <button class="ghost" data-toggle="${esc(s.url)}" data-enabled="${s.enabled}">
          ${s.enabled ? '停用' : '启用'}
        </button>
        <button class="ghost" data-debug="${esc(s.url)}" data-name="${esc(s.name)}">调试</button>
        <button class="ghost" data-del="${esc(s.url)}">删除</button>
      </div>
    </div>`).join('');

  // 批量操作只作用于「当前筛出来的这批」——导入整包后按分组清理最实用
  const urls = list.map((s) => s.url);
  $('source-batch-status').textContent = `批量操作对象：当前筛出的 ${urls.length} 个`;
  $('source-enable-all').onclick = () => batchSources('enable', urls);
  $('source-disable-all').onclick = () => batchSources('disable', urls);
  $('source-delete-all').onclick = () => {
    if (!confirm(`删除当前筛出的 ${urls.length} 个书源？此操作不可撤销。`)) return;
    batchSources('delete', urls);
  };

  box.querySelectorAll('[data-debug]').forEach((b) =>
    b.addEventListener('click', () => {
      $('debug-view').classList.add('open');
      startDebug(b.dataset.debug, b.dataset.name, '测试');
    }));

  box.querySelectorAll('[data-toggle]').forEach((b) =>
    b.addEventListener('click', async () => {
      await api('/source/toggle?' + q({ url: b.dataset.toggle, enabled: b.dataset.enabled !== 'true' }));
      loadSources();
    }));

  box.querySelectorAll('[data-del]').forEach((b) =>
    b.addEventListener('click', async () => {
      await api('/sources?' + q({ url: b.dataset.del }), { method: 'DELETE' });
      toast('已删除');
      loadSources();
    }));
}

/**
 * 搜索范围选择器。
 *
 * 整包导入后可能有上千个源，平铺成 1000 个 <option> 是没法用的。
 * 因此按书源分组用 <optgroup> 归类，并给每个分组一个"整组搜索"的入口——
 * 这样既能整组搜（比如只搜「听书源」），也能挑单个源。
 */
function renderSourceSelect() {
  const sel = $('search-source');
  const cur = sel.value;
  const enabled = state.sources.filter((s) => s.enabled && s.hasSearch);

  const byGroup = new Map();
  enabled.forEach((s) => {
    const g = s.group || '默认';
    if (!byGroup.has(g)) byGroup.set(g, []);
    byGroup.get(g).push(s);
  });
  const groupNames = Array.from(byGroup.keys()).sort();

  let html = `<option value="">全部启用书源（${enabled.length}）</option>`;
  // 分组整体作为可选项：值用 group: 前缀区分于单个书源地址
  html += groupNames
    .filter((g) => byGroup.get(g).length > 1)
    .map((g) => `<option value="group:${esc(g)}">【整组】${esc(g)}（${byGroup.get(g).length}）</option>`)
    .join('');
  // 单个书源按分组归类
  html += groupNames.map((g) => {
    const opts = byGroup.get(g)
      .map((s) => `<option value="${esc(s.url)}">${esc(s.name)}</option>`).join('');
    return `<optgroup label="${esc(g)}（${byGroup.get(g).length}）">${opts}</optgroup>`;
  }).join('');

  sel.innerHTML = html;
  sel.value = cur;
}

$('source-import').addEventListener('click', async () => {
  const text = $('source-input').value.trim();
  if (!text) { toast('请先粘贴书源 JSON'); return; }
  $('source-status').textContent = '导入中…';
  try {
    const data = await api('/sources', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: text,
    });
    $('source-status').textContent = '';
    toast(`成功导入 ${data.imported} 个书源`);
    $('source-input').value = '';
    loadSources();
  } catch (e) {
    $('source-status').textContent = '';
    toast('导入失败: ' + e.message);
  }
});

$('source-refresh').addEventListener('click', loadSources);

// 导出全部书源（浏览器直接下载 JSON，可用于备份或迁移到其它设备）
$('source-export').addEventListener('click', () => {
  if (!state.sources.length) { toast('还没有书源可导出'); return; }
  const a = document.createElement('a');
  a.href = '/api/sources/export';
  a.download = 'legado-book-sources.json';
  document.body.appendChild(a);
  a.click();
  a.remove();
  toast(`正在导出 ${state.sources.length} 个书源`);
});

/* ---------------------------------------------------------------- 发现 */
async function loadExploreSources() {
  const sel = $('explore-source');
  const cur = sel.value;
  try {
    const data = await api('/sources');
    const list = (data.sources || []).filter((s) => s.enabled && s.hasExplore);
    sel.innerHTML = '<option value="">选择书源</option>' +
      list.map((s) => `<option value="${esc(s.url)}">${esc(s.name)}</option>`).join('');
    sel.value = cur;
    if (!list.length) {
      $('explore-status').textContent = '没有启用且带发现规则的书源';
    }
  } catch (e) {
    toast('加载书源失败: ' + e.message);
  }
}

async function loadExploreKinds(sourceUrl) {
  $('explore-list').innerHTML = '';
  $('explore-kinds').innerHTML = '';
  if (!sourceUrl) { $('explore-status').textContent = ''; return; }
  $('explore-status').textContent = '加载分类…';
  try {
    const data = await api('/explore?' + q({ sourceUrl }));
    if (!data.ok) { $('explore-status').textContent = data.error || '加载失败'; return; }
    const kinds = data.kinds || [];
    $('explore-status').textContent = kinds.filter((k) => !k.group).length + ' 个分类';
    $('explore-kinds').innerHTML = kinds.map((k, i) =>
      k.group
        ? `<span class="chip group">${esc(k.title)}</span>`
        : `<button class="chip" data-kind="${i}">${esc(k.title)}</button>`
    ).join('');

    $('explore-kinds').querySelectorAll('[data-kind]').forEach((btn) =>
      btn.addEventListener('click', () => {
        $('explore-kinds').querySelectorAll('.chip').forEach((c) => c.classList.remove('active'));
        btn.classList.add('active');
        loadExploreBooks(sourceUrl, kinds[+btn.dataset.kind].url, 1);
      }));

    // 默认打开第一个可点击分类
    const first = kinds.findIndex((k) => !k.group);
    if (first >= 0) {
      const btn = $('explore-kinds').querySelector(`[data-kind="${first}"]`);
      if (btn) btn.click();
    }
  } catch (e) {
    $('explore-status').textContent = '';
    toast('加载发现分类失败: ' + e.message);
  }
}

async function loadExploreBooks(sourceUrl, url, page) {
  state.explorePage = page;
  $('explore-list').innerHTML = '<div class="empty">加载中…</div>';
  try {
    const data = await api('/explore/books?' + q({ sourceUrl, url, page }));
    if (!data.ok) {
      $('explore-list').innerHTML =
        `<div class="empty">加载失败：${esc(data.error || '未知错误')}</div>`;
      return;
    }
    renderBookList($('explore-list'), data.books || [], `本页 ${(data.books || []).length} 本 · 第 ${page} 页`);
  } catch (e) {
    $('explore-list').innerHTML = `<div class="empty">出错了：${esc(e.message)}</div>`;
  }
}

/** 搜索与发现共用的书籍列表渲染 */
function renderBookList(box, books, note) {
  if (!books.length) {
    box.innerHTML = '<div class="empty">没有结果</div>';
    return;
  }
  box.innerHTML = (note ? `<div class="muted" style="margin-bottom:4px">${esc(note)}</div>` : '') +
    books.map((b, i) => `
    <div class="item">
      <div class="thumb">${coverHtml(b.coverUrl, '书')}</div>
      <div class="meta">
        <div class="title">${esc(b.name)}</div>
        <div class="sub">${esc(b.author || '佚名')} · ${esc(b.originName || '')} ${b.kind ? '· ' + esc(b.kind) : ''}</div>
        <div class="intro">${esc(b.intro || b.latestChapterTitle || '暂无简介')}</div>
      </div>
      <div class="ops">
        <button class="primary" data-read="${i}">阅读</button>
        <button class="ghost" data-add="${i}">加入书架</button>
      </div>
    </div>`).join('');

  box.querySelectorAll('[data-add]').forEach((btn) =>
    btn.addEventListener('click', () => addToShelf(books[+btn.dataset.add])));

  box.querySelectorAll('[data-read]').forEach((btn) =>
    btn.addEventListener('click', () => openBook(books[+btn.dataset.read])));
}

async function addToShelf(b) {
  try {
    await api('/shelf', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: b.name, author: b.author, bookUrl: b.bookUrl,
        origin: b.origin, originName: b.originName, coverUrl: b.coverUrl,
        intro: b.intro, tocUrl: b.bookUrl,
      }),
    });
    toast('已加入书架');
    loadShelf();
  } catch (e) {
    toast('加入书架失败: ' + e.message);
  }
}

$('explore-source').addEventListener('change', (e) => loadExploreKinds(e.target.value));

/* ---------------------------------------------------------------- 搜索 */
/* ---------------------------------------------------------------- 搜索
 *
 * 走 SSE 流式接口，与手机端一致：**哪个书源先返回就先显示哪个源的结果**。
 * 因为真实书源里总有几个被墙/卡住的源，等全部完成会让用户干等甚至超时。
 */
let searchSource = null;
let searchRenderTimer = null;

function doSearch() {
  const key = $('search-key').value.trim();
  if (!key) { toast('请输入关键词'); return; }
  const source = $('search-source').value;

  // 关掉上一次未结束的搜索
  if (searchSource) { searchSource.close(); searchSource = null; }

  const box = $('search-list');
  box.innerHTML = '';
  state.searchBooks = [];
  state.searchMap = new Map();
  state.searchExpanded = '';
  $('search-status').textContent = '正在搜索…（结果会随书源返回陆续出现）';

  // 分组整体搜索：值形如 group:小说源
  const isGroup = source.startsWith('group:');
  const params = isGroup
    ? { key: key, group: source.slice('group:'.length) }
    : { key: key, source: source };
  const url = '/api/search/stream?' + q(params);
  searchSource = new EventSource(url);
  const stat = { replied: 0, total: 0, books: 0, failed: 0 };

  searchSource.addEventListener('start', (e) => {
    const d = JSON.parse(e.data);
    stat.total = d.sourceCount;
    $('search-status').textContent = `正在搜索 ${d.sourceCount} 个书源…`;
  });

  searchSource.addEventListener('source', (e) => {
    const d = JSON.parse(e.data);
    stat.replied++;
    stat.books += d.count || 0;
    if (!d.ok) stat.failed++;

    // 状态行：谁先回来就报谁
    $('search-status').textContent =
      `已返回 ${stat.replied}/${stat.total} 个书源 · ${stat.books} 个结果`
      + (stat.failed ? ` · ${stat.failed} 个失败` : '');

    if (d.ok && d.count > 0) {
      mergeSearchBooks(d.books, d.sourceName);
      state.searchBooks.push(...d.books);
      scheduleSearchRender();
    } else if (!d.ok) {
      // 失败源也列出来，方便判断是网络问题还是书源问题
      const row = document.createElement('div');
      row.className = 'muted';
      row.style.padding = '6px 2px';
      row.textContent = `✗ ${d.sourceName}：${(d.error || '失败').slice(0, 60)}`;
      box.appendChild(row);
    } else {
      const row = document.createElement('div');
      row.className = 'muted';
      row.style.padding = '6px 2px';
      row.textContent = `— ${d.sourceName}：无结果`;
      box.appendChild(row);
    }
  });

  searchSource.addEventListener('done', (e) => {
    const d = JSON.parse(e.data);
    // 书源太多时会只搜前一批，必须如实说明——
    // 否则用户会以为"就这几个源有这本书"，而实际是没搜到那些源
    const skip = d.skipped
      ? `　（另有 ${d.skipped} 个书源未参与，可用筛选缩小范围后重搜）`
      : '';
    $('search-status').textContent =
      `搜索完成：${d.repliedSources}/${d.sourceCount} 个书源有响应，共 ${d.total} 个结果` +
      `（${(d.elapsedMs / 1000).toFixed(1)}s）${skip}`;
    searchSource.close();
    searchSource = null;
  });

  searchSource.addEventListener('error', () => {
    if (searchSource) { searchSource.close(); searchSource = null; }
    $('search-status').textContent = '搜索连接中断';
  });
}

/** 把一批新结果并入聚合表（同一本书的多个源合并） */
function mergeSearchBooks(books, sourceName) {
  books.forEach((b) => {
    const key = `${(b.name || '').trim()}|${(b.author || '').trim()}`;
    let entry = state.searchMap.get(key);
    if (!entry) {
      entry = { key: key, name: b.name, author: b.author, sources: [], first: b };
      state.searchMap.set(key, entry);
    }
    entry.sources.push(Object.assign({}, b, { originName: b.originName || sourceName }));
  });
}

/** 渲染聚合后的搜索结果。同一本书只出一行，标出有几个源可用。 */
function renderSearchBooks() {
  const box = $('search-list');
  const all = Array.from(state.searchMap.values())
    // 源多的排前面：可选的源越多，越不容易遇到失效
    .sort((a, b) => b.sources.length - a.sources.length || a.name.localeCompare(b.name));

  const CAP = 200;
  const shown = all.slice(0, CAP);
  const expanded = state.searchExpanded;

  box.innerHTML = shown.map((e) => {
    const sources = e.sources;
    const multi = sources.length > 1;
    const isOpen = expanded === e.key;
    return `
    <div class="item search-item" data-key="${esc(e.key)}">
      <div class="thumb">${coverHtml(e.first.coverUrl, '书')}</div>
      <div class="meta">
        <div class="title">${esc(e.name)}
          ${multi ? `<span class="si-badge">${sources.length} 个源</span>` : ''}
        </div>
        <div class="sub">${esc(e.author || '佚名')} · ${esc(sources[0].originName || '')}
          ${e.first.kind ? '· ' + esc(e.first.kind) : ''}</div>
        <div class="intro">${esc(e.first.intro || e.first.latestChapterTitle || '暂无简介')}</div>
        ${isOpen ? `<div class="src-list">${sources.map((s, i) => `
          <div class="src-row">
            <span class="src-name">${esc(s.originName || '未知源')}</span>
            <span class="muted">${esc(s.latestChapterTitle || '')}</span>
            <button class="ghost" data-read-src="${i}" data-key="${esc(e.key)}">阅读</button>
          </div>`).join('')}</div>` : ''}
      </div>
      <div class="ops">
        <button class="primary" data-read="1">阅读</button>
        ${multi ? `<button class="ghost" data-expand="1">${isOpen ? '收起' : '换源'}</button>` : ''}
        <button class="ghost" data-add="1">加入书架</button>
      </div>
    </div>`;
  }).join('') + (all.length > CAP
    ? `<div class="muted" style="padding:12px 4px">另有 ${all.length - CAP} 本未显示，请换关键词或用分组缩小范围</div>`
    : '');

  box.querySelectorAll('.search-item').forEach((el) => {
    const e = state.searchMap.get(el.dataset.key);
    if (!e) return;
    el.querySelector('[data-read]')?.addEventListener('click', () => openBook(e.first));
    el.querySelector('[data-add]')?.addEventListener('click', () => addToShelf(e.first));
    el.querySelector('[data-expand]')?.addEventListener('click', () => {
      state.searchExpanded = state.searchExpanded === e.key ? '' : e.key;
      renderSearchBooks();
    });
    el.querySelectorAll('[data-read-src]').forEach((b) =>
      b.addEventListener('click', () => openBook(e.sources[+b.dataset.readSrc])));
  });
}

/** 节流重排：结果可能几百批，每批都全量重排会卡 */
function scheduleSearchRender() {
  if (searchRenderTimer) return;
  searchRenderTimer = setTimeout(() => {
    searchRenderTimer = null;
    renderSearchBooks();
  }, 350);
}

$('search-btn').addEventListener('click', doSearch);
$('search-key').addEventListener('keydown', (e) => { if (e.key === 'Enter') doSearch(); });

/* ---------------------------------------------------------------- 书架分组 */
async function loadGroups() {
  try {
    const data = await api('/groups');
    state.groups = data.groups || [];
    state.ungrouped = data.ungrouped || 0;
    renderGroupChips();
  } catch (e) {
    /* 分组加载失败不影响书架显示 */
  }
}

function renderGroupChips() {
  const box = $('shelf-groups');
  const gs = state.groups || [];
  const chips = [
    `<button class="chip ${state.shelfGroup === 'all' ? 'active' : ''}" data-g="all">全部 (${state.shelf.length})</button>`,
    `<button class="chip ${state.shelfGroup === '0' ? 'active' : ''}" data-g="0">未分组 (${state.ungrouped || 0})</button>`,
  ];
  gs.forEach((g) => {
    chips.push(`<button class="chip ${state.shelfGroup === String(g.groupId) ? 'active' : ''}"
      data-g="${g.groupId}">${esc(g.groupName)} (${g.bookCount})</button>`);
  });
  box.innerHTML = chips.join('');

  box.querySelectorAll('[data-g]').forEach((b) =>
    b.addEventListener('click', () => {
      state.shelfGroup = b.dataset.g;
      renderGroupChips();
      renderShelf();
    }));
}

$('group-add').addEventListener('click', async () => {
  const name = prompt('新分组名称');
  if (!name || !name.trim()) return;
  try {
    const r = await api('/groups', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ groupName: name.trim() }),
    });
    if (!r.ok) { toast(r.error || '创建失败'); return; }
    toast('分组已创建');
    await loadGroups();
  } catch (e) {
    toast('创建失败: ' + e.message);
  }
});

async function moveBookToGroup(bookUrl, groupId) {
  try {
    await api('/book/group?' + q({ bookUrl: bookUrl, group: groupId }), { method: 'POST' });
    toast('已移动分组');
    await loadGroups();
    await loadShelf();
  } catch (e) {
    toast('移动失败: ' + e.message);
  }
}

/* ---------------------------------------------------------------- 书架 */
async function loadShelf() {
  try {
    const data = await api('/shelf');
    state.shelf = data.books || [];
    await loadGroups();
    renderShelf();
    $('stat').textContent = `书源 ${state.sources.length} · 书架 ${state.shelf.length}`;
  } catch (e) {
    toast('加载书架失败: ' + e.message);
  }
}

/** 书架空状态。
 *
 * 分两种情形，因为"该干什么"完全不同：
 *   - 一个书源都没有：用户多半是第一次用，必须先有书源才能搜书，
 *     所以直接把他带到书源仓库，而不是让他去搜索页白搜一场。
 *   - 有书源但书架空：正常引导去搜书。
 */
function renderShelfOnboarding() {
  const box = $('onboard-body');
  if (!box) return;
  const srcCount = (state.sources || []).filter((s) => s.enabled).length;

  if (!srcCount) {
    box.innerHTML = `
      <h3>先导入书源，才能搜书</h3>
      <div class="ob-sub">这个应用本身不含书内容——书来自「书源」（描述某个网站怎么搜书、取目录、取正文的规则）。</div>
      <ol>
        <li>点下面的「打开书源仓库」，从 Yiove 书源仓库里搜索或挑一个合集</li>
        <li>点「导入」，书源就进到本地了</li>
        <li>回到「搜索」页搜书名，找到后点「阅读」</li>
      </ol>
      <div class="ob-actions">
        <button class="primary" id="ob-store">打开书源仓库</button>
        <button class="ghost" id="ob-paste">手动粘贴书源 JSON</button>
        <button class="ghost" id="ob-local">导入本地 TXT / EPUB</button>
      </div>`;
    $('ob-store').addEventListener('click', () => {
      switchView('source');
      $('store-view').classList.add('open');
      if (!$('store-list').children.length) storeSearch();
    });
    $('ob-paste').addEventListener('click', () => switchView('source'));
    $('ob-local').addEventListener('click', () => $('local-import').click());
    return;
  }

  box.innerHTML = `
    <h3>书架还是空的</h3>
    <div class="ob-sub">已经导入 ${srcCount} 个书源，可以直接搜书了。</div>
    <ol>
      <li>到「搜索」页输入书名，结果会随书源陆续出现</li>
      <li>点「阅读」开始看，点「加入书架」收藏</li>
      <li>也可以「发现」页按分类浏览，或导入本地 TXT / EPUB</li>
    </ol>
    <div class="ob-actions">
      <button class="primary" id="ob-search">去搜索</button>
      <button class="ghost" id="ob-explore">去发现</button>
      <button class="ghost" id="ob-local2">导入本地书</button>
    </div>`;
  $('ob-search').addEventListener('click', () => switchView('search'));
  $('ob-explore').addEventListener('click', () => switchView('explore'));
  $('ob-local2').addEventListener('click', () => $('local-import').click());
}

function renderShelf() {
  const box = $('shelf-list');
  const all = state.shelf;
  const filter = state.shelfGroup;
  const books = filter === 'all'
    ? all
    : all.filter((b) => String(b.group || 0) === filter);

  $('shelf-count').textContent = all.length
    ? (filter === 'all' ? `共 ${all.length} 本` : `${books.length} / ${all.length} 本`)
    : '';
  const isEmpty = books.length === 0;
  $('shelf-empty').classList.toggle('hide', !isEmpty);
  if (isEmpty) renderShelfOnboarding();

  box.innerHTML = books.map((b) => {
    const i = all.indexOf(b);
    const read = b.durChapterIndex > 0
      ? `读到 ${esc(b.durChapterTitle || ('第 ' + (b.durChapterIndex + 1) + ' 章'))}`
      : '未读';
    const options = ['<option value="0">未分组</option>']
      .concat((state.groups || []).map((g) =>
        `<option value="${g.groupId}" ${b.group === g.groupId ? 'selected' : ''}>${esc(g.groupName)}</option>`))
      .join('');
    return `
    <div class="card" data-open="${i}">
      <button class="del" data-del="${i}" title="移出书架">×</button>
      <button class="exp" data-exp="${i}" title="导出为 TXT">导出</button>
      <div class="cover">${coverHtml(b.coverUrl, '📖')}</div>
      <div class="info">
        <div class="name">${esc(b.name)}</div>
        <div class="author">${esc(b.author || '佚名')}</div>
        <div class="author" style="margin-top:5px;color:var(--accent)">${read}</div>
      </div>
      <select class="move" data-move="${i}">${options}</select>
    </div>`;
  }).join('');

  box.querySelectorAll('[data-open]').forEach((el) =>
    el.addEventListener('click', (ev) => {
      if (ev.target.closest('.move') || ev.target.closest('.del')) return;
      openBook(all[+el.dataset.open]);
    }));

  box.querySelectorAll('[data-move]').forEach((sel) =>
    sel.addEventListener('change', (ev) => {
      ev.stopPropagation();
      const b = all[+sel.dataset.move];
      moveBookToGroup(b.bookUrl, sel.value);
    }));

  box.querySelectorAll('[data-del]').forEach((btn) =>
    btn.addEventListener('click', async (ev) => {
      ev.stopPropagation();
      const b = all[+btn.dataset.del];
      if (!confirm(`把《${b.name}》移出书架？`)) return;
      await api('/shelf/delete?' + q({ url: b.bookUrl }), { method: 'POST' });
      toast('已移出书架');
      loadShelf();
    }));

  box.querySelectorAll('[data-exp]').forEach((btn) =>
    btn.addEventListener('click', async (ev) => {
      ev.stopPropagation();
      const b = all[+btn.dataset.exp];
      if (!b.totalChapterNum) {
        toast('还没有目录，请先打开这本书');
        return;
      }
      toast(`正在导出《${b.name}》（${b.totalChapterNum} 章），请稍候…`, 6000);
      // 用隐藏 iframe 触发下载，避免整页跳转
      const iframe = document.createElement('iframe');
      iframe.style.display = 'none';
      iframe.src = '/api/export?' + q({ bookUrl: b.bookUrl });
      document.body.appendChild(iframe);
      setTimeout(() => iframe.remove(), 60000);
    }));
}

$('shelf-refresh').addEventListener('click', loadShelf);

// 导入本地 TXT：浏览器拿不到任意本地路径，因此让用户粘贴完整路径
$('local-import').addEventListener('click', async () => {
  const path = prompt('本地 TXT 文件的完整路径\n例如：D:\\books\\我的小说.txt');
  if (!path || !path.trim()) return;
  toast('正在解析分章…');
  try {
    const r = await api('/local/import?path=' + encodeURIComponent(path.trim()), { method: 'POST' });
    if (!r.ok) { toast(r.error || '导入失败'); return; }
    toast(`已导入《${r.book.name}》，共 ${r.total} 章`);
    await loadShelf();
  } catch (e) {
    toast('导入失败: ' + e.message);
  }
});

/* ---------------------------------------------------------------- 阅读 */
async function openBook(book) {
  state.current = book;
  state.chapters = [];
  // 本地书（origin 为 legado 的本地标记）走本地读取接口，不走书源规则
  state.isLocal = !book.origin || book.origin === 'loc_book' || String(book.origin).startsWith('webDav::');
  // 漫画书走图片阅读器：由书源的 bookSourceType 决定
  if (!state.isLocal) {
    try {
      // 一次问清书的类型，据此选阅读器：漫画 / 音频 / 视频 / 文字
      const k = await api('/book/kind?bookUrl=' + encodeURIComponent(book.bookUrl));
      if (k.isManga) { openManga(book); return; }
      if (k.isAudio) { openMedia(book, 'audio'); return; }
      if (k.isVideo) { openMedia(book, 'video'); return; }
    } catch (e) { /* 检查失败就按文字书处理 */ }
  }
  $('reader').classList.add('open');
  $('reader-book').textContent = `${book.name} · ${book.author || '佚名'}`;
  $('reader-chapter').textContent = '正在获取目录…';
  $('reader-content').textContent = '';
  $('reader-pos').textContent = '';
  $('toc-list').innerHTML = '';

  try {
    const data = state.isLocal
      ? await api('/local/toc?' + q({ bookUrl: book.bookUrl }))
      : await api('/toc?' + q({
          sourceUrl: book.origin,
          bookUrl: book.bookUrl,
          name: book.name,
          author: book.author,
        }));
    if (!data.ok) {
      $('reader-chapter').textContent = '目录获取失败';
      $('reader-content').textContent = data.error || '未知错误';
      return;
    }
    state.chapters = data.chapters || [];
    renderToc();
    if (!state.chapters.length) {
      $('reader-chapter').textContent = '目录为空';
      return;
    }
    // 接着上次读到的地方继续
    const resume = Math.min(Math.max(book.durChapterIndex || 0, 0), state.chapters.length - 1);
    await loadChapter(resume);
  } catch (e) {
    $('reader-chapter').textContent = '出错了';
    $('reader-content').textContent = e.message;
  }
}

function renderToc() {
  $('toc-list').innerHTML = state.chapters.map((c) => `
    <div class="toc-item ${c.index === state.chapterIndex ? 'active' : ''}" data-ch="${c.index}">
      ${esc(c.title)}
    </div>`).join('');
  $('toc-list').querySelectorAll('[data-ch]').forEach((el) =>
    el.addEventListener('click', () => {
      loadChapter(+el.dataset.ch);
      $('toc-panel').classList.remove('open');
    }));
}

async function loadChapter(index, keepSpeaking) {
  const book = state.current;
  if (!book) return;
  // 换章时必须先停掉当前朗读，否则声音会和正文对不上；
  // keepSpeaking 表示"读完后自动续读下一章"，由 onChapterSpoken 传入。
  const wasSpeaking = tts.active;
  if (wasSpeaking) ttsStop();
  $('reader-chapter').textContent = '正在加载…';
  $('reader-content').textContent = '';
  try {
    const data = state.isLocal
      ? await api('/local/content?' + q({ bookUrl: book.bookUrl, index: index }))
      : await api('/content?' + q({
          sourceUrl: book.origin,
          bookUrl: book.bookUrl,
          name: book.name,
          author: book.author,
          index: index,
        }));
    if (!data.ok) {
      $('reader-chapter').textContent = '正文获取失败';
      $('reader-content').textContent = data.error || '未知错误';
      return;
    }
    state.chapterIndex = data.index;
    $('reader-chapter').textContent = data.title || ('第 ' + (index + 1) + ' 章');
    $('reader-content').innerHTML = toParagraphs(data.content);
    $('reader-pos').textContent = `${data.index + 1} / ${data.total}`;
    $('reader-prev').disabled = data.prevIndex < 0;
    $('reader-next').disabled = data.nextIndex < 0;
    $('reader-body').scrollTop = 0;
    renderToc();
    saveProgress(data.index, data.title, data.total);
    if (keepSpeaking) ttsStartSoon();
  } catch (e) {
    $('reader-chapter').textContent = '出错了';
    $('reader-content').textContent = e.message;
  }
}

/* 把纯文本正文切成段落；已含 HTML 标签的内容原样保留 */
function toParagraphs(content) {
  if (!content) return '';
  if (/<[a-z][\s\S]*>/i.test(content)) return content;
  return content.split(/\n+/).map((line) => line.trim()).filter(Boolean)
    .map((line) => `<p>${esc(line)}</p>`).join('');
}

$('reader-back').addEventListener('click', () => $('reader').classList.remove('open'));
$('reader-prev').addEventListener('click', () => { if (state.chapterIndex > 0) loadChapter(state.chapterIndex - 1); });
$('reader-next').addEventListener('click', () => { if (state.chapterIndex < state.chapters.length - 1) loadChapter(state.chapterIndex + 1); });
$('reader-toc').addEventListener('click', () => $('toc-panel').classList.toggle('open'));
$('toc-close').addEventListener('click', () => $('toc-panel').classList.remove('open'));

/* ---------------------------------------------------------------- 朗读（TTS）
 *
 * 用浏览器内置的 Web Speech API，Edge 自带中文语音，无需后端与额外依赖。
 * 逐段朗读而不是整章丢进去，原因有两个：
 *   1. Chromium 对超长 utterance 有截断问题；
 *   2. 可以顺便高亮当前朗读的段落，体验接近 legado 的"边听边看"。
 */
const tts = {
  active: false,
  paused: false,
  paragraphs: [],
  cursor: 0,
  rate: parseFloat(localStorage.getItem('tts-rate') || '1.0'),
  voiceName: localStorage.getItem('tts-voice') || '',
  autoNext: localStorage.getItem('tts-auto-next') !== '0',
};

function ttsSupported() {
  return typeof window.speechSynthesis !== 'undefined' && typeof SpeechSynthesisUtterance !== 'undefined';
}

function loadVoices() {
  if (!ttsSupported()) return [];
  const all = speechSynthesis.getVoices() || [];
  // 中文语音优先
  const zh = all.filter((v) => /zh|cmn|Chinese/i.test(v.lang + ' ' + v.name));
  return zh.length ? zh : all;
}

function fillVoiceSelect() {
  const sel = $('tts-voice');
  const voices = loadVoices();
  sel.innerHTML = voices.map((v) =>
    `<option value="${esc(v.name)}">${esc(v.name)} · ${esc(v.lang)}</option>`).join('');
  if (!voices.length) return;
  const found = voices.some((v) => v.name === tts.voiceName);
  if (!found) tts.voiceName = voices[0].name;
  sel.value = tts.voiceName;
}

function currentVoice() {
  const voices = loadVoices();
  return voices.find((v) => v.name === tts.voiceName) || voices[0] || null;
}

function ttsStart() {
  if (!ttsSupported()) { toast('当前浏览器不支持系统朗读'); return; }
  // 把正文按段落拆出来；跳过空段
  const ps = Array.from($('reader-content').querySelectorAll('p'));
  const texts = ps.length
    ? ps
    : ($('reader-content').textContent || '').split(/\n+/).map((t) => ({ textContent: t }));

  tts.paragraphs = texts
    .map((el, i) => ({ el, text: (el.textContent || '').trim(), i }))
    .filter((x) => x.text.length > 0);
  if (!tts.paragraphs.length) { toast('这一章没有可朗读的内容'); return; }

  tts.active = true;
  tts.paused = false;
  tts.cursor = 0;
  $('tts-bar').classList.add('open');
  $('reader-speak').textContent = '停止';
  speakCurrent();
}

function speakCurrent() {
  if (!tts.active) return;
  speechSynthesis.cancel();

  const item = tts.paragraphs[tts.cursor];
  if (!item) { onChapterSpoken(); return; }

  // 高亮当前段
  document.querySelectorAll('.content p.speaking').forEach((p) => p.classList.remove('speaking'));
  if (item.el && item.el.classList) {
    item.el.classList.add('speaking');
    item.el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  const u = new SpeechSynthesisUtterance(item.text);
  const v = currentVoice();
  if (v) { u.voice = v; u.lang = v.lang; }
  u.rate = tts.rate;
  u.onend = () => {
    if (!tts.active || tts.paused) return;
    tts.cursor++;
    speakCurrent();
  };
  u.onerror = (e) => {
    // interrupted/canceled 是我们自己 cancel 造成的，忽略
    if (e.error === 'interrupted' || e.error === 'canceled') return;
    console.warn('TTS 出错:', e.error);
    tts.cursor++;
    speakCurrent();
  };
  speechSynthesis.speak(u);
}

function onChapterSpoken() {
  document.querySelectorAll('.content p.speaking').forEach((p) => p.classList.remove('speaking'));
  if (!tts.active) return;
  if (tts.autoNext && state.chapterIndex < state.chapters.length - 1) {
    toast('本章读完，继续下一章');
    loadChapter(state.chapterIndex + 1, true);
  } else {
    toast('已读完本章');
    ttsStop();
  }
}

/** 切章后正文是异步渲染的，稍等一下再开始朗读 */
function ttsStartSoon() {
  setTimeout(() => { if (!$('reader').classList.contains('open')) return; ttsStart(); }, 600);
}

function ttsTogglePause() {
  if (!tts.active) return;
  tts.paused = !tts.paused;
  if (tts.paused) {
    speechSynthesis.pause();
    $('tts-toggle').textContent = '▶ 继续';
  } else {
    speechSynthesis.resume();
    $('tts-toggle').textContent = '⏸ 暂停';
  }
}

function ttsStop() {
  tts.active = false;
  tts.paused = false;
  if (ttsSupported()) speechSynthesis.cancel();
  document.querySelectorAll('.content p.speaking').forEach((p) => p.classList.remove('speaking'));
  $('tts-bar').classList.remove('open');
  $('reader-speak').textContent = '朗读';
}

$('reader-speak').addEventListener('click', () => {
  if (tts.active) ttsStop(); else ttsStart();
});
$('tts-toggle').addEventListener('click', ttsTogglePause);
$('tts-stop').addEventListener('click', ttsStop);
$('tts-close').addEventListener('click', ttsStop);

$('tts-rate').addEventListener('input', (e) => {
  tts.rate = (+e.target.value) / 10;
  $('tts-rate-out').textContent = tts.rate.toFixed(1);
  if (tts.active && !tts.paused) speakCurrent();  // 立即用新语速重读当前段
  localStorage.setItem('tts-rate', tts.rate);
});

$('tts-voice').addEventListener('change', (e) => {
  tts.voiceName = e.target.value;
  localStorage.setItem('tts-voice', tts.voiceName);
  if (tts.active && !tts.paused) speakCurrent();
});

$('tts-auto-next').addEventListener('change', (e) => {
  tts.autoNext = e.target.checked;
  localStorage.setItem('tts-auto-next', tts.autoNext ? '1' : '0');
});

// 离开阅读器或翻章时停止朗读，避免声音与正文不一致
$('reader-back').addEventListener('click', ttsStop);

if (ttsSupported()) {
  // 语音列表在某些浏览器是异步就绪的
  speechSynthesis.onvoiceschanged = fillVoiceSelect;
  fillVoiceSelect();
  $('tts-rate').value = Math.round(tts.rate * 10);
  $('tts-rate-out').textContent = tts.rate.toFixed(1);
  $('tts-auto-next').checked = tts.autoNext;
} else {
  $('reader-speak').disabled = true;
  $('reader-speak').title = '当前浏览器不支持系统朗读';
}

/* ---------------------------------------------------------------- 净化规则 */
async function loadReplaceRules() {
  try {
    const data = await api('/replace');
    const rules = data.rules || [];
    $('replace-status').textContent =
      rules.length ? `共 ${rules.length} 条，启用 ${data.enabledCount} 条` : '';
    $('replace-list').innerHTML = rules.length ? rules.map((r) => `
      <div class="replace-item ${r.isEnabled ? '' : 'disabled'}">
        <div class="info">
          <div class="name">${esc(r.name)}${r.scopeTitle ? ' <span class="muted">(含标题)</span>' : ''}</div>
          <div class="code">${esc(r.pattern)}${r.replacement ? '  →  ' + esc(r.replacement) : '  →  (删除)'}</div>
        </div>
        <button class="ghost" data-toggle="${r.id}" data-enabled="${r.isEnabled}">
          ${r.isEnabled ? '停用' : '启用'}
        </button>
        <button class="ghost" data-del="${r.id}">删除</button>
      </div>`).join('')
      : '<div class="empty">还没有净化规则。加一条试试，比如把「请记住本站」这类广告句删掉。</div>';

    $('replace-list').querySelectorAll('[data-toggle]').forEach((b) =>
      b.addEventListener('click', async () => {
        await api('/replace/toggle?' + q({ id: b.dataset.toggle, enabled: b.dataset.enabled !== 'true' }), { method: 'POST' });
        loadReplaceRules();
      }));

    $('replace-list').querySelectorAll('[data-del]').forEach((b) =>
      b.addEventListener('click', async () => {
        await api('/replace/delete?' + q({ id: b.dataset.del }), { method: 'POST' });
        toast('已删除规则');
        loadReplaceRules();
      }));
  } catch (e) {
    toast('加载净化规则失败: ' + e.message);
  }
}

$('rep-add').addEventListener('click', async () => {
  const pattern = $('rep-pattern').value.trim();
  if (!pattern) { toast('请填写查找内容'); return; }
  try {
    await api('/replace', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: $('rep-name').value.trim(),
        pattern: pattern,
        replacement: $('rep-replacement').value,
        isRegex: true,
        isEnabled: true,
        scopeTitle: $('rep-scope-title').checked,
        scopeContent: true,
      }),
    });
    $('rep-name').value = '';
    $('rep-pattern').value = '';
    $('rep-replacement').value = '';
    $('rep-scope-title').checked = false;
    toast('规则已添加，阅读时生效');
    loadReplaceRules();
  } catch (e) {
    toast('添加失败: ' + e.message);
  }
});

/* ---------------------------------------------------------------- 换源 */
let changeSource = null;

function startChangeSource() {
  const book = state.current;
  if (!book) return;
  if (changeSource) { changeSource.close(); changeSource = null; }

  const list = $('change-list');
  list.innerHTML = '';
  $('change-status').textContent = '正在其它书源里寻找同一本书…（找到就显示）';

  const url = '/api/change-source/stream?' + q({
    name: book.name, author: book.author || '', exclude: book.origin,
  });
  changeSource = new EventSource(url);
  let found = 0, replied = 0, total = 0;

  changeSource.addEventListener('start', (e) => {
    total = JSON.parse(e.data).sourceCount;
    $('change-status').textContent = `正在 ${total} 个书源里查找…`;
  });

  changeSource.addEventListener('candidate', (e) => {
    const d = JSON.parse(e.data);
    replied++;
    $('change-status').textContent = `已查 ${replied}/${total} 个书源 · 找到 ${found} 个`;
    if (!d.found) return;
    found++;

    const row = document.createElement('div');
    row.className = 'toc-item';
    row.innerHTML = `<span style="color:var(--accent)">● </span>${esc(d.sourceName)}`;
    row.title = '点击切换到这个书源';
    row.addEventListener('click', () => applyChangeSource(d));
    list.appendChild(row);
  });

  changeSource.addEventListener('done', (e) => {
    const d = JSON.parse(e.data);
    $('change-status').textContent = found
      ? `找到 ${found} 个可用书源，点击即可切换（${(d.elapsedMs / 1000).toFixed(1)}s）`
      : `其它书源里没找到这本书（${(d.elapsedMs / 1000).toFixed(1)}s）`;
    changeSource.close();
    changeSource = null;
  });

  changeSource.addEventListener('error', () => {
    if (changeSource) { changeSource.close(); changeSource = null; }
    $('change-status').textContent = '换源查询中断';
  });
}

async function applyChangeSource(cand) {
  const book = state.current;
  if (!book || !cand.book) return;
  try {
    const r = await api('/change-source/apply?' + q({
      bookUrl: book.bookUrl,
      sourceUrl: cand.sourceUrl,
      newBookUrl: cand.book.bookUrl,
    }), { method: 'POST' });
    if (!r.ok) { toast(r.error || '换源失败'); return; }
    toast(r.message || '已换源');
    ttsStop();
    $('change-panel').classList.remove('open');
    // 更新当前书对象并重新取目录
    book.origin = r.book.origin;
    book.originName = r.book.originName;
    book.bookUrl = r.book.bookUrl;
    state.chapters = [];
    state.chapterIndex = -1;
    await openBook(book);
  } catch (e) {
    toast('换源失败: ' + e.message);
  }
}

$('reader-change').addEventListener('click', () => {
  const open = $('change-panel').classList.toggle('open');
  $('toc-panel').classList.remove('open');
  $('settings-panel').classList.remove('open');
  $('bookmark-panel').classList.remove('open');
  if (open) startChangeSource();
  else if (changeSource) { changeSource.close(); changeSource = null; }
});
$('change-close').addEventListener('click', () => {
  $('change-panel').classList.remove('open');
  if (changeSource) { changeSource.close(); changeSource = null; }
});

/* ---------------------------------------------------------------- 主题编辑器
 * 配色存两份：
 *   引擎 ThemeConfig  = 当前生效的那一套（持久化在配置目录）
 *   /api/themes       = 可保存/切换的命名主题集合（含内置预设）
 * 调色时先写 ThemeConfig 立即生效；满意了再「另存为新主题」。
 */
const THEME_KEYS = [
  ['background', 'backgroundNight', '背景'],
  ['textColor', 'textColorNight', '正文文字'],
  ['mutedColor', 'mutedColorNight', '次要文字'],
  ['panelColor', 'panelColorNight', '面板底色'],
  ['borderColor', 'borderColorNight', '分隔线'],
  ['primary', 'primaryNight', '主色'],
  ['accent', 'accentNight', '强调色'],
];

let themeState = {
  isNight: false,
  current: null,     // /api/theme 返回的 ThemeConfig
  themes: [],        // 命名主题
  editing: '',       // 正在编辑的主题名
};

function argbToHex(argb) {
  return '#' + ((argb >>> 0) & 0xFFFFFF).toString(16).padStart(6, '0');
}

function hexToArgb(hex) {
  return (0xFF000000 | parseInt(hex.slice(1), 16)) | 0;
}

/** 把当前配色铺到 CSS 变量上（日间/夜间各取对应那一组） */
function applyThemeVars(t, isNight) {
  if (!t) return;
  const root = document.documentElement.style;
  const pick = (day, night) => argbToHex(isNight ? (t[night] ?? t[day]) : t[day]);
  root.setProperty('--bg', pick('background', 'backgroundNight'));
  root.setProperty('--text', pick('textColor', 'textColorNight'));
  root.setProperty('--muted', pick('mutedColor', 'mutedColorNight'));
  root.setProperty('--panel', pick('panelColor', 'panelColorNight'));
  root.setProperty('--line', pick('borderColor', 'borderColorNight'));
  root.setProperty('--accent', argbToHex(t.accent));
  root.setProperty('--accent-soft', argbToHex(t.accent) + '22');
  root.setProperty('--primary', argbToHex(t.primary));
  document.documentElement.dataset.night = isNight ? '1' : '0';
}

async function loadTheme() {
  try {
    const data = await api('/theme');
    themeState.current = data.theme;
    themeState.isNight = !!data.theme.isNightTheme;
    applyThemeVars(data.theme, themeState.isNight);
    renderThemeEditor();
  } catch (e) { /* 主题加载失败不阻塞启动 */ }
}

/** 直接改某几个颜色（立即生效） */
async function patchTheme(patch) {
  try {
    const r = await api('/theme', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    });
    if (r.theme) {
      themeState.current = r.theme;
      themeState.isNight = !!r.theme.isNightTheme;
      applyThemeVars(r.theme, themeState.isNight);
    }
    return r;
  } catch (e) {
    toast('配色保存失败: ' + e.message);
    return null;
  }
}

async function loadThemeList() {
  try {
    const data = await api('/themes');
    themeState.themes = data.themes || [];
    renderThemeList();
  } catch (e) { /* 忽略 */ }
}

function renderThemeList() {
  const box = $('theme-list');
  if (!box) return;
  box.innerHTML = themeState.themes.map((t) => {
    const isActive = themeState.editing === t.name;
    return `
    <div class="theme-item ${isActive ? 'active' : ''}" data-theme="${esc(t.name)}">
      <span class="theme-swatch">
        <i style="background:${argbToHex(t.background)}"></i>
        <i style="background:${argbToHex(t.panelColor)}"></i>
        <i style="background:${argbToHex(t.accent)}"></i>
        <i style="background:${argbToHex(t.textColor)}"></i>
      </span>
      <span class="tname">${esc(t.name)}${t.isNight ? ' <span class="tnight">夜</span>' : ''}</span>
      ${t.custom ? `<button class="tdel" data-deltheme="${esc(t.name)}" title="删除">×</button>` : ''}
    </div>`;
  }).join('');

  box.querySelectorAll('[data-theme]').forEach((el) =>
    el.addEventListener('click', (ev) => {
      if (ev.target.closest('[data-deltheme]')) return;
      applyNamedTheme(el.dataset.theme);
    }));
  box.querySelectorAll('[data-deltheme]').forEach((btn) =>
    btn.addEventListener('click', async (ev) => {
      ev.stopPropagation();
      const name = btn.dataset.deltheme;
      if (!confirm(`删除主题《${name}》？`)) return;
      const r = await api('/themes/delete?name=' + encodeURIComponent(name), { method: 'POST' });
      toast(r.message || r.error || '');
      await loadThemeList();
    }));
}

async function applyNamedTheme(name) {
  try {
    const r = await api('/themes/apply?name=' + encodeURIComponent(name), { method: 'POST' });
    if (!r.ok) { toast(r.error || '套用失败'); return; }
    themeState.editing = name;
    toast(r.message || '已套用');
    await loadTheme();
    await loadThemeList();
  } catch (e) {
    toast('套用失败: ' + e.message);
  }
}

/** 渲染左侧配色编辑器与预览 */
function renderThemeEditor() {
  const t = themeState.current;
  if (!t || !$('color-grid')) return;
  const isNight = themeState.isNight;

  $('theme-editing').textContent = themeState.editing ||
    (isNight ? (t.nameNight || '（未命名）') : (t.name || '（未命名）'));
  $('theme-editing-kind').textContent = isNight ? '· 夜间配色' : '· 日间配色';
  $('theme-daynight').querySelectorAll('.seg-btn').forEach((b) =>
    b.classList.toggle('active', (b.dataset.night === '1') === isNight));

  $('color-grid').innerHTML = THEME_KEYS.map(([dayKey, nightKey, label]) => {
    const key = isNight ? nightKey : dayKey;
    const hex = argbToHex(t[key] ?? t[dayKey]);
    return `
      <div class="color-row">
        <label>${label}</label>
        <input type="color" data-ckey="${key}" value="${hex}">
        <input class="hex" data-hkey="${key}" value="${hex}" spellcheck="false">
      </div>`;
  }).join('');

  $('color-grid').querySelectorAll('input[type=color]').forEach((el) =>
    el.addEventListener('input', () => {
      const key = el.dataset.ckey;
      const hex = el.value;
      const sib = $('color-grid').querySelector(`[data-hkey="${key}"]`);
      if (sib) sib.value = hex;
      patchTheme({ [key]: hexToArgb(hex) });
    }));

  $('color-grid').querySelectorAll('.hex').forEach((el) =>
    el.addEventListener('change', () => {
      let v = el.value.trim();
      if (!/^#?[0-9a-fA-F]{6}$/.test(v)) { toast('颜色要写成 #RRGGBB'); renderThemeEditor(); return; }
      if (!v.startsWith('#')) v = '#' + v;
      const key = el.dataset.hkey;
      const picker = $('color-grid').querySelector(`[data-ckey="${key}"]`);
      if (picker) picker.value = v;
      patchTheme({ [key]: hexToArgb(v) });
    }));
}

$('theme-daynight')?.addEventListener('click', async (e) => {
  const btn = e.target.closest('[data-night]');
  if (!btn) return;
  const night = btn.dataset.night === '1';
  await patchTheme({ isNightTheme: night });
  themeState.editing = '';
  renderThemeEditor();
});

$('theme-saveas')?.addEventListener('click', async () => {
  const name = prompt('给这套配色起个名字', themeState.editing || '我的主题');
  if (!name || !name.trim()) return;
  try {
    const r = await api('/themes/snapshot?' + q({
      name: name.trim(), isNight: themeState.isNight,
    }), { method: 'POST' });
    toast(r.message || r.error || '');
    themeState.editing = name.trim();
    await loadThemeList();
    renderThemeEditor();
  } catch (e) { toast('保存失败: ' + e.message); }
});

$('theme-reset2')?.addEventListener('click', async () => {
  await api('/theme?reset=1', { method: 'POST' });
  themeState.editing = '';
  await loadTheme();
  toast('已恢复默认配色');
});

$('theme-reload')?.addEventListener('click', async () => {
  themeState.editing = '';
  await loadTheme();
  toast('已放弃改动');
});

/* ---------------------------------------------------------------- 音视频播放
 * 书源类型 1（音频）与 4（视频）。引擎侧与文字书一致，区别只在取到内容后
 * 当播放地址用（legado 的 `AudioPlay` / `VideoPlay` 也是这样）。
 * 两者共用这一个播放器，只是元素换成 <audio> 或 <video>。
 */
let mediaKind = 'video';

/** 按类型创建（或复用）播放器元素 */
function ensurePlayer(kind) {
  const slot = $('media-slot');
  const tag = kind === 'audio' ? 'audio' : 'video';
  let el = slot.firstElementChild;
  if (!el || el.tagName.toLowerCase() !== tag) {
    slot.innerHTML = '';
    el = document.createElement(tag);
    el.id = 'media-player';
    el.controls = true;
    el.preload = 'metadata';
    if (tag === 'video') el.setAttribute('playsinline', '');
    el.addEventListener('ended', () => {
      if (state.chapterIndex < state.chapters.length - 1) loadMediaChapter(state.chapterIndex + 1);
    });
    slot.appendChild(el);
  }
  return el;
}

async function openMedia(book, kind) {
  state.current = book;
  mediaKind = kind;
  $('reader').classList.remove('open');
  $('manga-view').classList.remove('open');
  $('video-view').classList.add('open');
  $('video-title').textContent = `${book.name} · ${book.author || '佚名'}` +
    (kind === 'audio' ? '　🔊 音频' : '');
  $('video-hint').textContent = '正在获取目录…';
  ensurePlayer(kind).removeAttribute('src');

  try {
    const data = await api('/toc?' + q({
      sourceUrl: book.origin, bookUrl: book.bookUrl,
      name: book.name, author: book.author,
    }));
    if (!data.ok) { $('video-hint').textContent = '目录获取失败：' + (data.error || ''); return; }
    state.chapters = data.chapters || [];
    renderVideoToc();
    if (!state.chapters.length) { $('video-hint').textContent = '目录为空'; return; }
    const resume = Math.min(Math.max(book.durChapterIndex || 0, 0), state.chapters.length - 1);
    await loadMediaChapter(resume);
  } catch (e) {
    $('video-hint').textContent = '出错了：' + e.message;
  }
}

function renderVideoToc() {
  $('video-toc-list').innerHTML = state.chapters.map((c) => `
    <div class="toc-item ${c.index === state.chapterIndex ? 'active' : ''}" data-vch="${c.index}">
      ${esc(c.title)}
    </div>`).join('');
  $('video-toc-list').querySelectorAll('[data-vch]').forEach((el) =>
    el.addEventListener('click', () => {
      loadMediaChapter(+el.dataset.vch);
      $('video-toc-panel').classList.remove('open');
    }));
}

async function loadMediaChapter(index) {
  const book = state.current;
  if (!book) return;
  const player = ensurePlayer(mediaKind);
  $('video-hint').textContent = '正在解析播放地址…';
  player.removeAttribute('src');
  try {
    const ep = mediaKind === 'audio' ? '/audio/content?' : '/video/content?';
    const data = await api(ep + q({ bookUrl: book.bookUrl, index: index }));
    if (!data.ok) {
      $('video-hint').textContent = '加载失败：' + (data.error || '');
      return;
    }
    state.chapterIndex = data.index;
    $('video-pos').textContent = `${data.index + 1} / ${data.total}`;
    $('video-prev').disabled = data.prevIndex < 0;
    $('video-next').disabled = data.nextIndex < 0;
    player.src = data.mediaUrl || data.videoUrl;
    $('video-hint').textContent = data.title || '';
    renderVideoToc();
    saveProgress(data.index, data.title, data.total);
  } catch (e) {
    $('video-hint').textContent = '出错了：' + e.message;
  }
}

$('video-back').addEventListener('click', () => {
  $('video-view').classList.remove('open');
  const p = $('media-player');
  if (p) p.pause();
});
$('video-prev').addEventListener('click', () => { if (state.chapterIndex > 0) loadMediaChapter(state.chapterIndex - 1); });
$('video-next').addEventListener('click', () => { if (state.chapterIndex < state.chapters.length - 1) loadMediaChapter(state.chapterIndex + 1); });
$('video-toc').addEventListener('click', () => $('video-toc-panel').classList.toggle('open'));
$('video-toc-close').addEventListener('click', () => $('video-toc-panel').classList.remove('open'));

/* ---------------------------------------------------------------- 词典查询
 * 词条规则与书源同构（urlRule + showRule），引擎侧直接复用 AnalyzeUrl + AnalyzeRule。
 */
async function queryDict(word) {
  const w = (word || '').trim();
  if (!w) { toast('请输入要查的词'); return; }
  $('dict-word').value = w;
  $('dict-status').textContent = '查询中…';
  $('dict-results').innerHTML = '';
  try {
    const data = await api('/dict/query?' + q({ word: w }));
    const rs = data.results || [];
    if (!rs.length) {
      $('dict-status').textContent = '还没有配置词典';
      return;
    }
    const ok = rs.filter((r) => r.ok && (r.content || '').trim()).length;
    $('dict-status').textContent = `${rs.length} 部词典，${ok} 部有结果`;
    $('dict-results').innerHTML = rs.map((r) => `
      <div class="dict-item">
        <div class="dict-name">${esc(r.name)} <span class="muted">${r.elapsedMs}ms</span></div>
        ${r.ok
          ? (r.content || '').trim()
            ? `<div class="dict-text">${esc(r.content)}</div>`
            : '<div class="dict-err">没有查到</div>'
          : `<div class="dict-err">${esc(r.error || '查询失败')}</div>`}
      </div>`).join('');
  } catch (e) {
    $('dict-status').textContent = '查询失败：' + e.message;
  }
}

$('reader-dict').addEventListener('click', () => {
  const open = $('dict-panel').classList.toggle('open');
  $('toc-panel').classList.remove('open');
  $('settings-panel').classList.remove('open');
  $('bookmark-panel').classList.remove('open');
  $('change-panel').classList.remove('open');
  if (open) {
    // 有选中文本就带出来，省得手输
    const sel = window.getSelection ? String(window.getSelection()).trim() : '';
    if (sel && sel.length <= 30) $('dict-word').value = sel;
    $('dict-word').focus();
  }
});
$('dict-close').addEventListener('click', () => $('dict-panel').classList.remove('open'));
$('dict-query').addEventListener('click', () => queryDict($('dict-word').value));
$('dict-word').addEventListener('keydown', (e) => { if (e.key === 'Enter') queryDict($('dict-word').value); });

/* ---------------------------------------------------------------- 同步 / 备份 */
async function loadWebDav() {
  try {
    const data = await api('/webdav');
    const s = (data.servers || [])[0];
    if (s) {
      $('dav-name').value = s.name || '';
      $('dav-url').value = s.url || '';
      $('dav-user').value = s.username || '';
      $('dav-status').textContent = `已配置：${s.name || s.url}`;
    } else {
      $('dav-status').textContent = '还没有配置 WebDAV';
    }
  } catch (e) { /* 忽略 */ }
}

$('dav-save').addEventListener('click', async () => {
  const url = $('dav-url').value.trim();
  if (!url) { toast('请填写 WebDAV 地址'); return; }
  try {
    const r = await api('/webdav', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: $('dav-name').value.trim() || 'WebDAV',
        url: url,
        username: $('dav-user').value.trim(),
        password: $('dav-pass').value,
      }),
    });
    $('dav-pass').value = '';
    toast(r.message || '已保存');
    loadWebDav();
  } catch (e) { toast('保存失败: ' + e.message); }
});

$('dav-backup').addEventListener('click', async () => {
  $('dav-status').textContent = '正在备份到 WebDAV…';
  try {
    const r = await api('/webdav/backup', { method: 'POST' });
    $('dav-status').textContent = r.message || (r.ok ? '备份完成' : ('失败：' + (r.error || '')));
    toast(r.ok ? '已备份到 WebDAV' : ('备份失败：' + (r.error || '')));
  } catch (e) { $('dav-status').textContent = '备份失败：' + e.message; }
});

$('dav-restore').addEventListener('click', async () => {
  if (!confirm('从 WebDAV 恢复会与本地数据合并，继续？')) return;
  $('dav-status').textContent = '正在从 WebDAV 恢复…';
  try {
    const r = await api('/webdav/restore', { method: 'POST' });
    $('dav-status').textContent = r.message || (r.ok ? '恢复完成' : ('失败：' + (r.error || '')));
    if (r.ok) { await loadSources(); await loadShelf(); }
    toast(r.ok ? r.message : ('恢复失败：' + (r.error || '')));
  } catch (e) { $('dav-status').textContent = '恢复失败：' + e.message; }
});

$('bk-local').addEventListener('click', () => {
  const a = document.createElement('a');
  a.href = '/api/backup';
  a.download = 'legado-backup.json';
  document.body.appendChild(a); a.click(); a.remove();
  toast('正在导出备份');
});

$('bk-import').addEventListener('click', () => $('bk-file').click());
$('bk-file').addEventListener('change', async (e) => {
  const f = e.target.files?.[0];
  if (!f) return;
  $('bk-status').textContent = '正在恢复…';
  try {
    const text = await f.text();
    const r = await api('/restore', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: text,
    });
    $('bk-status').textContent = r.message || (r.ok ? '恢复完成' : ('失败：' + (r.error || '')));
    if (r.ok) { await loadSources(); await loadShelf(); }
  } catch (err) {
    $('bk-status').textContent = '恢复失败：' + err.message;
  }
  e.target.value = '';
});

/* ---------------------------------------------------------------- 漫画阅读
 * 与文字书走同一套规则引擎与抓取链路，区别只在最后一步：
 * 文字书取文本，图片书用 imgPattern 抽出图片地址（复用上游 BookHelp.flowImages）。
 * 是否漫画由书源的 bookSourceType（2 = 图片）决定。
 */
async function openManga(book) {
  state.current = book;
  state.isManga = true;
  $('reader').classList.remove('open');
  $('manga-view').classList.add('open');
  $('manga-title').textContent = `${book.name} · ${book.author || '佚名'}`;
  $('manga-pages').innerHTML = '<div class="manga-tip">正在获取目录…</div>';

  try {
    const data = await api('/toc?' + q({
      sourceUrl: book.origin, bookUrl: book.bookUrl,
      name: book.name, author: book.author,
    }));
    if (!data.ok) {
      $('manga-pages').innerHTML = `<div class="manga-tip">目录获取失败：${esc(data.error || '')}</div>`;
      return;
    }
    state.chapters = data.chapters || [];
    renderMangaToc();
    if (!state.chapters.length) {
      $('manga-pages').innerHTML = '<div class="manga-tip">目录为空</div>';
      return;
    }
    const resume = Math.min(Math.max(book.durChapterIndex || 0, 0), state.chapters.length - 1);
    await loadMangaChapter(resume);
  } catch (e) {
    $('manga-pages').innerHTML = `<div class="manga-tip">出错了：${esc(e.message)}</div>`;
  }
}

function renderMangaToc() {
  $('manga-toc-list').innerHTML = state.chapters.map((c) => `
    <div class="toc-item ${c.index === state.chapterIndex ? 'active' : ''}" data-mch="${c.index}">
      ${esc(c.title)}
    </div>`).join('');
  $('manga-toc-list').querySelectorAll('[data-mch]').forEach((el) =>
    el.addEventListener('click', () => {
      loadMangaChapter(+el.dataset.mch);
      $('manga-toc-panel').classList.remove('open');
    }));
}

async function loadMangaChapter(index) {
  const book = state.current;
  if (!book) return;
  $('manga-pages').innerHTML = '<div class="manga-tip">正在加载…</div>';
  try {
    const data = await api('/manga/content?' + q({ bookUrl: book.bookUrl, index: index }));
    if (!data.ok) {
      $('manga-pages').innerHTML = `<div class="manga-tip">加载失败：${esc(data.error || '')}</div>`;
      return;
    }
    state.chapterIndex = data.index;
    $('manga-pos').textContent = `${data.index + 1} / ${data.total}`;
    $('manga-prev').disabled = data.prevIndex < 0;
    $('manga-next').disabled = data.nextIndex < 0;

    if (!data.images.length) {
      $('manga-pages').innerHTML =
        '<div class="manga-tip">这一话没有解析出图片<br>可能是书源的图片规则与该站点不匹配</div>';
      return;
    }
    $('manga-pages').innerHTML = data.images.map((src, i) => `
      <img src="${esc(src)}" loading="lazy" referrerpolicy="no-referrer"
           alt="第 ${i + 1} 页"
           onerror="this.replaceWith(Object.assign(document.createElement('div'),
                    {className:'manga-tip',textContent:'第 ${i + 1} 页加载失败'}))">
      ${i < data.images.length - 1 ? '<div class="page-sep"></div>' : ''}
    `).join('');
    $('manga-body').scrollTop = 0;
    renderMangaToc();
    // 记录进度（复用文字书的进度接口）
    saveProgress(data.index, data.title, data.total);
  } catch (e) {
    $('manga-pages').innerHTML = `<div class="manga-tip">出错了：${esc(e.message)}</div>`;
  }
}

$('manga-back').addEventListener('click', () => $('manga-view').classList.remove('open'));
$('manga-prev').addEventListener('click', () => { if (state.chapterIndex > 0) loadMangaChapter(state.chapterIndex - 1); });
$('manga-next').addEventListener('click', () => { if (state.chapterIndex < state.chapters.length - 1) loadMangaChapter(state.chapterIndex + 1); });
$('manga-toc').addEventListener('click', () => $('manga-toc-panel').classList.toggle('open'));
$('manga-toc-close').addEventListener('click', () => $('manga-toc-panel').classList.remove('open'));

/* ---------------------------------------------------------------- 书源调试
 * 引擎里 142 个 Debug.log 调用点会在解析规则时输出过程信息，
 * 这里把它们实时显示出来——排查失效书源时能直接看到是哪一步断了。
 */
let debugSource = null;

function startDebug(sourceUrl, sourceName, key) {
  if (debugSource) { debugSource.close(); debugSource = null; }
  const log = $('debug-log');
  log.textContent = '';
  $('debug-title').textContent = `书源调试 · ${sourceName}`;
  $('debug-key').value = key;
  $('debug-view').dataset.source = sourceUrl;

  const url = '/api/debug/stream?' + q({ sourceUrl: sourceUrl, key: key });
  debugSource = new EventSource(url);
  let lines = 0;

  const append = (text, cls) => {
    const span = document.createElement('span');
    if (cls) span.className = cls;
    span.textContent = text + '\n';
    log.appendChild(span);
    // 自动滚到底
    log.parentElement.scrollTop = log.parentElement.scrollHeight;
  };

  debugSource.addEventListener('start', (e) => {
    const d = JSON.parse(e.data);
    append(`▶ 开始调试《${d.sourceName}》，关键词「${d.key}」`, 'dim');
    append(`  搜索地址：${d.searchUrl || '(空)'}`, 'dim');
  });

  debugSource.addEventListener('log', (e) => {
    lines++;
    append(JSON.parse(e.data).msg);
  });

  debugSource.addEventListener('done', (e) => {
    const d = JSON.parse(e.data);
    append('', null);
    if (d.ok) append(`✓ 调试完成：找到 ${d.bookCount} 本书，输出 ${d.logCount} 行日志（${(d.elapsedMs / 1000).toFixed(1)}s）`, 'ok');
    else append(`✗ 调试失败：${d.error}（输出 ${d.logCount} 行日志）`, 'err');
    debugSource.close();
    debugSource = null;
  });

  debugSource.addEventListener('error', () => {
    if (debugSource) { debugSource.close(); debugSource = null; }
    append('✗ 调试连接中断', 'err');
  });

  if (!lines) append('（等待引擎输出…）', 'dim');
}

$('debug-back').addEventListener('click', () => {
  $('debug-view').classList.remove('open');
  if (debugSource) { debugSource.close(); debugSource = null; }
});
$('debug-run').addEventListener('click', () => {
  const src = $('debug-view').dataset.source;
  const name = $('debug-title').textContent.replace('书源调试 · ', '');
  startDebug(src, name, $('debug-key').value.trim() || '测试');
});

/* ---------------------------------------------------------------- 书签 */
async function loadBookmarks() {
  const book = state.current;
  if (!book) return;
  try {
    const data = await api('/bookmark?' + q({ name: book.name, author: book.author }));
    const list = data.bookmarks || [];
    $('bookmark-list').innerHTML = list.length ? list.map((b) => `
      <div class="toc-item" data-bm="${b.chapterIndex}">
        ${esc(b.chapterName || ('第 ' + (b.chapterIndex + 1) + ' 章'))}
        <button class="ghost" data-bmdel="${b.time}"
                style="float:right;padding:1px 8px;font-size:11px">×</button>
      </div>`).join('')
      : '<div class="empty" style="padding:30px 0">这本书还没有书签</div>';

    $('bookmark-list').querySelectorAll('[data-bm]').forEach((el) =>
      el.addEventListener('click', (ev) => {
        if (ev.target.dataset.bmdel) return;
        loadChapter(+el.dataset.bm);
        $('bookmark-panel').classList.remove('open');
      }));

    $('bookmark-list').querySelectorAll('[data-bmdel]').forEach((btn) =>
      btn.addEventListener('click', async (ev) => {
        ev.stopPropagation();
        await api('/bookmark/delete?' + q({ time: btn.dataset.bmdel }), { method: 'POST' });
        toast('已删除书签');
        loadBookmarks();
      }));
  } catch (e) {
    toast('加载书签失败: ' + e.message);
  }
}

$('reader-bookmark').addEventListener('click', () => {
  $('bookmark-panel').classList.toggle('open');
  $('settings-panel').classList.remove('open');
  $('toc-panel').classList.remove('open');
  loadBookmarks();
});
$('bookmark-close').addEventListener('click', () => $('bookmark-panel').classList.remove('open'));

$('bookmark-add').addEventListener('click', async () => {
  const book = state.current;
  if (!book || state.chapterIndex < 0) { toast('先打开一章再添加书签'); return; }
  const ch = state.chapters[state.chapterIndex];
  const body = {
    bookName: book.name,
    bookAuthor: book.author || '',
    chapterIndex: state.chapterIndex,
    chapterPos: 0,
    chapterName: ch ? (ch.title || '') : '',
    bookText: $('reader-chapter').textContent || '',
    content: ($('reader-content').textContent || '').slice(0, 100),
  };
  try {
    await api('/bookmark', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    toast('已添加书签');
    loadBookmarks();
  } catch (e) {
    toast('添加书签失败: ' + e.message);
  }
});

/* ---------------------------------------------------------------- 订阅源（RSS） */
async function loadRssSources() {
  const sel = $('rss-source');
  const cur = sel.value;
  try {
    const data = await api('/rss/sources');
    state.rssSources = data.sources || [];
    const list = state.rssSources.filter((s) => s.enabled);
    sel.innerHTML = '<option value="">选择订阅源</option>' +
      list.map((s) => `<option value="${esc(s.url)}">${esc(s.name)}</option>`).join('');
    if (cur && list.some((s) => s.url === cur)) sel.value = cur;
    $('rss-status').textContent = list.length
      ? `${list.length} 个订阅源`
      : '还没有订阅源，点「导入订阅源」粘贴 JSON';
  } catch (e) {
    toast('加载订阅源失败: ' + e.message);
  }
}

async function loadRssSorts(sourceUrl) {
  $('rss-list').innerHTML = '';
  $('rss-sorts').innerHTML = '';
  if (!sourceUrl) return;
  $('rss-status').textContent = '加载分类…';
  try {
    const data = await api('/rss/sorts?' + q({ sourceUrl }));
    if (!data.ok) { $('rss-status').textContent = data.error || '加载失败'; return; }
    const kinds = data.kinds || [];
    // 没有分类的订阅源（singleUrl）直接用源地址取文章
    if (!kinds.length) {
      $('rss-status').textContent = '该订阅源没有分类，直接取最新文章';
      loadRssArticles(sourceUrl, '', sourceUrl, 1);
      return;
    }
    $('rss-status').textContent = `${kinds.length} 个分类`;
    $('rss-sorts').innerHTML = kinds.map((k, i) =>
      `<button class="chip" data-sort="${i}">${esc(k.title)}</button>`).join('');
    $('rss-sorts').querySelectorAll('[data-sort]').forEach((b) =>
      b.addEventListener('click', () => {
        $('rss-sorts').querySelectorAll('.chip').forEach((c) => c.classList.remove('active'));
        b.classList.add('active');
        const k = kinds[+b.dataset.sort];
        loadRssArticles(sourceUrl, k.title, k.url || sourceUrl, 1);
      }));
    $('rss-sorts').querySelector('[data-sort]')?.click();
  } catch (e) {
    $('rss-status').textContent = '';
    toast('加载订阅分类失败: ' + e.message);
  }
}

async function loadRssArticles(sourceUrl, sortName, sortUrl, page) {
  $('rss-list').innerHTML = '<div class="empty">加载中…</div>';
  try {
    const data = await api('/rss/articles?' + q({ sourceUrl, sortName, sortUrl, page }));
    if (!data.ok) {
      $('rss-list').innerHTML = `<div class="empty">加载失败：${esc(data.error || '未知错误')}</div>`;
      return;
    }
    const arts = data.articles || [];
    state.rssArticles = arts;
    if (!arts.length) {
      $('rss-list').innerHTML = '<div class="empty">这个分类下暂无文章</div>';
      return;
    }
    $('rss-list').innerHTML = arts.map((a, i) => `
      <div class="item" data-art="${i}">
        <div class="thumb">${coverHtml(a.image, '文')}</div>
        <div class="meta">
          <div class="title">${a.read ? '' : '<span style="color:var(--accent)">● </span>'}${esc(a.title)}</div>
          <div class="sub">${esc(a.pubDate || '')} · ${esc(a.group || '')}</div>
          <div class="intro">${esc((a.description || '').replace(/<[^>]+>/g, '').slice(0, 120))}</div>
        </div>
      </div>`).join('');

    $('rss-list').querySelectorAll('[data-art]').forEach((el) =>
      el.addEventListener('click', () => openArticle(arts[+el.dataset.art], sourceUrl)));
  } catch (e) {
    $('rss-list').innerHTML = `<div class="empty">出错了：${esc(e.message)}</div>`;
  }
}

async function openArticle(a, sourceUrl) {
  $('article-reader').classList.add('open');
  $('article-title').textContent = a.group || '';
  $('article-heading').textContent = a.title || '';
  $('article-meta').textContent = a.pubDate || '';
  $('article-content').innerHTML = '<p class="muted">正在加载…</p>';
  try {
    const data = await api('/rss/content?' + q({
      sourceUrl: sourceUrl, link: a.link, sort: a.sort,
    }));
    if (!data.ok) {
      $('article-content').innerHTML = `<p class="muted">加载失败：${esc(data.error || '')}</p>`;
      return;
    }
    $('article-content').innerHTML = toParagraphs(data.content);
    $('article-body').scrollTop = 0;
    // 标记已读
    api('/rss/read?' + q({ sourceUrl: sourceUrl, link: a.link, sort: a.sort }), { method: 'POST' })
      .catch(() => {});
    a.read = true;
  } catch (e) {
    $('article-content').innerHTML = `<p class="muted">出错了：${esc(e.message)}</p>`;
  }
}

$('rss-source').addEventListener('change', (e) => loadRssSorts(e.target.value));
$('rss-refresh').addEventListener('click', async () => {
  await loadRssSources();
  const v = $('rss-source').value;
  if (v) loadRssSorts(v);
});
$('rss-import').addEventListener('click', async () => {
  const text = prompt('粘贴订阅源 JSON（单个对象或数组）');
  if (!text || !text.trim()) return;
  try {
    const r = await api('/rss/sources', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: text,
    });
    toast(`已导入 ${r.imported} 个订阅源`);
    await loadRssSources();
  } catch (e) {
    toast('导入失败: ' + e.message);
  }
});
$('article-back').addEventListener('click', () => $('article-reader').classList.remove('open'));
$('article-theme').addEventListener('click', () => $('reader-theme').click());
$('article-settings').addEventListener('click', () => {
  $('article-reader').classList.remove('open');
  toast('阅读设置请从书籍阅读器进入');
});

/* ---------------------------------------------------------------- 阅读历史 */
async function loadHistory() {
  try {
    const data = await api('/history');
    const rs = data.records || [];
    $('history-status').textContent = rs.length ? `共 ${rs.length} 条` : '';
    $('history-list').innerHTML = rs.length ? rs.map((r) => `
      <div class="replace-item">
        <div class="info">
          <div class="name">${esc(r.bookName)}</div>
          <div class="code">累计阅读 ${esc(r.readTimeText)} · 最近 ${new Date(r.lastRead).toLocaleString()}</div>
        </div>
      </div>`).join('')
      : '<div class="empty">还没有阅读记录</div>';
  } catch (e) {
    toast('加载历史失败: ' + e.message);
  }
}

$('history-refresh').addEventListener('click', loadHistory);

/* ---------------------------------------------------------------- 阅读进度 */
let progressTimer = null;

function saveProgress(index, title, total) {
  const book = state.current;
  if (!book) return;
  book.durChapterIndex = index;
  book.durChapterTitle = title;
  // 累计本次在这一章停留的时长（翻页时结算上一章）
  const now = Date.now();
  const readMs = state.chapterOpenedAt ? Math.min(now - state.chapterOpenedAt, 30 * 60 * 1000) : 0;
  state.chapterOpenedAt = now;
  // 合并短时间内的多次翻页，避免频繁写盘
  clearTimeout(progressTimer);
  progressTimer = setTimeout(async () => {
    try {
      await api('/progress?' + q({
        bookUrl: book.bookUrl, index: index, title: title, total: total, readMs: readMs,
      }), { method: 'POST' });
    } catch (e) { /* 进度记录失败不打扰阅读 */ }
  }, 800);
}

/* ---------------------------------------------------------------- 阅读设置 */
function applyReaderConfig() {
  const c = state.config || {};
  const fontSize = c.fontSize || 18;
  const lh = (18 + (c.lineSpacingExtra || 0)) / 18;
  const indent = c.paragraphIndent !== '' && c.paragraphIndent != null;
  const paraGap = state.paraGap;

  document.documentElement.style.setProperty('--read-font-size', fontSize + 'px');
  document.documentElement.style.setProperty('--read-line-height', lh.toFixed(2));
  document.documentElement.style.setProperty('--read-para-gap', paraGap + 'em');
  const content = $('reader-content');
  if (content) {
    content.style.fontSize = fontSize + 'px';
    content.style.lineHeight = lh.toFixed(2);
    content.style.textAlign = c.textFullJustify ? 'justify' : 'start';
  }
  document.body.classList.toggle('no-indent', !indent);

  if ($('set-fontsize')) {
    $('set-fontsize').value = fontSize;
    $('out-fontsize').textContent = fontSize;
    $('set-lineheight').value = c.lineSpacingExtra || 8;
    $('out-lineheight').textContent = lh.toFixed(2);
    $('set-para').value = Math.round(paraGap * 10);
    $('out-para').textContent = paraGap.toFixed(1);
    $('set-indent').checked = indent;
    $('set-justify').checked = !!c.textFullJustify;
  }
}

async function loadConfig() {
  try {
    const data = await api('/config');
    state.config = data.config || {};
  } catch (e) {
    state.config = { fontSize: 18, lineSpacingExtra: 8, paragraphIndent: '　　' };
  }
  applyReaderConfig();
}

async function pushConfig(patch) {
  Object.assign(state.config, patch);
  applyReaderConfig();
  try {
    const data = await api('/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    });
    if (data.config) { state.config = data.config; applyReaderConfig(); }
  } catch (e) {
    toast('设置保存失败: ' + e.message);
  }
}

function bindSettings() {
  const fs = $('set-fontsize');
  fs.value = (state.config && state.config.fontSize) || 18;
  fs.addEventListener('input', () => { $('out-fontsize').textContent = fs.value; });
  fs.addEventListener('change', () => pushConfig({ fontSize: +fs.value }));

  const lh = $('set-lineheight');
  lh.value = (state.config && state.config.lineSpacingExtra) || 8;
  lh.addEventListener('input', () => {
    $('out-lineheight').textContent = ((18 + (+lh.value)) / 18).toFixed(2);
  });
  lh.addEventListener('change', () => pushConfig({ lineSpacingExtra: +lh.value }));

  // 段间距纯属前端排版，不入引擎配置，只记在本地
  const pg = $('set-para');
  pg.value = Math.round(state.paraGap * 10);
  pg.addEventListener('input', () => {
    $('out-para').textContent = ((+pg.value) / 10).toFixed(1);
  });
  pg.addEventListener('change', () => {
    state.paraGap = (+pg.value) / 10;
    localStorage.setItem('reader-para-gap', state.paraGap);
    applyReaderConfig();
  });

  $('set-indent').addEventListener('change', (e) =>
    pushConfig({ paragraphIndent: e.target.checked ? '　　' : '' }));

  $('set-justify').addEventListener('change', (e) =>
    pushConfig({ textFullJustify: e.target.checked }));
}

$('reader-settings').addEventListener('click', () => {
  $('settings-panel').classList.toggle('open');
  $('toc-panel').classList.remove('open');
});
$('settings-close').addEventListener('click', () => $('settings-panel').classList.remove('open'));

/* ---------------------------------------------------------------- 书源列表筛选 */
let sourceKwTimer = null;
$('source-kw').addEventListener('input', (e) => {
  // 轻量防抖：书源可能上千条（整包导入），每敲一个字就全量重排会卡
  clearTimeout(sourceKwTimer);
  const v = e.target.value;
  sourceKwTimer = setTimeout(() => {
    state.sourceKeyword = v;
    // 输关键词时把分组筛选清掉——用户搜一个名字通常是"在所有书源里找"，
    // 被上一次选的分组限制住会让人以为搜不到。
    if (v.trim()) state.sourceGroupFilter = 'all';
    renderSources();
  }, 160);
});

/* ---------------------------------------------------------------- 书源校验
 * 拿一个关键词去每个源搜一次：搜到书=可用，连接成功但 0 结果=可疑，
 * 报错/超时=失效。导入整包书源后必然混着一批失效源，用它挑出来清理。
 */
let checkSource = null;
let checkBad = [];

function startCheck() {
  if (checkSource) { checkSource.close(); checkSource = null; }
  const box = $('check-list');
  box.innerHTML = '';
  checkBad = [];
  $('check-disable-bad').disabled = true;
  $('check-delete-bad').disabled = true;
  $('check-status').textContent = '正在检测…';

  const key = $('check-key').value.trim() || '测试';
  checkSource = new EventSource('/api/sources/check/stream?' + q({ key: key }));
  let done = 0, total = 0;

  checkSource.addEventListener('start', (e) => {
    total = JSON.parse(e.data).total;
    $('check-status').textContent = `正在检测 ${total} 个书源…（结果陆续出现）`;
  });

  checkSource.addEventListener('result', (e) => {
    const d = JSON.parse(e.data);
    done++;
    $('check-status').textContent = `已检测 ${done}/${total} 个 · 失效 ${checkBad.length} 个`;
    if (d.status === 'bad') checkBad.push(d.sourceUrl);

    const badge = d.status === 'ok'
      ? `<span class="si-badge">可用 ${d.bookCount} 本</span>`
      : d.status === 'empty'
        ? '<span class="si-badge si-warn">连接正常但无结果</span>'
        : '<span class="si-badge si-bad">失效</span>';

    const row = document.createElement('div');
    row.className = 'store-item';
    row.innerHTML = `
      <div class="si-main">
        <div class="si-name">${esc(d.sourceName)} ${badge}</div>
        <div class="si-url">${esc(d.sourceUrl)}</div>
        ${d.error ? `<div class="si-desc">${esc(d.error)}</div>`
          : (d.firstBook ? `<div class="si-desc">首本：${esc(d.firstBook)}</div>` : '')}
      </div>
      <span class="muted">${d.elapsedMs}ms</span>`;
    // 失效的排前面，方便先看问题源
    if (d.status === 'bad') box.prepend(row); else box.appendChild(row);
  });

  checkSource.addEventListener('done', (e) => {
    const d = JSON.parse(e.data);
    $('check-status').textContent =
      `检测完成：可用 ${d.okCount} · 可疑 ${d.emptyCount} · 失效 ${d.badCount}` +
      `（共 ${d.total} 个，${(d.elapsedMs / 1000).toFixed(1)}s）`;
    $('check-disable-bad').disabled = checkBad.length === 0;
    $('check-delete-bad').disabled = checkBad.length === 0;
    checkSource.close();
    checkSource = null;
  });

  checkSource.addEventListener('error', () => {
    if (checkSource) { checkSource.close(); checkSource = null; }
    $('check-status').textContent = '检测中断';
  });
}

async function batchSources(action, urls) {
  if (!urls.length) return;
  try {
    const r = await api('/sources/batch?' + q({ action: action, urls: urls.join(',') }),
      { method: 'POST' });
    toast(r.message || r.error || '');
    await loadSources();
    $('check-disable-bad').disabled = true;
    $('check-delete-bad').disabled = true;
  } catch (e) {
    toast('操作失败: ' + e.message);
  }
}

$('source-check').addEventListener('click', () => {  $('check-view').classList.add('open');
  if (!$('check-list').children.length) startCheck();
});
$('check-back').addEventListener('click', () => {
  $('check-view').classList.remove('open');
  if (checkSource) { checkSource.close(); checkSource = null; }
});
$('check-run').addEventListener('click', startCheck);
$('check-disable-bad').addEventListener('click', () => batchSources('disable', checkBad));
$('check-delete-bad').addEventListener('click', () => {
  if (!confirm(`删除 ${checkBad.length} 个失效书源？此操作不可撤销。`)) return;
  batchSources('delete', checkBad);
});

/* ---------------------------------------------------------------- 书源仓库
 * 从 Yiove 书源仓库搜索并导入书源。
 * 那个站是单页应用，没法当订阅源用；但它的后端接口可以直接调。
 */
let storeType = 'book-sources';

async function storeSearch() {
  const key = $('store-key').value.trim();
  const box = $('store-list');
  $('store-status').textContent = '搜索中…';
  box.innerHTML = '';
  try {
    const data = await api('/store/search?' + q({ key: key, type: storeType, page: 1 }));
    if (!data.ok) {
      $('store-status').textContent = '搜索失败：' + (data.error || '');
      return;
    }
    const items = data.items || [];
    $('store-status').textContent =
      `共 ${data.total} 条${key ? `（关键词「${key}」）` : ''}，显示前 ${items.length} 条`;
    if (!items.length) {
      box.innerHTML = '<div class="empty">没有找到</div>';
      return;
    }
    box.innerHTML = items.map((it) => `
      <div class="store-item">
        <div class="si-main">
          <div class="si-name">
            ${esc(it.name)}
            ${it.valid === false ? '<span class="si-badge si-bad">已失效</span>' : ''}
            ${storeType === 'book-source-collections' ? `<span class="si-badge">合集</span>` : ''}
          </div>
          ${it.url ? `<div class="si-url">${esc(it.url)}</div>` : ''}
          ${it.description ? `<div class="si-desc">${esc(it.description)}</div>` : ''}
        </div>
        <button class="primary" data-import="${esc(it.id)}">
          ${storeType === 'book-source-collections' ? '导入合集' : '导入'}
        </button>
      </div>`).join('');

    box.querySelectorAll('[data-import]').forEach((b) =>
      b.addEventListener('click', async () => {
        const orig = b.textContent;
        b.disabled = true;
        b.textContent = '导入中…';
        try {
          const r = await api('/store/import?' + q({
            id: b.dataset.import,
            type: storeType === 'book-source-collections' ? 'book-source-collection' : 'book-source',
          }), { method: 'POST' });
          toast(r.message || r.error || (r.ok ? '导入完成' : '导入失败'));
          if (r.ok) await loadSources();
        } catch (e) {
          toast('导入失败: ' + e.message);
        }
        b.disabled = false;
        b.textContent = orig;
      }));
  } catch (e) {
    $('store-status').textContent = '搜索失败：' + e.message;
  }
}

$('store-back').addEventListener('click', () => $('store-view').classList.remove('open'));
$('source-store').addEventListener('click', () => {
  $('store-view').classList.add('open');
  if (!$('store-list').children.length) storeSearch();
});
$('store-search').addEventListener('click', storeSearch);
$('store-key').addEventListener('keydown', (e) => { if (e.key === 'Enter') storeSearch(); });
$('store-type').addEventListener('click', (e) => {
  const btn = e.target.closest('[data-stype]');
  if (!btn) return;
  storeType = btn.dataset.stype;
  $('store-type').querySelectorAll('.seg-btn').forEach((b) =>
    b.classList.toggle('active', b.dataset.stype === storeType));
  storeSearch();
});

/* ---------------------------------------------------------------- 主题 */
// 阅读器工具栏的「主题」按钮：切换日间/夜间配色（详细调节在「主题」页）
$('reader-theme').addEventListener('click', toggleDayNight);

// 阅读设置里的入口：关掉面板，跳到「主题」页
$('set-open-theme').addEventListener('click', () => {
  $('settings-panel').classList.remove('open');
  $('reader').classList.remove('open');
  switchView('theme');
});

/**
 * 日/夜属性。
 *
 * 原先这里还有一套「明亮/护眼/夜间」的内置三主题（靠 data-theme 切换预设变量），
 * 但它与自定义配色会互相覆盖。现在统一到主题系统：
 * 那三套配色已作为预设出现在「主题」页，这里只负责昼夜标记。
 */
function applyTheme() {
  if (themeState.isNight) document.documentElement.setAttribute('data-theme', 'night');
  else document.documentElement.removeAttribute('data-theme');
}

/** 切换日间/夜间并立即生效 */
async function toggleDayNight() {
  await patchTheme({ isNightTheme: !themeState.isNight });
  applyTheme();
  renderThemeEditor();
  toast(themeState.isNight ? '已切到夜间配色' : '已切到日间配色');
}

/* ---------------------------------------------------------------- 启动 */
(async function boot() {
  applyTheme();
  await loadConfig();
  await loadTheme();
  bindSettings();
  await loadSources();
  await loadShelf();
  try {
    const h = await api('/health');
    $('stat').textContent = `书源 ${h.sources} · 书架 ${h.books}`;
  } catch (e) { /* 忽略 */ }
})();
