/**
 * 三端样式全量逐页审计（style-full-audit）
 *
 * 目标：不抽样，逐页扫描 admin-vue / consumer-mp / merchant-mp 的全部 .vue，
 * 输出「每页 × 每类问题」矩阵 + 完整问题枚举清单，作为样式收敛改造的工单。
 *
 * 运行：node scripts/style-full-audit.mjs
 * 输出：docs/style-consistency-full-audit-<date>.md / .json
 */
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATE = new Date().toISOString().slice(0, 10);

const APPS = {
  'admin-vue': {
    dir: 'clients/admin-vue/src',
    pagesDir: 'views',
    globalCss: ['styles/main.css', 'styles/chart-panel.css'],
    isAdmin: true,
  },
  'consumer-mp': {
    dir: 'clients/consumer-mp/src',
    pagesDir: 'pages',
    globalCss: [],
    appVue: true,
    pagesJson: true,
  },
  'merchant-mp': {
    dir: 'clients/merchant-mp/src',
    pagesDir: 'pages',
    globalCss: [],
    appVue: true,
    pagesJson: true,
  },
};

// —— 通用工具 ———————————————————————————————————————————————
const walk = (dir, ext = '.vue', out = []) => {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, ext, out);
    else if (name.endsWith(ext)) out.push(p);
  }
  return out;
};
const read = (p) => { try { return readFileSync(p, 'utf8'); } catch { return ''; } };
const rel = (p) => relative(join(ROOT), p).replaceAll('\\', '/');

const HEX = /#[0-9a-fA-F]{3,8}\b/g;
const VAR_OR_HEX = /var\([^()]*\)|#[0-9a-fA-F]{3,8}\b/g; // var(...) 整体吞掉，兜底色不算裸色

/** 把文件拆成 template / script / style 三段 */
function splitSfc(src) {
  const styleBlocks = [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]);
  const scriptBlock = (src.match(/<script[^>]*>([\s\S]*?)<\/script>/) || [])[1] || '';
  const tplOpen = src.indexOf('<template>');
  const tplClose = src.lastIndexOf('</template>');
  const template = tplOpen >= 0 && tplClose > tplOpen ? src.slice(tplOpen, tplClose) : src;
  return { template, script: scriptBlock, style: styleBlocks.join('\n'), styleBlocks };
}

/** 统计 style 文本中的裸 hex（var() 内兜底值不计）与兜底 hex */
function colorStats(styleText) {
  const bare = new Map(); let fallback = 0;
  let m;
  const re = new RegExp(VAR_OR_HEX.source, 'g');
  while ((m = re.exec(styleText))) {
    if (m[0].startsWith('var(')) {
      const inner = m[0].match(HEX);
      if (inner) fallback += inner.length;
    } else {
      const v = m[0].toLowerCase();
      bare.set(v, (bare.get(v) || 0) + 1);
    }
  }
  return { bare, fallback };
}

const radiusStats = (styleText) => {
  const out = new Map();
  for (const m of styleText.matchAll(/border-radius\s*:\s*([^;}\n]+)/g)) {
    if (!m[1].includes('var(')) out.set(m[1].trim(), (out.get(m[1].trim()) || 0) + 1);
  }
  return out;
};

