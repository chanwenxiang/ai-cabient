#!/usr/bin/env node
/**
 * 门禁：admin 前端（endpoints.ts + views/composables 裸字面量）引用的每个端点，
 * 必须存在于后端路由表（离线数据源 = 提交的 openapi.ts；其与运行容器的同步由
 * check-openapi-types 钉住——CI 抓运行容器 /v3/api-docs 重生成后 git diff）。
 *
 * 背景（真实事故，2026-10-14）：b96a74d4（要货申请全链下线）删整块补货端点时，
 * 把设备详情页在用的 `GET /api/v2/ops/admin/devices/{id}/detail` 一并带走（误伤）。
 * 前端仍调用 → Spring NoResourceFoundException → 全局 404 文案「资源不存在」；
 * 详情页首屏数据全空，且每次操作成功后回刷详情必弹一次错误，观感即
 * 「操作成功了却弹资源不存在」。当时的 check-admin-endpoints 只管「必须走
 * AdminEndpoints、禁止裸字面量」，**不校验路径在后端是否存在**，openapi.ts
 * 重生成后前端引用了不存在的 path 也无人拦 → 漏网。
 *
 * 规则：
 *   1. 解析 endpoints.ts 全部模板字符串 + src 其余文件里的 `/api/v2/...` 裸字面量
 *      （排除 *.test.ts 假样例），`${...}` 归一为 `{}`，查询串截断；
 *   2. 每条引用按段匹配 openapi.ts 的 paths（`{}` 段可匹配后端 `{param}` 或
 *      任意单段——覆盖 warehouseOutboundAction 这类动态 action 尾段；
 *      紧贴字面量的 `{}` 是内联查询拼接，剥掉）；
 *   3. 命中不了 → FAIL，打印引用与来源行。
 *
 * 存量豁免：2026-10-14 已全部清零（6 条恢复后端端点、5 条删除 admin 执行链死 UI，
 * 补货执行统一走商户端小程序通道）。当前数组为空——任何新增死引用直接 FAIL。
 *
 *   node scripts/check-admin-endpoint-paths.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-admin-endpoint-paths]';
const ENDPOINTS_TS = 'clients/admin-vue/src/api/endpoints.ts';
const OPENAPI_TS = 'packages/shared-types/src/generated/openapi.ts';

/** 存量豁免：2026-10-14 全部清零——6 条随 CB-037/CB-038 恢复后端端点、5 条删除
 * admin 执行链死 UI（补货执行统一走商户端小程序通道）。新增死引用将直接 FAIL。 */
const EXEMPT = [];

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

// ── 1) 后端路由表：从 openapi.ts 提取 paths 键 ─────────────────────────────
if (!existsSync(join(ROOT, OPENAPI_TS))) fail(`缺少 ${OPENAPI_TS}（先跑 pnpm gen:api-types）`);
const openapiSrc = readFileSync(join(ROOT, OPENAPI_TS), 'utf8');
const pathsSection = openapiSrc.slice(0, openapiSrc.indexOf('export type ApiPaths'));
const backend = new Set(
  [...pathsSection.matchAll(/["'](\/api\/[^"']+)["']\s*:/g)].map((m) => m[1])
);
if (backend.size < 100) fail(`openapi.ts 只解析出 ${backend.size} 条路径，疑似解析失效`);

// ── 2) 前端引用收集 ────────────────────────────────────────────────────────
/** 剥 JS 注释（块+行），跟踪字符串上下文，避免 JSDoc 里的示例 URL 混进来。 */
function stripComments(src) {
  let out = '';
  let i = 0;
  let quote = null;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (quote) {
      out += c;
      if (c === '\\') {
        out += n ?? '';
        i += 2;
        continue;
      }
      if (c === quote) quote = null;
      i += 1;
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      out += c;
      i += 1;
      continue;
    }
    if (c === '/' && n === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end === -1 ? src.length : end + 2;
      continue;
    }
    if (c === '/' && n === '/') {
      const end = src.indexOf('\n', i);
      i = end === -1 ? src.length : end;
      continue;
    }
    out += c;
    i += 1;
  }
  return out;
}

