#!/usr/bin/env node
/**
 * 运营后台布局防抖 / 审单工作台门禁。
 * 对照 .cursor/rules/admin-layout-anti-jitter.mdc 问题表 A～H。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const errors = [];

function read(rel) {
  return fs.readFileSync(path.join(root, rel), 'utf8');
}

function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

function fail(msg) {
  errors.push(msg);
}

const mainCss = stripComments(read('clients/admin-vue/src/styles/main.css'));
const drawerTs = read('clients/admin-vue/src/composables/useResizableDrawer.ts');
const drawerTsCode = stripComments(drawerTs);
const drawerVue = stripComments(read('clients/admin-vue/src/components/ResizableDrawer.vue'));
const exceptionVue = stripComments(
  read('clients/admin-vue/src/views/exceptions/ExceptionListView.vue')
);
const disputeVue = stripComments(read('clients/admin-vue/src/views/disputes/DisputeListView.vue'));

function ruleHas(selNeedles, bodyRe) {
  for (const raw of mainCss.split('}')) {
    const brace = raw.indexOf('{');
    if (brace < 0) continue;
    const sel = raw.slice(0, brace);
    const body = raw.slice(brace + 1);
    if (selNeedles.every((n) => sel.includes(n)) && bodyRe.test(body)) return true;
  }
  return false;
}

/** 在含全部 needles 的选择器块里取 body 文本（多块则拼接） */
function ruleBodies(selNeedles) {
  const out = [];
  for (const raw of mainCss.split('}')) {
    const brace = raw.indexOf('{');
    if (brace < 0) continue;
    const sel = raw.slice(0, brace);
    const body = raw.slice(brace + 1);
    if (selNeedles.every((n) => sel.includes(n))) out.push(body);
  }
  return out.join('\n');
}

// ——— A) 右侧操作列必须 sticky ———
if (!ruleHas(['.table-scroll', 'fixed-column--right'], /position\s*:\s*sticky/i)) {
  fail('A: main.css .table-scroll 内 fixed-column--right 必须 position:sticky（操作列要锁住）');
}
if (ruleHas(['.table-scroll', 'fixed-column--right'], /position\s*:\s*static/i)) {
  fail('A: main.css .table-scroll 内 fixed-column--right 不可再 static（与锁列冲突）');
}

// ——— I) 横滚须收在内层：包着 CrudTable 的壳不得自己横滚 ———
// 壳一旦成为横滚容器，位于壳内的吸附工具行（排序/批量删除/刷新）与分页行
// 会随表体一起左移（现象：往右滑动，分页和表格动作按钮跟着动）。
if (!ruleHas(['.table-scroll:has(.crud-table)'], /overflow\s*:\s*visible/i)) {
  fail(
    'I: main.css 包着 CrudTable 的 .table-scroll 壳须 overflow:visible（否则壳成横滚容器，工具行/分页行随表体左移）'
  );
}
{
  const innerBodies = ruleBodies(['.crud-table__table']);
  if (!innerBodies || !/overflow-x\s*:\s*auto/i.test(innerBodies)) {
    fail('I: main.css 缺少 .crud-table__table{overflow-x:auto}（横向滚动须下移到该内层容器）');
  }
}

// ——— G) 禁 dvh / visualViewport / translateZ(0) ———
for (const rel of [
  'clients/admin-vue/src/styles/main.css',
  'clients/admin-vue/src/layouts/AdminLayout.vue',
  'clients/admin-vue/src/utils/table-scroll-fit.ts'
]) {
  const text = stripComments(read(rel));
  if (/100dvh/.test(text)) fail(`G: ${rel} 禁止 100dvh`);
  if (/visualViewport/.test(text)) fail(`G: ${rel} 禁止 visualViewport`);
  if (/translateZ\s*\(\s*0\s*\)/.test(text)) fail(`G: ${rel} 禁止 translateZ(0)`);
}

// ——— B/C) 抽屉拖宽 ———
if (!/RESIZE_HYSTERESIS_PX\s*=\s*[2-9]/.test(drawerTs)) {
  fail('C: useResizableDrawer.ts 缺少 ≥2px RESIZE_HYSTERESIS_PX');
}
if (!/Math\.round/.test(drawerTsCode)) {
  fail('C: useResizableDrawer.ts 须 Math.round');
}
if (!/nextTick/.test(drawerTs)) {
  fail('B: useResizableDrawer.ts 松手须 nextTick，避免清 inline 早于 :size 导致弹回旧宽');
}

