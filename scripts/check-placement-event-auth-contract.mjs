#!/usr/bin/env node
/**
 * 门禁：消费者端展示位打点的鉴权契约（首屏早于登录，禁止直连端点）。
 *
 * 背景（2026-11-11 实证）：消费者端每次冷启动，开发者工具控制台必报
 *   `POST http://192.168.0.26/api/v2/marketing/placement-events 401`
 * 网关日志证明它是**游客态直连**，不是 token 过期：
 *   `02:53:56 GET /marketing/popups 200`（公开白名单，游客也拿得到）
 *   `02:53:56 POST /placement-events 401 60B` × 2
 *   `02:53:57 POST /auth/wx-login 200`（微信静默登录这时才完成）
 * 60B 是后端「请先登录」（无凭证），81B 才是「登录已失效」。
 * 打点接口要求登录是**有意设计**（主体从登录态取，防匿名刷量），
 * 所以正确修法是前端把这几秒的事件攒起来、登录成功后补发（utils/placement-event-queue），
 * 而不是把端点加进白名单。
 *
 * 判据：
 *   P1 直连面：`clients/consumer-mp/src` 全树（.ts/.vue）只有 `utils/consumer-api.ts`
 *      允许引用 `ConsumerEndpoints.marketingPlacementEvent`；其它文件出现即红
 *      （绕过队列 = 首屏必 401）。
 *   P2 补发点：`consumer-api.ts` 的 `applyTokenSession` 必须调用 `placementEvents().flush()`。
 *      漏了它，队列只进不出 —— 事件静默丢失，且不会有任何报错。
 *   P3 打点入口：banner 组件与两个弹窗页必须各自调用 `reportPlacementEvent(`。
 *      打点被整段删掉不会有类型错误（返回值是 void），只能靠枚举登记守住。
 *   P4 队列有界：队列必须有上限与登录态分流（无上限会在长期未登录时无限攒事件）。
 *   P5 登录落定点：`applyTokenSession` 必须 flush 设备推广位打点队列**并**广播
 *      `consumerSessionReady`。后者是「登录后重做取数」的唯一信号 —— 漏了它，
 *      游客态挂载的设备投放位永远不会重拉内容（表现为广告位一直空白，且不报错）。
 *   P6 拉内容前判登录：`device-ad-banner.vue` 的 `load` 必须判 `isConsumerLoggedIn()`。
 *      `screen-content` 不在公开白名单，游客态拉必 401；而投放清单是**有时效的内容**，
 *      不能像打点那样攒着补发，只能「未登录不拉、登录后重拉」。
 *   P7 队列守卫必须用「可用凭证」判据：placementEvents/adPlayEvents 的 `isLoggedIn`
 *      依赖必须接 `canSendTrackedEventsNow`（cookie 会话或**未过期** bearer），
 *      不得直接接 `isConsumerLoggedIn`——后者对过期票恒真（R15-K5 有意保留），
 *      过期票按 C3 拿不到 bearer，直发就是无凭证 401。2026-11-12 实证：隔夜票
 *      冷启动网关日志必现 `placement-events 401 60B → 1s 内 200 48B`（续期重放成功，
 *      事件没丢但每次冷启动留一条 401 噪音）。守卫收紧后过期票与游客态同构：先入队，
 *      applyTokenSession 落定后补发。
 *
 * 扫描前统一抹注释（等长空格）：本文件注释里就写着这些标识符，不抹会自己判红。
 *
 * 用法：node scripts/check-placement-event-auth-contract.mjs
 * 负向验证：node scripts/devops/verify-placement-event-auth-contract.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, extname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[check-placement-event-auth-contract]';
const ROOT = process.env.PLACEMENT_GATE_ROOT
  ? process.env.PLACEMENT_GATE_ROOT
  : join(dirname(fileURLToPath(import.meta.url)), '..');

const SRC = join(ROOT, 'clients', 'consumer-mp', 'src');
const API_FILE = join(SRC, 'utils', 'consumer-api.ts');
const QUEUE_FILE = join(SRC, 'utils', 'placement-event-queue.ts');
const AD_BANNER = join(SRC, 'components', 'device-ad-banner.vue');

/** 打点入口登记：新增展示位打点必须在这里加（P3 用）。 */
const ENTRY_FILES = [
  join(SRC, 'components', 'marketing-ad-banner.vue'),
  join(SRC, 'pages', 'index', 'index.vue'),
  join(SRC, 'pages', 'result', 'result.vue')
];

/**
 * 只允许 `consumer-api.ts` 直连的打点端点（P1 用）。
 * 两者都要求登录：游客态直连必产 401，且旧请求层会把一次上报变成两次。
 */