/** 模板字符串 → 路径 pattern：${...}(含一层嵌套) → {}；截断查询串；剥内联拼接。 */
function normalize(tpl) {
  let s = tpl
    .replace(/\$\{(?:[^{}]|\{[^{}]*\})*\}/g, '{}')
    .replace(/[?&]\{\}.*$/, '') // ?{}... / &{}... 为查询拼接 → 截断
    .replace(/{}$/, (m, off, full) => (off > 0 && full[off - 1] !== '/' ? '' : m)); // 紧贴字面量的 {} 是内联拼接
  const q = s.indexOf('?');
  if (q >= 0) s = s.slice(0, q);
  return s;
}

const refs = new Map(); // pattern -> 首个来源描述
const OPS_RE = /\$\{ops\}/g;
const PREFIX_RE = /\$\{API_PREFIX\}/g;
function collect(src, file) {
  const clean = stripComments(src);
  const tpl = [...clean.matchAll(/`([^`]+)`/g)];
  for (const m of tpl) {
    // 先展开前缀常量再过滤（多数模板写作 `${ops}/...`，展开前不含字面 /api/）
    const base = m[1].replace(OPS_RE, '/api/v2/ops/admin').replace(PREFIX_RE, '/api/v2');
    if (!base.includes('/api/')) continue;
    if (/[*|\s]|\.\./.test(base)) continue; // 通配/示例/多分支残留
    // 三元双分支：`A` : `B` —— 拆两个候选（仅当 A、B 都是纯路径字面量形态）
    const branches = [base, ...[...base.matchAll(/`\s*:\s*`([^`]+)`/g)].map((x) => x[1])];
    for (const b of branches) {
      if (!b.startsWith('/api/')) continue;
      const p = normalize(b);
      if (!/^\/api\/v2\/[\w{}./-]+$/.test(p)) continue;
      // 前缀常量定义（const ops / API_PREFIX）会被当成模板抓到，但它们从不被直接请求
      if (p === '/api/v2/ops/admin' || p === '/api/v2') continue;
      if (!refs.has(p)) refs.set(p, file);
    }
  }
}

collect(readFileSync(join(ROOT, ENDPOINTS_TS), 'utf8'), ENDPOINTS_TS);

// 裸字面量（views/composables 等；排除 endpoints.ts 自身与 *.test.ts 假样例）
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const f = join(dir, name);
    if (statSync(f).isDirectory()) walk(f, out);
    else if (/\.(ts|vue)$/.test(name) && !/\.test\.ts$/.test(name)) out.push(f);
  }
  return out;
}
const srcRoot = join(ROOT, 'clients/admin-vue/src');
for (const f of walk(srcRoot)) {
  if (f.replace(/\\/g, '/').endsWith(ENDPOINTS_TS)) continue;
  const hits = [
    ...stripComments(readFileSync(f, 'utf8')).matchAll(/['"`](\/api\/v2\/[^'"`?]+)['"`]/g)
  ];
  for (const h of hits) {
    const p = h[1];
    if (!refs.has(p)) refs.set(p, f.replace(ROOT, '').replace(/\\/g, '/').slice(1));
  }
}

// ── 3) 匹配 ────────────────────────────────────────────────────────────────
function matches(ref, be) {
  const a = ref.split('/');
  const b = be.split('/');
  if (a.length !== b.length) return false;
  return a.every((seg, i) => seg === b[i] || (seg === '{}' && (b[i].startsWith('{') || i > 0)));
}

const dead = [];
for (const [p, from] of refs) {
  if (EXEMPT.includes(p)) continue;
  if ([...backend].some((be) => matches(p, be))) continue;
  dead.push({ p, from });
}

if (dead.length) {
  console.error(`${TAG} FAIL: ${dead.length} 条前端引用在后端路由表中不存在（死路由）:`);
  for (const d of dead) console.error(`  ${d.p}\n      <- ${d.from}`);
  console.error('  处置：端点被删→恢复或删前端调用；路径迁移→改 endpoints.ts；误报→检查解析。');
  process.exit(1);
}
console.log(
  `${TAG} OK: ${refs.size} 条前端端点引用全部命中后端路由表（后端 ${backend.size} 条 paths；豁免存量 ${EXEMPT.length} 条）`
);