{
  const startIdx = drawerTs.indexOf('function onResizeStart');
  const upIdx = drawerTs.indexOf('const onUp', startIdx);
  if (startIdx < 0 || upIdx < 0) {
    fail('B/C: useResizableDrawer.ts 找不到 onResizeStart / onUp');
  } else {
    const beforeUp = drawerTs.slice(startIdx, upIdx);
    const onUpChunk = drawerTs.slice(upIdx, upIdx + 1200);
    if (/width\.value\s*=/.test(beforeUp)) {
      fail('C: useResizableDrawer.ts 拖动中禁止写 width.value');
    }
    if (!/width\.value\s*=/.test(onUpChunk)) {
      fail('B: useResizableDrawer.ts pointerup 须写 width.value');
    }
    if (
      /style\.width\s*=\s*['"]['"]/.test(onUpChunk) ||
      /style\.removeProperty\(\s*['"]width['"]/.test(onUpChunk)
    ) {
      fail('B: useResizableDrawer.ts pointerup 禁止清空 style.width（会弹回旧宽）');
    }
  }
}

if (!/\.is-resizing/.test(drawerVue)) {
  fail('C: ResizableDrawer.vue 缺少 .is-resizing');
}
if (
  !/scrollbar-gutter\s*:\s*stable/.test(drawerVue) &&
  !/scrollbar-gutter\s*:\s*stable/.test(mainCss)
) {
  fail('D: 抽屉 body 须 scrollbar-gutter: stable');
}

// ——— D) 抽屉纵向 scroll + media-actions nowrap ———
if (!/\.el-drawer__body[\s\S]{0,320}overflow-y\s*:\s*scroll/.test(mainCss)) {
  fail('D: main.css .el-drawer__body 须 overflow-y: scroll（禁 auto，防滚动条点击挤窄）');
}
if (/workbench-media-actions[\s\S]{0,220}flex-wrap\s*:\s*wrap/.test(mainCss)) {
  fail('D: main.css .workbench-media-actions 禁止 flex-wrap:wrap（滚动条闪动会挤行）');
}
if (!/workbench-media-actions[\s\S]{0,220}flex-wrap\s*:\s*nowrap/.test(mainCss)) {
  fail('D: main.css .workbench-media-actions 须 flex-wrap: nowrap');
}

// ——— E) 描述表：内容可换行且不溢出盖邻格 ———
{
  const contentBody = ruleBodies(['workbench-desc', 'el-descriptions__content']);
  if (!contentBody) {
    fail('E: main.css 缺少 .workbench-desc .el-descriptions__content 规则');
  } else {
    if (!/overflow\s*:\s*hidden/.test(contentBody)) {
      fail('E: .workbench-desc 内容格须 overflow:hidden（防 Tag 盖邻格标签）');
    }
    if (!/white-space\s*:\s*normal/.test(contentBody)) {
      fail('E: .workbench-desc 内容格须 white-space:normal（允许格内换行）');
    }
  }
  const tagBody = ruleBodies(['workbench-desc', 'el-tag']);
  if (!tagBody || !/max-width\s*:\s*100%/.test(tagBody)) {
    fail('E: .workbench-desc 内 .el-tag 须 max-width:100%');
  }
}

// ——— F) 调整明细：数量列 ≥150px，input-number 不溢出 ———
{
  const lineBody = ruleBodies(['manual-line']);
  // 匹配第二列固定像素 ≥150（允许 150px / 160px 等）
  const colMatch = /grid-template-columns\s*:\s*[^;]*?\s(\d+)px\s+max-content/i.exec(lineBody);
  if (!colMatch) {
    fail('F: .manual-line 须为 grid-template-columns: … NpX max-content（数量列+删除）');
  } else if (Number(colMatch[1]) < 150) {
    fail(`F: .manual-line 数量列 ${colMatch[1]}px < 150，会盖住「删除」`);
  }
  if (!/\.manual-line\s+\.el-input-number[\s\S]{0,120}max-width\s*:\s*100%/.test(mainCss)) {
    fail('F: .manual-line .el-input-number 须 max-width:100%');
  }
}

// ——— G 延伸）workbench-grid 禁止大硬下限 ———
for (const [name, src] of [
  ['ExceptionListView.vue', exceptionVue],
  ['DisputeListView.vue', disputeVue]
]) {
  if (/workbench-grid[\s\S]{0,200}minmax\s*\(\s*3\d{2}px/i.test(src)) {
    fail(`G/E: ${name} .workbench-grid 禁止 minmax(300px+/340px+) 硬下限（挤窄叠层）`);
  }
  if (
    /workbench-grid[\s\S]{0,200}grid-template-columns/.test(src) &&
    !/workbench-grid[\s\S]{0,200}minmax\s*\(\s*0\s*,/.test(src)
  ) {
    fail(`G/E: ${name} .workbench-grid 宜用 minmax(0, 1fr) 系列`);
  }
}

// ——— H) 全后台禁止 show-overflow-tooltip；须有原生 title 兜底 ———
{
  const adminSrc = path.join(root, 'clients/admin-vue/src');
  function walkVue(dir, acc = []) {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        if (ent.name === 'node_modules' || ent.name === 'dist') continue;
        walkVue(p, acc);
      } else if (ent.name.endsWith('.vue')) acc.push(p);
    }
    return acc;
  }
  for (const abs of walkVue(adminSrc)) {
    const code = stripComments(fs.readFileSync(abs, 'utf8'));
    if (/show-overflow-tooltip/.test(code)) {
      fail(
        `H: ${path.relative(root, abs)} 禁止 show-overflow-tooltip（用原生 title / installTableCellNativeTitle）`
      );
    }
  }
  const nativeTitle = read('clients/admin-vue/src/utils/table-cell-native-title.ts');
  const mainTs = read('clients/admin-vue/src/main.ts');
  if (!/installTableCellNativeTitle/.test(nativeTitle)) {
    fail('H: 缺少 utils/table-cell-native-title.ts（表格截断原生 title）');
  }
  if (!/installTableCellNativeTitle/.test(mainTs)) {
    fail('H: main.ts 须调用 installTableCellNativeTitle()');
  }
}

if (errors.length) {
  console.error('[check-admin-anti-jitter] FAILED:');
  for (const e of errors) console.error(' -', e);
  process.exit(1);
}
console.log('[check-admin-anti-jitter] OK');