const DIRECT_ENDPOINTS = ['marketingPlacementEvent', 'deviceAdPlay'];

const problems = [];

function readOrFail(path, label) {
  try {
    return readFileSync(path, 'utf8');
  } catch {
    problems.push(`${label} 不存在：${relative(ROOT, path)}`);
    return '';
  }
}

/** 注释抹除器：行/块注释替换为等长空格（保留换行 ⇒ 行号不变），字符串原样保留。 */
function maskComments(src) {
  let out = '';
  let i = 0;
  let state = 'code';
  let quote = '';
  while (i < src.length) {
    const c = src[i];
    const c2 = src[i + 1];
    if (state === 'code') {
      if (c === '/' && c2 === '/') {
        state = 'line';
        out += '  ';
        i += 2;
        continue;
      }
      if (c === '/' && c2 === '*') {
        state = 'block';
        out += '  ';
        i += 2;
        continue;
      }
      if (c === '"' || c === "'" || c === '`') {
        state = 'str';
        quote = c;
        out += c;
        i += 1;
        continue;
      }
      out += c;
      i += 1;
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        state = 'code';
        out += c;
      } else {
        out += ' ';
      }
      i += 1;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && c2 === '/') {
        state = 'code';
        out += '  ';
        i += 2;
        continue;
      }
      out += c === '\n' ? c : ' ';
      i += 1;
      continue;
    }
    if (c === '\\') {
      out += c + (c2 ?? '');
      i += 2;
      continue;
    }
    if (c === quote) state = 'code';
    out += c;
    i += 1;
  }
  return out;
}

/** 按花括号配平取函数体，找不到返回 ''。 */
function sliceFunctionBody(code, marker) {
  const start = code.indexOf(marker);
  if (start < 0) return '';
  const braceStart = code.indexOf('{', start);
  if (braceStart < 0) return '';
  let depth = 0;
  for (let i = braceStart; i < code.length; i += 1) {
    if (code[i] === '{') depth += 1;
    else if (code[i] === '}') {
      depth -= 1;
      if (depth === 0) return code.slice(braceStart, i + 1);
    }
  }
  return '';
}

function walk(dir, acc = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return acc;
  }
  for (const name of entries) {
    const full = join(dir, name);
    let st;
    try {
      st = statSync(full);
    } catch {
      continue;
    }
    if (st.isDirectory()) walk(full, acc);
    else if (['.ts', '.vue'].includes(extname(name))) acc.push(full);
  }
  return acc;
}

// ── P1：只有 consumer-api.ts 能直连打点端点 ────────────────────────────────
const apiRaw = readOrFail(API_FILE, '消费者端 consumer-api.ts');
const files = walk(SRC);
if (files.length === 0) {
  problems.push('P1：扫不到源码文件 —— 目录结构被改，门禁失效，请同步更新判据');
}
for (const file of files) {
  if (file === API_FILE) continue;
  const code = maskComments(readFileSync(file, 'utf8'));
  for (const endpoint of DIRECT_ENDPOINTS) {
    if (new RegExp(`ConsumerEndpoints\\s*\\.\\s*${endpoint}`).test(code)) {
      problems.push(
        `P1：${relative(ROOT, file)} 直连了打点端点 ${endpoint} —— 首屏渲染早于微信静默登录，` +
          '直连必产 401；请改走 consumerApi.reportPlacementEvent / reportAdPlay（未登录先入队、登录后补发）'
      );
    }
  }
}

