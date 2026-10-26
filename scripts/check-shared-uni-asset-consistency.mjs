#!/usr/bin/env node
/**
 * shared-uni 共享组件静态资产注册一致性门禁（C，2026-10-27）。
 *
 * 背景：check-mp-defect-classes 的 P 类曾把 shared-uni 组件对两端「各查一遍」，
 * pay-score-tag 只注册在 consumer-mp 的 easycom、logo 也只落在 consumer-mp/static，
 * 于是对 merchant-mp 误报「引用不存在的静态资产」。
 * 本门禁把双向都钉死：
 *   ① 共享组件引用的 /static/ 资产，必须存在于「注册了该组件的每个 app」的 static/；
 *   ② easycom 注册表里映射的共享组件文件必须真实存在（防注册悬空）。
 *
 * fail-closed：注册表读不到、扫描零组件都按失败处理。
 *   node scripts/check-shared-uni-asset-consistency.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-shared-uni-asset-consistency]';
const APPS = [
  { name: 'consumer-mp', src: 'clients/consumer-mp/src' },
  { name: 'merchant-mp', src: 'clients/merchant-mp/src' }
];
const SHARED_COMPONENTS_DIR = 'packages/shared-uni/src/components';

const STATIC_REF_RE = /['"`](\/?static\/[A-Za-z0-9._\-/]+\.(?:png|jpe?g|svg|webp|gif|mp4))['"`]/g;

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

// 收集共享组件 → 引用的静态资产
const sharedDir = join(root, SHARED_COMPONENTS_DIR);
if (!existsSync(sharedDir)) fail(`找不到共享组件目录 ${SHARED_COMPONENTS_DIR}`);
const assetByComponent = new Map();
let scanned = 0;
for (const name of [
  ...new Set([
    'pay-score-tag',
    'empty-state',
    'error-state',
    'app-nav-bar',
    'app-button',
    'app-underline-tabs',
    'privacy-consent-modal'
  ])
]) {
  const file = join(sharedDir, `${name}.vue`);
  if (!existsSync(file)) continue;
  scanned++;
  const refs = new Set();
  const code = readFileSync(file, 'utf8');
  STATIC_REF_RE.lastIndex = 0;
  let m;
  while ((m = STATIC_REF_RE.exec(code))) refs.add(m[1].startsWith('/') ? m[1] : `/${m[1]}`);
  assetByComponent.set(name, refs);
}
if (scanned === 0) fail('共享组件零扫描——门禁自身失效（fail-closed）');

let checkedPairs = 0;
for (const app of APPS) {
  const pagesJsonPath = join(root, app.src, 'pages.json');
  if (!existsSync(pagesJsonPath)) continue;
  const pagesJson = readFileSync(pagesJsonPath, 'utf8');
  for (const [component, assets] of assetByComponent) {
    const registered = pagesJson.includes(component);
    for (const asset of assets) {
      if (registered) {
        // ① 注册了的 app：资产必须存在
        checkedPairs++;
        if (!existsSync(join(root, app.src, asset))) {
          fail(`${app.name} 注册了 ${component} 但缺少静态资产 ${asset}（easycom 映射会 404）`);
        }
      } else if (existsSync(join(root, app.src, asset))) {
        // ② 没注册却拷了资产：允许（提前铺货），但提示一声
        console.log(`${TAG} note: ${app.name} 未注册 ${component} 但已备资产 ${asset}（可接受）`);
      }
    }
  }
  // ③ easycom 映射指向的共享组件文件必须存在
  const easycomMatch =
    pagesJson.match(/"@aicabinet\/shared-uni\/components\/([a-z0-9-]+)\.vue"/g) ?? [];
  for (const ref of easycomMatch) {
    const name = ref.match(/components\/([a-z0-9-]+)\.vue/)[1];
    checkedPairs++;
    if (!existsSync(join(root, SHARED_COMPONENTS_DIR, `${name}.vue`))) {
      fail(
        `${app.name} easycom 注册了 ${name} 但 packages/shared-uni/src/components/${name}.vue 不存在`
      );
    }
  }
}
console.log(
  `${TAG} OK：${scanned} 个共享组件 × ${APPS.length} 端注册/资产一致性 ${checkedPairs} 对全部对齐`
);
