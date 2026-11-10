#!/usr/bin/env node
/**
 * 门禁：两端小程序「声明了下拉刷新但没有处理函数」。
 *
 * 背景（2026-11-10）：`pages.json` 里声明 `style.enablePullDownRefresh: true` 后，
 * 若页面没有注册 `onPullDownRefresh`，用户下拉只会看到一个永不收回的转圈指示器。
 * merchant-mp 的 messages / wallet / line-wallet 曾同时中招（同族），且新增页面
 * 照着 pages.json 复制粘贴极易复发，故做成静态门禁。
 *
 * 判据（贴真实责任边界）：
 *  - 只看自己声明了 enablePullDownRefresh 的页面；没声明的页面不具备下拉能力，不在管辖内。
 *    （E-R3-3②：globalStyle.enablePullDownRefresh=true 时声明是**全局**的 —— 全部页面都必须
 *    注册 onPullDownRefresh，页面级显式 `false` 才能豁免，否则声明移入 globalStyle 整批漏管。）
 *  - 只认**页面文件本身**的注册：小程序端组件里的 onPullDownRefresh 不会触发，
 *    外壳页（如 wallet.vue 只渲染 <WalletPage>）必须自己在页面层注册。
 *  - 扫源码前先抹掉注释（换等长空格保行号），避免注释里的示例写法造成假绿。
 *  - E-R3-3①：解析出的页面声明数为 0 ⇒ 直接 FAIL —— pages.json 结构漂移（pages/subPackages
 *    键改名）会让本门禁从「查 30 页」退化成「查 0 页」的**静默假绿**，防锚必须显式。
 *
 * 用法：node scripts/check-mp-pull-refresh.mjs [--print]
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');
const MINI_PROGRAMS = ['merchant-mp', 'consumer-mp'];
const PRINT = process.argv.includes('--print');

/** 把注释替换成等长空白，保留换行与偏移（行号不乱）。 */
function maskComments(src) {
  let out = src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  out = out.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
  out = out.replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length));
  return out;
}

/** pages.json 允许注释与尾逗号（uni-app 方言），先清洗再解析。 */
function loadPagesJson(file) {
  const txt = fs
    .readFileSync(file, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/,(\s*[}\]])/g, '$1');
  return JSON.parse(txt);
}

function collectPages(pj) {
  const out = [];
  for (const p of pj.pages || []) out.push({ path: p.path, style: p.style || {} });
  for (const sp of pj.subPackages || []) {
    for (const p of sp.pages || []) {
      out.push({ path: `${sp.root}/${p.path}`, style: p.style || {} });
    }
  }
  return out;
}

const failures = [];
let declared = 0;

for (const cmp of MINI_PROGRAMS) {
  const srcDir = path.join(ROOT, 'clients', cmp, 'src');
  const pagesJsonPath = path.join(srcDir, 'pages.json');
  if (!fs.existsSync(pagesJsonPath)) {
    failures.push(`${cmp}: 找不到 src/pages.json`);
    continue;
  }
  const pj = loadPagesJson(pagesJsonPath);
  const entries = collectPages(pj);
  if (PRINT) console.log(`\n=== ${cmp} ===`);

  // E-R3-3①：结构漂移防锚。pages/subPackages 键被改名/挪走 ⇒ 解析出 0 个页面 ⇒
  // 旧版从「查 30 页」静默退化成「OK：0 个」。0 个声明本身就是异常，必须显式红。
  if (entries.length === 0) {
    failures.push(
      `${cmp}: 解析到 0 个页面声明，疑似结构漂移（pages/subPackages 键变化？）—— 拒绝按空集假绿`
    );
    continue;
  }
  // E-R3-3②：globalStyle 全局声明收编。声明移入 globalStyle 后所有页面都有下拉能力，
  // 旧版只收页面级 style ⇒ 整批漏管。页面级显式 `false` 是覆盖（uni-app 页面样式优先），照旧豁免。
  const globalPullDown = pj.globalStyle?.enablePullDownRefresh === true;

  for (const entry of entries) {
    const enabled =
      'enablePullDownRefresh' in entry.style
        ? entry.style.enablePullDownRefresh === true
        : globalPullDown;
    if (!enabled) continue;
    declared += 1;
    const vuePath = path.join(srcDir, `${entry.path}.vue`);
    if (!fs.existsSync(vuePath)) {
      failures.push(`${cmp}/${entry.path}: 声明了下拉刷新但源文件不存在（${vuePath}）`);
      continue;
    }
    const code = maskComments(fs.readFileSync(vuePath, 'utf8'));
    // 只认注册：`onPullDownRefresh(`（直接调用）或 `onPullDownRefresh:`（对象式 options）
    const hasHandler = /onPullDownRefresh\s*[(:]/.test(code);
    const stops = /stopPullDownRefresh\s*\(/.test(code);
    if (PRINT) {
      const mark = hasHandler ? 'OK ' : '🔴 ';
      console.log(`  ${mark}${entry.path}`);
    }
    if (!hasHandler) {
      failures.push(
        `${cmp}/${entry.path}: 声明 enablePullDownRefresh 但没有注册 onPullDownRefresh（下拉只转圈不收回）`
      );
    } else if (!stops) {
      // 只注册不停表同样是用户可见缺陷：转圈会一直挂着（replenishment 曾如此）
      failures.push(
        `${cmp}/${entry.path}: 注册了 onPullDownRefresh 但从未调用 stopPullDownRefresh（下拉转圈不会收回）`
      );
    }
  }
}

if (failures.length) {
  console.error(`[check-mp-pull-refresh] 🔴 ${failures.length} 个页面下拉刷新无处理函数：`);
  for (const f of failures) console.error('  - ' + f);
  console.error(
    '  修法：在页面 setup 里注册 onPullDownRefresh(async () => { try { await load(); } finally { uni.stopPullDownRefresh(); } })；' +
      '若结构在组件里，组件 defineExpose({ reload: load })，由页面 ref 转调。'
  );
  process.exit(1);
}

console.log(
  `[check-mp-pull-refresh] OK：${declared} 个声明下拉刷新的页面均有页面级 onPullDownRefresh`
);