// ── P2：登录落定必须触发补发 ───────────────────────────────────────────────
if (apiRaw) {
  const api = maskComments(apiRaw);
  const applyBody = sliceFunctionBody(api, 'function applyTokenSession');
  if (!applyBody) {
    problems.push('P2：找不到 applyTokenSession 函数体 —— 结构被改，门禁失效，请同步更新判据');
  } else if (!/placementEvents\(\s*\)\s*\.\s*flush\(\s*\)/.test(applyBody)) {
    problems.push(
      'P2：applyTokenSession 未调用 placementEvents().flush() —— 队列只进不出，' +
        '首屏攒下的曝光/点击会静默丢失（且不报错）'
    );
  }
  if (!/from\s+['"]@\/utils\/placement-event-queue['"]/.test(api)) {
    problems.push(
      "P2：consumer-api.ts 必须从 '@/utils/placement-event-queue' 引入队列（禁止本地另造一份）"
    );
  }
}

// ── P3：打点入口必须还在 ───────────────────────────────────────────────────
for (const file of ENTRY_FILES) {
  const raw = readOrFail(file, '打点入口');
  if (!raw) continue;
  const code = maskComments(raw);
  if (!/reportPlacementEvent\s*\(/.test(code)) {
    problems.push(
      `P3：${relative(ROOT, file)} 没有调用 reportPlacementEvent —— ` +
        '展示位打点被删空不会有任何报错，只会让 ROI 数据静默变少'
    );
  }
}

// ── P4：队列模块自身的关键约束（上限 + 未登录入队） ────────────────────────
const queueRaw = readOrFail(QUEUE_FILE, '打点队列模块');
if (queueRaw) {
  const queue = maskComments(queueRaw);
  if (!/DEFAULT_MAX_PENDING/.test(queue) || !/maxSize/.test(queue)) {
    problems.push('P4：队列必须有上限（无上限会在长期未登录时无限攒事件）');
  }
  if (!/isLoggedIn\(\s*\)/.test(queue)) {
    problems.push('P4：队列必须按登录态分流（未登录入队 / 已登录直发）');
  }
}

// ── P5：设备打点与「登录后重拉」也必须挂在登录落定点 ────────────────────────
if (apiRaw) {
  const applyBody = sliceFunctionBody(maskComments(apiRaw), 'function applyTokenSession');
  if (applyBody) {
    if (!/adPlayEvents\(\s*\)\s*\.\s*flush\(\s*\)/.test(applyBody)) {
      problems.push(
        'P5：applyTokenSession 未 flush 设备推广位打点队列 —— token 恰在打点瞬间到期时事件会静默丢失'
      );
    }
    if (!/consumerSessionReady\s*\.\s*emit\(\s*\)/.test(applyBody)) {
      problems.push(
        'P5：applyTokenSession 未广播 consumerSessionReady —— 游客态挂载的投放位永远不会重拉内容（广告位空白到底）'
      );
    }
  }
}

// ── P6：拉投放内容前必须判登录（内容不可补发，只能重拉） ────────────────────
const adBannerRaw = readOrFail(AD_BANNER, '设备投放位组件');
if (adBannerRaw) {
  const code = maskComments(adBannerRaw);
  const loadBody = sliceFunctionBody(code, 'async function load(');
  if (!loadBody) {
    problems.push(
      'P6：device-ad-banner.vue 找不到 load 函数体 —— 结构被改，门禁失效，请同步更新判据'
    );
  } else if (!/isConsumerLoggedIn\s*\(/.test(loadBody)) {
    problems.push(
      'P6：device-ad-banner.load 未判登录态 —— 游客扫码进柜会拿 screen-content 撞 401' +
        '（该接口不在 WebConfig 公开白名单），且投放清单是有时效的内容，不能像打点那样补发'
    );
  }
}

// ── P7：队列守卫必须用「可用凭证」判据（过期票不得直发） ───────────────────
if (apiRaw) {
  const api = maskComments(apiRaw);
  const GUARD = 'canSendTrackedEventsNow';
  const guardBody = sliceFunctionBody(api, `function ${GUARD}(`);
  if (!guardBody) {
    problems.push(
      `P7：consumer-api.ts 找不到 ${GUARD} 函数体 —— 打点队列守卫被删或改名，` +
        '过期票会重新直发（每次冷启动必现 placement-events 401→续期重放噪音）'
    );
  } else if (!/getConsumerToken\s*\(/.test(guardBody)) {
    problems.push(
      `P7：${GUARD} 未走 getConsumerToken 判可用凭证 —— 该函数按 C3 对过期票返空串，` +
        '是「票当前可用」的唯一判据；换 raw 存储（如 uni.getStorageSync(TOKEN_KEY)）会把过期票当可用'
    );
  }
  for (const [label, marker] of [
    ['展示位打点队列', 'function placementEvents('],
    ['设备推广位打点队列', 'function adPlayEvents(']
  ]) {
    const body = sliceFunctionBody(api, marker);
    if (!body) {
      problems.push(`P7：找不到 ${label} 构造体（${marker}）—— 结构被改，门禁失效，请同步更新判据`);
      continue;
    }
    if (!new RegExp(`isLoggedIn\\s*:\\s*${GUARD}`).test(body)) {
      problems.push(
        `P7：${label} 的 isLoggedIn 未接 ${GUARD} —— 接 isConsumerLoggedIn 会对过期票恒真，` +
          '过期票拿不到 bearer（C3），直发必产 401 噪音；过期票应与游客态同构先入队'
      );
    }
  }
}

if (problems.length) {
  console.error(`${TAG} FAIL: 打点鉴权契约被破坏（${problems.length} 条）`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}

console.log(
  `${TAG} OK（P1 无直连 / P2 登录后补发 / P3 入口在位 / P4 队列有界 / P5 设备打点+重拉挂登录点 / P6 拉内容前判登录 / P7 守卫用可用凭证）`
);