// —— 全局已定义 CSS 变量集合（用于找「用了但谁都没定义」的变量）——
const collectDefinedVars = (texts) => {
  const set = new Set();
  for (const t of texts) {
    for (const m of t.matchAll(/(--[\w-]+)\s*:/g)) set.add(m[1]);
    for (const m of t.matchAll(/setProperty\(\s*'(--[\w-]+)'/g)) set.add(m[1]);
  }
  return set;
};
const VAR_WHITELIST = /^(--el-|--uni-|--window-|--status-bar|--ios-|--android-|--safe-area)/;

// —— 单文件审计 —————————————————————————————————————————————
function auditFile(absPath, app, globalDefinedVars, globalClassSet) {
  const src = read(absPath);
  const { template, script, style } = splitSfc(src);
  const res = {
    file: rel(absPath), loc: src.split('\n').length,
    hexBare: 0, hexBareTop: {}, hexFallback: 0,
    radiusLit: 0, radiusLitVals: {},
    inlineStyleHex: 0, styleAttrAll: 0,
    btnNative: 0, btnApp: 0,
    emptyCmp: 0, emptyHw: 0, errCmp: 0,
    undefVars: [], ghostClasses: [],
    pageCard: 0, pcHead: 0, shadowNever: 0, shadowHover: 0, chartHexScript: 0,
    usesChart: false,
  };

  // 全文件统计裸 hex：覆盖 style / script（图表色板、JS 动态样式）/ 模板内联样式
  const wholeStats = colorStats(src);
  res.hexBare = [...wholeStats.bare.values()].reduce((a, b) => a + b, 0);
  res.hexBareTop = Object.fromEntries([...wholeStats.bare.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5));
  res.hexFallback = wholeStats.fallback;

  const radii = radiusStats(src);
  res.radiusLit = [...radii.values()].reduce((a, b) => a + b, 0);
  res.radiusLitVals = Object.fromEntries([...radii.entries()]);

  // script 里的裸色（图表色板等）
  if (app.isAdmin) {
    const scriptColors = colorStats(script);
    res.chartHexScript = [...scriptColors.bare.values()].reduce((a, b) => a + b, 0);
  }

  for (const m of template.matchAll(/\sstyle="([^"]*)"/g)) {
    res.styleAttrAll++;
    if (m[1].includes('#')) res.inlineStyleHex++;
  }

  res.btnNative = (template.match(/<button[\s>]/g) || []).length;
  res.btnApp = (template.match(/<app-button[\s>/]/gi) || []).length;
  res.emptyCmp = (template.match(/<empty-state[\s>/]/g) || []).length;
  res.errCmp = (template.match(/<error-state[\s>/]/g) || []).length;

  // 手写空状态：模板用 .empty 类 或 style 里自定义 .empty-title/.empty-desc/.empty-hint
  const hwInTpl = /class="[^"]*\bempty(-title|-desc|-hint)?\b[^"]*"/.test(template);
  const hwInStyle = /\.(empty-title|empty-desc|empty-hint|empty-state-text)\s*[{,]/.test(style) && !absPath.includes('empty-state');
  res.emptyHw = hwInTpl || hwInStyle ? 1 : 0;

  if (app.isAdmin) {
    res.pageCard = (template.match(/page-card[\s"']/g) || []).length;
    res.pcHead = (template.match(/page-card-head/g) || []).length;
    res.shadowNever = (template.match(/shadow="never"/g) || []).length;
    res.shadowHover = (template.match(/shadow="hover"/g) || []).length;
    res.usesChart = /<ChartBox|<ChartPanel/.test(template);
  }

  // 用了但全 app 没人定义的 CSS 变量
  const used = new Set();
  for (const m of src.matchAll(/var\(\s*(--[\w-]+)/g)) used.add(m[1]);
  res.undefVars = [...used].filter((v) => !globalDefinedVars.has(v) && !VAR_WHITELIST.test(v));

  // 模板静态 class 里「全 app 都找不到定义」的类（已知 = 全局样式 + 本文件 scoped + uni/el 前缀）
  const usedCls = new Set();
  for (const m of template.matchAll(/\sclass="([^"{}]*)"/g)) {
    for (const c of m[1].split(/\s+/)) if (c) usedCls.add(c);
  }
  const localClasses = new Set();
  for (const m of style.matchAll(/\.([a-zA-Z][\w-]*)/g)) localClasses.add(m[1]);
  for (const m of script.matchAll(/class:\s*'([\w-]+)'/g)) localClasses.add(m[1]);
  const knownTags = new Set(['view', 'text', 'button', 'image', 'input', 'scroll-view', 'swiper', 'block', 'template', 'video', 'map', 'canvas', 'cover-view']);
  res.ghostClasses = [...usedCls].filter(
    (c) => !globalClassSet.has(c) && !localClasses.has(c) && !knownTags.has(c) && !c.startsWith('uni-') && !c.startsWith('el-') && !/^(is|has)-/.test(c)
  );
  return res;
}

// —— 主流程 ————————————————————————————————————————————————
const result = { date: DATE, apps: {}, copySync: [] };

for (const [appName, app] of Object.entries(APPS)) {
  const srcDir = join(ROOT, app.dir);
  const vueFiles = walk(srcDir);

  // 全局样式/变量来源
  const globalTexts = [];
  for (const g of app.globalCss) globalTexts.push(read(join(srcDir, g)));
  if (app.appVue) globalTexts.push(read(join(srcDir, 'App.vue')));
  globalTexts.push(read(join(ROOT, 'packages/shared-uni/src/theme.css')));
  if (app.isAdmin) globalTexts.push(read(join(srcDir, 'stores/settings.ts')));
  const definedVars = collectDefinedVars([...globalTexts, ...vueFiles.map((f) => read(f))]);

  // 全局类名集合（ghost class 判定用）
  const globalClassSet = new Set();
  for (const t of globalTexts) for (const m of t.matchAll(/\.([a-zA-Z][\w-]*)/g)) globalClassSet.add(m[1]);

  const pagesRoot = join(srcDir, app.pagesDir);
  const pageFiles = vueFiles.filter((f) => f.startsWith(pagesRoot));
  const otherFiles = vueFiles.filter((f) => !f.startsWith(pagesRoot));

  const auditAll = (files) => files.map((f) => auditFile(f, app, definedVars, globalClassSet));
  const pages = auditAll(pageFiles);
  const others = auditAll(otherFiles);

  // —— 页面清单完整性：路由/页面注册 vs 实际文件 ——
  let registry = { registered: [], missingFiles: [], orphans: [], globalStyle: null, tabBar: null, pageOverrides: [] };
  if (app.isAdmin) {
    const routerSrc = read(join(srcDir, 'router/index.ts'));
    registry.registered = [...routerSrc.matchAll(/@\/views\/([\w/.-]+\.vue)/g)].map((m) => `views/${m[1]}`);
    const regSet = new Set(registry.registered);
    registry.orphans = pageFiles.map(rel).filter((f) => !regSet.has(f.replace(/^clients\/admin-vue\/src\//, '')));
  } else {
    const pj = JSON.parse(read(join(srcDir, 'pages.json')));
    registry.globalStyle = pj.globalStyle || null;
    registry.tabBar = pj.tabBar ? { color: pj.tabBar.color, selectedColor: pj.tabBar.selectedColor, backgroundColor: pj.tabBar.backgroundColor } : null;
    const entries = [];
    for (const p of pj.pages || []) entries.push({ ...p, path: p.path.startsWith('pages/') ? p.path : `pages/${p.path}` });
    for (const sp of pj.subPackages || pj.subpackages || []) {
      for (const p of sp.pages || []) {
        const root = sp.root.startsWith('pages/') ? sp.root : `pages/${sp.root}`;
        entries.push({ ...p, path: `${root}/${p.path}`.replaceAll('//', '/') });
      }
    }
    registry.registered = entries.map((e) => `${e.path}.vue`);
    const regSet = new Set(registry.registered);
    for (const e of registry.registered) {
      if (!existsSync(join(srcDir, e))) registry.missingFiles.push(e);
    }
    registry.orphans = pageFiles.map(rel).filter((f) => !regSet.has(f.replace(/^clients\/[^/]+\/src\//, '')));
    for (const e of entries) {
      const styleOverrides = {};
      for (const k of ['navigationStyle', 'backgroundColor', 'navigationBarBackgroundColor', 'navigationBarTextStyle']) {
        if (e[k] && (!registry.globalStyle || registry.globalStyle[k] !== e[k])) styleOverrides[k] = e[k];
      }
      if (Object.keys(styleOverrides).length) registry.pageOverrides.push({ page: e.path, ...styleOverrides });
    }
  }

  result.apps[appName] = { pages, others, registry, totals: sumTotals(pages, others) };
}

function sumTotals(pages, others) {
  const all = [...pages, ...others];
  const t = { pages: pages.length, otherFiles: others.length, hexBare: 0, hexFallback: 0, radiusLit: 0, inlineStyleHex: 0, btnNative: 0, btnApp: 0, emptyCmp: 0, emptyHw: 0, errCmp: 0, chartHexScript: 0 };
  for (const f of all) {
    t.hexBare += f.hexBare; t.hexFallback += f.hexFallback; t.radiusLit += f.radiusLit;
    t.inlineStyleHex += f.inlineStyleHex; t.btnNative += f.btnNative; t.btnApp += f.btnApp;
    t.emptyCmp += f.emptyCmp; t.emptyHw += f.emptyHw; t.errCmp += f.errCmp; t.chartHexScript += f.chartHexScript;
  }
  return t;
}

// —— 共享组件拷贝一致性 ————————————————————————————————————
/** 简单 LCS 行级 diff：返回不同的行数（含增/删/改） */
function lcsDiffCount(a, b) {
  const m = a.length, n = b.length;
  const dp = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));
  for (let i = 1; i <= m; i++)
    for (let j = 1; j <= n; j++)
      dp[i][j] = a[i - 1] === b[j - 1] ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1]);
  return m + n - 2 * dp[m][n];
}

/** 去掉拷贝副本头部的同步提示注释块与 Canonical 行，只比真实代码 */
const stripCopyComments = (lines) => {
  const out = [];
  let inHeadComment = true;
  for (const l of lines) {
    const t = l.trim();
    if (inHeadComment && (t === '' || t === '<!--' || t === '-->' || t.includes('Keep this file identical') || t.includes('Canonical:'))) continue;
    inHeadComment = false;
    out.push(l);
  }
  return out;
};

const CANON = join(ROOT, 'packages/shared-uni/src/components');
for (const mp of ['consumer-mp', 'merchant-mp']) {
  const localDir = join(ROOT, `clients/${mp}/src/components`);
  if (!existsSync(localDir)) continue;
  for (const name of readdirSync(CANON)) {
    const local = join(localDir, name);
    if (!existsSync(local)) continue;
    const a = stripCopyComments(read(join(CANON, name)).split('\n'));
    const b = stripCopyComments(read(local).split('\n'));
    const diff = lcsDiffCount(a, b);
    result.copySync.push({ component: name, app: mp, identical: diff === 0, diffLines: diff });
  }
}

// —— Markdown 报告 ————————————————————————————————————————————
const shortFile = (f, app) => f.split(`/src/`)[1] || f;
const fmt = (n) => (n === 0 ? '·' : String(n));

function buildMarkdown(result) {
  const L = [];
  L.push(`# 三端样式全量逐页审计（${result.date}）`);
  L.push('');
  L.push(`> 生成方式：\`node scripts/style-full-audit.mjs\`（全量逐页扫描，无抽样，可重复执行）`);
  L.push(`> 机器可读数据：\`style-consistency-full-audit-${result.date}.json\`（本目录）`);
  L.push(`> 色值口径：「裸 hex」= 不在 var() 兜底位里的色值（含 style/script/内联，全部要治理）；「兜底」= var(--token, #hex) 形式（token 失效才生效，低优先级）。`);
  L.push('');

  // 一、总量看板
  L.push('## 一、总量看板');
  L.push('');
  L.push('| 指标 | admin-vue | consumer-mp | merchant-mp |');
  L.push('|---|---|---|---|');
  const row = (label, get) => L.push(`| ${label} | ${get(result.apps['admin-vue'])} | ${get(result.apps['consumer-mp'])} | ${get(result.apps['merchant-mp'])} |`);
  row('页面数', (a) => a.totals.pages);
  row('组件/布局文件数', (a) => a.totals.otherFiles);
  row('裸 hex 色（待治理）', (a) => a.totals.hexBare);
  row('var() 兜底 hex', (a) => a.totals.hexFallback);
  row('字面圆角（非 token）', (a) => a.totals.radiusLit);
  row('内联 style 带色值', (a) => a.totals.inlineStyleHex);
  row('原生 <button>', (a) => a.totals.btnNative);
  row('app-button 使用', (a) => a.totals.btnApp);
  row('手写空状态页数', (a) => a.totals.emptyHw);
  row('empty-state 使用', (a) => a.totals.emptyCmp);
  row('error-state 使用', (a) => a.totals.errCmp);
  row('script 图表裸色（admin 专属）', (a) => a.totals.chartHexScript);
  L.push('');

  // 二~四、逐页矩阵
  const sections = [
    ['admin-vue', 'admin-vue（管理后台 70 页）'],
    ['consumer-mp', 'consumer-mp（消费者小程序 24 页）'],
    ['merchant-mp', 'merchant-mp（商家小程序 23 页）'],
  ];
  for (const [key, title] of sections) {
    const app = result.apps[key];
    L.push(`## ${title}逐页矩阵`);
    L.push('');
    if (key === 'admin-vue') {
      L.push('| 页面 | 裸hex | 兜底 | 字面圆角 | 内联色 | 原生btn | page-card | 图表 | script裸色 |');
      L.push('|---|---|---|---|---|---|---|---|---|');
      for (const p of app.pages) {
        L.push(`| ${shortFile(p.file)} | ${fmt(p.hexBare)} | ${fmt(p.hexFallback)} | ${fmt(p.radiusLit)} | ${fmt(p.inlineStyleHex)} | ${fmt(p.btnNative)} | ${p.pageCard > 0 ? '✓' : '✗'} | ${p.usesChart ? '✓' : '·'} | ${fmt(p.chartHexScript)} |`);
      }
    } else {
      L.push('| 页面 | 裸hex | 兜底 | 字面圆角 | 内联色 | 原生btn | app-button | empty-state | 手写空态 | 未定义变量 |');
      L.push('|---|---|---|---|---|---|---|---|---|---|');
      for (const p of app.pages) {
        L.push(`| ${shortFile(p.file)} | ${fmt(p.hexBare)} | ${fmt(p.hexFallback)} | ${fmt(p.radiusLit)} | ${fmt(p.inlineStyleHex)} | ${fmt(p.btnNative)} | ${fmt(p.btnApp)} | ${fmt(p.emptyCmp)} | ${p.emptyHw ? '⚠' : '·'} | ${p.undefVars.length ? p.undefVars.join(',') : '·'} |`);
      }
    }
    L.push('');
  }

  // 五、组件/布局文件（有问题的才列）
  L.push('## 五、组件与布局文件（仅列有问题的）');
  L.push('');
  L.push('| 端 | 文件 | 裸hex | 字面圆角 | 原生btn | 未定义变量 |');
  L.push('|---|---|---|---|---|---|');
  for (const [key, app] of Object.entries(result.apps)) {
    for (const f of app.others) {
      if (f.hexBare > 0 || f.radiusLit > 0 || f.btnNative > 0 || f.undefVars.length) {
        L.push(`| ${key} | ${shortFile(f.file)} | ${fmt(f.hexBare)} | ${fmt(f.radiusLit)} | ${fmt(f.btnNative)} | ${f.undefVars.length ? f.undefVars.join(',') : '·'} |`);
      }
    }
  }
  L.push('');

  // 六、页面注册完整性
  L.push('## 六、页面注册完整性（防漏页）');
  L.push('');
  for (const [key, app] of Object.entries(result.apps)) {
    const r = app.registry;
    L.push(`- **${key}**：实际页面文件 ${app.totals.pages} 个；注册/路由引用 ${r.registered.length} 个；缺失文件 ${r.missingFiles.length}；未注册孤儿 ${r.orphans.length}${r.orphans.length ? '：' + r.orphans.join('、') : ''}`);
  }
  L.push('');
  for (const key of ['consumer-mp', 'merchant-mp']) {
    const r = result.apps[key].registry;
    if (r.globalStyle) {
      L.push(`**${key} globalStyle**：${JSON.stringify(r.globalStyle)}；tabBar：${JSON.stringify(r.tabBar)}`);
      if (r.pageOverrides.length) {
        L.push('');
        L.push('| 覆盖页 | 差异配置 |');
        L.push('|---|---|');
        for (const o of r.pageOverrides) {
          const { page, ...rest } = o;
          L.push(`| ${page} | ${JSON.stringify(rest)} |`);
        }
      }
      L.push('');
    }
  }

  // 七、共享组件拷贝一致性
  L.push('## 七、共享组件本地拷贝一致性（对比 packages/shared-uni/src/components）');
  L.push('');
  L.push('| 组件 | consumer-mp | merchant-mp |');
  L.push('|---|---|---|');
  const byComp = {};
  for (const c of result.copySync) (byComp[c.component] ||= {})[c.app] = c;
  for (const [comp, apps] of Object.entries(byComp)) {
    const cell = (a) => (apps[a] ? (apps[a].identical ? '一致' : `⚠ 差异 ${apps[a].diffLines} 行`) : '（无本地拷贝）');
    L.push(`| ${comp} | ${cell('consumer-mp')} | ${cell('merchant-mp')} |`);
  }
  L.push('');

  // 八、未定义 CSS 变量（bug 级）
  L.push('## 八、使用了但全 app 未定义的 CSS 变量（bug 级，恒走 fallback）');
  L.push('');
  for (const [key, app] of Object.entries(result.apps)) {
    for (const f of [...app.pages, ...app.others]) {
      if (f.undefVars.length) L.push(`- ${key} · ${shortFile(f.file)}：${f.undefVars.join('、')}`);
    }
  }
  L.push('');

  // 九、幽灵类（需人工确认）
  L.push('## 九、模板引用但找不到定义的类（需人工确认是否死类/动态拼接）');
  L.push('');
  for (const [key, app] of Object.entries(result.apps)) {
    for (const f of [...app.pages, ...app.others]) {
      if (f.ghostClasses.length) L.push(`- ${key} · ${shortFile(f.file)}：${f.ghostClasses.join('、')}`);
    }
  }
  L.push('');
  // 十、改造切片工单（零遗漏映射）
  L.push('## 十、改造切片工单（每片命中页面全清单，改一页销一页）');
  L.push('');
  const list = (app, pred) => result.apps[app].pages.filter(pred).map((p) => `${shortFile(p.file)}(${p.btnNative})`).join('、');
  const fileList = (app, pred) => result.apps[app].pages.filter(pred).map((p) => shortFile(p.file)).join('、');

  L.push('**P0 · bug 级（恒错/依赖断裂）**');
  L.push('');
  L.push('- 未定义 CSS 变量、幽灵类：见 §八/§九 全量清单（含 merchant orders 裸导出按钮 → 见 §九 幽灵类 `export-btn`）。');
  L.push('- merchant-mp package.json 补声明 `@aicabinet/shared-uni`。');
  L.push('- 共享组件拷贝漂移回齐：见 §七（app-button/app-nav-bar/error-state 两端均落后共享包）。');
  L.push('');
  L.push('**P1 · 两个小程序互相拉齐（不向后台看齐，遵循小程序规范）**');
  L.push('');
  L.push(`1. 按钮统一到 app-button（原生 <button> 清除）：`);
  L.push(`   - consumer-mp：${list('consumer-mp', (p) => p.btnNative > 0) || '无'}`);
  L.push(`   - merchant-mp：${list('merchant-mp', (p) => p.btnNative > 0) || '无'}`);
  L.push(`2. 空状态统一到 empty-state（手写空状态清除）：`);
  L.push(`   - consumer-mp：${fileList('consumer-mp', (p) => p.emptyHw > 0) || '无'}`);
  L.push(`   - merchant-mp：${fileList('merchant-mp', (p) => p.emptyHw > 0) || '无'}`);
  L.push(`3. consumer-mp error-state 组件 0 使用 → 接入或删除（merchant 已有 14 处使用可参照）。`);
  L.push('4. 页面底色/导航口径统一：按 §六 pageOverrides 表逐页核对（nearby #f5f7f6 等私有底色收敛为 token 或有意豁免注释）。');
  L.push(`5. 裸 hex 按密度治理（≥3 处的页面优先）：`);
  for (const key of ['consumer-mp', 'merchant-mp']) {
    const hot = result.apps[key].pages.filter((p) => p.hexBare >= 3).sort((a, b) => b.hexBare - a.hexBare);
    L.push(`   - ${key}：${hot.map((p) => `${shortFile(p.file)}(${p.hexBare})`).join('、') || '无'}`);
  }
  L.push(`6. 字面圆角接 token（≥3 处的页面优先）：`);
  for (const key of ['consumer-mp', 'merchant-mp']) {
    const hot = result.apps[key].pages.filter((p) => p.radiusLit >= 3).sort((a, b) => b.radiusLit - a.radiusLit);
    L.push(`   - ${key}：${hot.map((p) => `${shortFile(p.file)}(${p.radiusLit})`).join('、') || '无'}`);
  }
  L.push('');
  L.push('**P2 · 管理后台收尾**');
  L.push('');
  const adminCharts = result.apps['admin-vue'].pages.filter((p) => p.usesChart || p.chartHexScript > 0);
  L.push(`1. ECharts 迁移（替代手写 SVG）：${adminCharts.map((p) => `${shortFile(p.file)}(script裸色${p.chartHexScript})`).join('、')}`);
  L.push('   - ChartBox 保留加载/错误/空态壳，内部换 ECharts；主题色值从 CSS 变量读取，跟随暗色/主题切换；迁完删 utils/charts.ts SVG 体系。');
  const adminBtn = result.apps['admin-vue'].pages.filter((p) => p.btnNative > 0).sort((a, b) => b.btnNative - a.btnNative);
  L.push(`2. 原生 <button> 归一到 el-button：${adminBtn.map((p) => `${shortFile(p.file)}(${p.btnNative})`).join('、')}`);
  const adminRadius = result.apps['admin-vue'].pages.filter((p) => p.radiusLit >= 3).sort((a, b) => b.radiusLit - a.radiusLit);
  L.push(`3. 字面圆角接 --radius-*：${adminRadius.map((p) => `${shortFile(p.file)}(${p.radiusLit})`).join('、') || '无'}（其余 1-2 处的随页面顺手改）`);
  const adminHot = result.apps['admin-vue'].pages.filter((p) => p.hexBare >= 5).sort((a, b) => b.hexBare - a.hexBare);
  L.push(`4. 裸 hex 收敛（≥5 处优先）：${adminHot.map((p) => `${shortFile(p.file)}(${p.hexBare})`).join('、')}`);
  L.push('5. 结构性偏离：DevOpsHubView 对齐 page-card 骨架（§矩阵中 page-card=✗ 的页面逐一核对是否合理特例）。');
  L.push('');
  L.push('**机制 · 防再漂移**');
  L.push('');
  L.push('- 本审计脚本纳入 CI 门禁：各端 totals 不高于基线（基线=本次 JSON），新增裸 hex/裸圆角/原生按钮即失败。');
  L.push('- 拷贝组件一致性校验（§七）纳入 CI：identical 必须为 true。');
  L.push('- stylelint 禁止新增裸 hex 与字面 border-radius（白名单 theme.css/main.css/App.vue）。');
  L.push('');
  return L.join('\n');
}

writeFileSync(join(ROOT, `docs/style-consistency-full-audit-${DATE}.json`), JSON.stringify(result, null, 2));
writeFileSync(join(ROOT, `docs/style-consistency-full-audit-${DATE}.md`), buildMarkdown(result));
console.log(`OK 已生成 docs/style-consistency-full-audit-${DATE}.md / .json`);
for (const [name, app] of Object.entries(result.apps)) {
  console.log(`${name}: 页面 ${app.totals.pages} 个, 其他文件 ${app.totals.otherFiles} 个, 裸hex ${app.totals.hexBare}, 字面圆角 ${app.totals.radiusLit}, 原生按钮 ${app.totals.btnNative}, 手写空状态 ${app.totals.emptyHw}, 孤儿页 ${app.registry.orphans.length}`);
}
