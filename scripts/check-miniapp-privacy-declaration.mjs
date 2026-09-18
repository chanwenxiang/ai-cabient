#!/usr/bin/env node
/**
 * 小程序「隐私接口声明」门禁。
 *
 * 背景（09-18 复核实测）：`clients/consumer-mp/src/pages/nearby/nearby.vue:159` 调用
 * `uni.getLocation`，但 `clients/consumer-mp/src/manifest.json` 既无
 * `permission.scope.userLocation` 也无 `requiredPrivateInfos` ⇒ 微信侧 not declared
 * 的隐私接口**直接禁用**（errno 112 `api scope is not declared in the privacy agreement`
 * / `appid privacy api banned`）。而 `clients/merchant-mp/src/manifest.json:26-31`
 * 两样都有 —— 典型的**同族缺陷一端修一端漏**（本项目反复出现过）。
 *
 * 为什么不判 `__usePrivacyCheck__`：微信官方《小程序隐私协议开发指南》明确
 * 「2023-09-15 之后 / 2023-10-17 之后，**不论 app.json 中是否有配置
 * `__usePrivacyCheck__`，隐私相关功能都会启用**」⇒ 该字段已是**空操作**。
 * 把它当缺口写进去属于「信号在骗读者」，故本门禁**不判**它。
 * 另：后台《用户隐私保护指引》声明属**管理后台配置**，代码侧无法取证，不在本门禁内。
 *
 * 规则：
 *   1. 每个小程序（`clients/<pkg>/src/manifest.json`）中，凡源码调用了**需要
 *      `requiredPrivateInfos` 声明**的接口，manifest 的 `mp-weixin.requiredPrivateInfos`
 *      必须包含该接口（缺失或空数组均视为未声明）。
 *   2. 凡用到 `getLocation`，`mp-weixin.permission.scope.userLocation.desc`
 *      必须是非空字符串（微信授权弹窗文案）。
 *
 * 防「恒真 / 恒假」：
 *   - 扫到的小程序数 < MIN_APPS ⇒ 红（说明 clients/* 路径表达式已失效）；
 *   - 全仓位置类调用数 < MIN_LOCATION_CALLS ⇒ 红（说明正则已失效，否则本门禁恒绿）；
 *   - 本文件只做「期望绿」的正向断言；「改回漏声明 → 必红」的负向对照由
 *     `scripts/devops/verify-miniapp-privacy-drift.py` 负责（A/B 证据）。
 *
 *   node scripts/check-miniapp-privacy-declaration.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-miniapp-privacy-declaration]';

/** 需要写进 `requiredPrivateInfos` 的接口（微信官方 requiredPrivateInfos 取值）。 */
const REQUIRING = new Set([
  'getFuzzyLocation',
  'getLocation',
  'onLocationChange',
  'startLocationUpdate',
  'startLocationUpdateBackground',
  'chooseLocation',
  'choosePoi',
  'chooseAddress',
  'chooseInvoiceTitle',
  'getWeRunData'
]);

/** 凡出现这些接口，必须同时给出 `permission.scope.<scope>` 文案。 */
const SCOPE_OF = new Map([
  ['getLocation', 'scope.userLocation'],
  ['getFuzzyLocation', 'scope.userLocation'],
  ['onLocationChange', 'scope.userLocation'],
  ['startLocationUpdate', 'scope.userLocation'],
  ['startLocationUpdateBackground', 'scope.userLocation']
]);

const MIN_APPS = 2;
const MIN_LOCATION_CALLS = 1;

const SKIP_DIR = new Set(['node_modules', 'dist', 'unpackage', '.git', 'coverage']);

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (SKIP_DIR.has(name)) continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(vue|ts|js|mjs|cjs)$/.test(name)) out.push(p);
  }
  return out;
}

/** 收集某端源码里调用的「需要声明」的接口 → 调用点列表。 */
function collectCalls(appDir) {
  const hits = new Map();
  for (const file of walk(join(appDir, 'src'))) {
    const src = readFileSync(file, 'utf8');
    for (const m of src.matchAll(/\b(?:uni|wx)\.([A-Za-z][A-Za-z0-9]*)\s*\(/g)) {
      const api = m[1];
      if (!REQUIRING.has(api)) continue;
      const line = src.slice(0, m.index).split('\n').length;
      if (!hits.has(api)) hits.set(api, []);
      hits.get(api).push(`${relative(root, file).replace(/\\/g, '/')}:${line}`);
    }
  }
  return hits;
}

const clientsDir = join(root, 'clients');
if (!existsSync(clientsDir)) fail('找不到 clients/ 目录');

const apps = readdirSync(clientsDir)
  .map((name) => ({ name, dir: join(clientsDir, name) }))
  .filter((a) => existsSync(join(a.dir, 'src', 'manifest.json')));

if (apps.length < MIN_APPS) {
  fail(
    `只扫到 ${apps.length} 个含 src/manifest.json 的小程序（期望 ≥ ${MIN_APPS}）：` +
      `clients/* 的目录结构或 manifest 位置可能已变，门禁已失效`
  );
}

const problems = [];
let totalCalls = 0;

for (const app of apps) {
  const calls = collectCalls(app.dir);
  totalCalls += [...calls.values()].reduce((n, v) => n + v.length, 0);
  if (calls.size === 0) continue;

  const manifestPath = join(app.dir, 'src', 'manifest.json');
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch (e) {
    problems.push(`${app.name}: src/manifest.json 不是合法 JSON（${e.message}）`);
    continue;
  }

  const wx = (manifest['mp-weixin'] ?? {}) || {};
  const declared = Array.isArray(wx.requiredPrivateInfos) ? wx.requiredPrivateInfos : [];

  const missing = [...calls.keys()].filter((api) => !declared.includes(api));
  if (missing.length) {
    const detail = missing.map((api) => `${api}（调用点 ${calls.get(api).join(', ')}）`).join('；');
    problems.push(
      `${app.name}: manifest.mp-weixin.requiredPrivateInfos 未声明实际调用的隐私接口 —— ${detail}` +
        `（当前声明：${declared.length ? declared.join(', ') : '空/缺失'}）`
    );
  }

  const perm = (wx.permission ?? {}) || {};
  for (const api of calls.keys()) {
    const scope = SCOPE_OF.get(api);
    if (!scope) continue;
    const desc = perm[scope]?.desc;
    if (typeof desc !== 'string' || desc.trim() === '') {
      problems.push(
        `${app.name}: 调用了 ${api}，但 manifest.mp-weixin.permission.${scope}.desc 缺失或为空` +
          `（微信授权弹窗文案，缺失时会用默认文案，审核易被驳回）`
      );
    }
  }
}

if (totalCalls < MIN_LOCATION_CALLS) {
  fail(
    `全仓只扫到 ${totalCalls} 处「需声明」的隐私接口调用（期望 ≥ ${MIN_LOCATION_CALLS}）：` +
      `API 正则或 REQUIRING 集合可能已失效 —— 若确实是业务移除，请同步下调阈值，` +
      `否则本门禁会恒绿`
  );
}

if (problems.length) {
  console.error(`${TAG} FAIL: 发现 ${problems.length} 处隐私声明缺陷：`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}

console.log(
  `${TAG} OK: ${apps.length} 个小程序、${totalCalls} 处隐私接口调用均已声明` +
    `（requiredPrivateInfos + permission.desc）`
);
