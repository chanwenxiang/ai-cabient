#!/usr/bin/env node
/**
 * 门禁：运营后台「柜机对象级授权」不得回退（R23X 修复批 1 的机制解，2026-11-15）。
 *
 * 背景：同一类缺陷（只挂功能码、不查数据范围）从 MA-068 到本轮已复发四次。
 * 修法不是逐个端点补 `requireDeviceAccess`（押在「下一个人记得」上，
 * `AdminJiangyiController` 一个类就漏了 34 个），而是在 `/api/v2/ops/**` 前缀上用
 * `OpsDeviceScopeInterceptor` 拦 `{deviceId}` 路径变量。本门禁钉住这条机制不被拆掉：
 *
 *  R1 拦截器本体：必须真的调 `requireDeviceAccess(`、必须从 `URI_TEMPLATE_VARIABLES_ATTRIBUTE`
 *     取 `deviceId`、必须对「拿不到操作员身份」fail-closed（Unuthenticated 不等于放行）。
 *  R2 注册面：`WebConfig` 必须用 `OPS_PREFIX` 注册、order > 0（晚于 AuthInterceptor 拿身份）、
 *     且**不得**在该注册上加 `excludePathPatterns`（排除清单＝静默绕过口）。
 *  R3 前缀一致性：`OPS_PREFIX` 必须是 `/api/v2/ops/**`，且所有运营控制器类级
 *     `@RequestMapping` 都落在 `/api/v2/ops` 下——否则新开 `/api/v3/ops` 会整批漏网。
 *  R4 查询串例外：运营端点若把 deviceId 放在 `@RequestParam`（拦截器看不见），
 *     必须自身（≤2 跳）过范围判据，或在 `scripts/ops-device-scope-baseline.json` 里
 *     带原因登记（当前都是「分页筛选条件」，由 service 做交集）。
 *
 * 负向验证：`node scripts/devops/verify-ops-device-scope-gate.mjs`（S1–S6）。
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// GATE_ROOT：夹具沙箱覆写扫描根（生产路径行为不变，见 scripts/devops/verify-ops-device-scope-gate.mjs）。
// GATE_SKIP_SANITY：沙箱里只放少量文件，跳过「扫描面下限」防假绿阈值。
const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SKIP_SANITY = process.env.GATE_SKIP_SANITY === '1';
const TAG = '[check-ops-device-scope]';
const BASELINE_PATH = join('scripts', 'ops-device-scope-baseline.json');
const TRADE_SRC = join(ROOT, 'services', 'trade-service', 'src', 'main', 'java');
const API_DIR = join(TRADE_SRC, 'com', 'aicabinet', 'trade', 'api');
const INTERCEPTOR = join(
  TRADE_SRC,
  'com',
  'aicabinet',
  'trade',
  'auth',
  'OpsDeviceScopeInterceptor.java'
);
const WEB_CONFIG = join(TRADE_SRC, 'com', 'aicabinet', 'trade', 'config', 'WebConfig.java');
const SCOPE_APIS = [
  'requireDeviceAccess',
  'requireDeviceFilter',
  'intersectDeviceFilter',
  'canAccessDevice'
];

const errors = [];
const warns = [];

/**
 * 抹注释但保留行号与字符串字面量（铁律 84①）。
 *
 * 🔴 不能用 `/\/\*[\s\S]*?\*\//` 这种朴素正则：本仓源码里 `"/api/v2/ops/**"`、
 * `{@code Path/**}` 都含 `/*`，正则会把**字符串里的 `/**` 当成注释开始**，一路吃到下一个
 * `*\/`，整段代码被抹成空格——门禁于是「扫到空文件」而假绿（首版实测：拦截器里
 * `requireDeviceAccess(` / `OPS_PREFIX` 全读不到，四条判据一起假红）。
 * 必须按状态机走：只有 code 态且紧跟 `/` 的 `*` 才是注释。
 */
function maskComments(s) {
  const out = s.split('');
  let i = 0;
  let state = 'code';
  while (i < s.length) {
    const c = s[i];
    const n = s[i + 1];
    if (state === 'code') {
      if (c === '"') state = 'str';
      else if (c === "'") state = 'chr';
      else if (c === '/' && n === '/') state = 'line';
      else if (c === '/' && n === '*') {
        state = 'block';
        out[i] = ' ';
        out[i + 1] = ' ';
        i += 2;
        continue;
      }
    } else if (state === 'line') {
      if (c === '\n') state = 'code';
      else out[i] = ' ';
    } else if (state === 'block') {
      if (c === '*' && n === '/') {
        out[i] = ' ';
        out[i + 1] = ' ';
        i += 2;
        state = 'code';
        continue;
      }
      if (c !== '\n') out[i] = ' ';
    } else if (state === 'str') {
      if (c === '\\') {
        i += 2;
        continue;
      }
      if (c === '"') state = 'code';
    } else if (state === 'chr') {
      if (c === '\\') {
        i += 2;
        continue;
      }
      if (c === "'") state = 'code';
    }
    i++;
  }
  return out.join('');
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (name.endsWith('.java')) out.push(p);
  }
  return out;
}

function bodyOf(src, openIdx) {
  let depth = 0;
  for (let i = openIdx; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(openIdx, i + 1);
    }
  }
  return src.slice(openIdx);
}

// ── 全仓 Java 索引（类名 → 源码 / 字段表），供 2 跳解析 ──
const javaFiles = walk(TRADE_SRC);
const byClass = new Map();
const srcOf = new Map();
for (const f of javaFiles) {
  const cls = f.match(/([A-Za-z0-9_]+)\.java$/)[1];
  if (!byClass.has(cls)) byClass.set(cls, f);
  srcOf.set(f, maskComments(readFileSync(f, 'utf8')));
}

function methodBody(cls, name) {
  const f = byClass.get(cls);
  if (!f) return null;
  const src = srcOf.get(f);
  // 🔴 必须锚在修饰符上：只按 `\bname\s*\(` 找会把**调用点**当定义。
  // AdminDashboardService 里第一次出现 `listSessions(` 是别的方法里的
  // `sessionOrderQueryService.listSessions(operatorId, query);`，其后紧跟的 `{`
  // 属于下一个方法——取到的是别人的方法体，真校验被读成「没有校验」（首版实测 11 条假红）。
  const re = new RegExp(`(?:public|private|protected)\\s+[^;{}()]*?\\b${name}\\s*\\(`, 'g');
  // 同名重载全部拼接：`listSessions(Long,int,int,String,SessionState)` 只是转发，
  // 真正判范围的是 `listSessions(Long, SessionListQuery)`。只看第一个重载会漏掉校验方。
  const bodies = [];
  let m;
  while ((m = re.exec(src))) {
    const brace = src.indexOf('{', m.index + m[0].length - 1);
    if (brace < 0) continue;
    bodies.push(bodyOf(src, brace));
  }
  return bodies.length ? bodies.join('\n') : null;
}

function fieldsOf(cls) {
  const f = byClass.get(cls);
  const out = new Map();
  if (!f) return out;
  for (const m of srcOf
    .get(f)
    .matchAll(
      /(?:private|protected|public)\s+(?:final\s+)?([A-Z][A-Za-z0-9_]*)\s+([a-z][A-Za-z0-9_]*)\s*;/g
    )) {
    out.set(m[2], m[1]);
  }
  return out;
}

const hasScopeCall = (body) => !!body && SCOPE_APIS.some((a) => body.includes(`${a}(`));

/** 方法（≤2 跳）内是否过设备范围判据：自身 → 字段上调 → 同类私有方法。 */
function enforcedWithin(cls, method, depth = 0, visited = new Set()) {
  // key 带 depth：同名重载是不同方法（`listSessions(operatorId, query)` 只是转发到
  // `listSessions(Long, SessionListQuery)`，后者才真正调 intersectDeviceFilter）。
  // 只按 `cls#method` 去重会把重载当环，转发层一律判「没校验」（首版实测 11 条假红）。
  const key = `${cls}#${method}@${depth}`;
  // 深度 4 与 check-write-endpoint-attribution 同口径：facade 转发（Controller→DashboardService
  // →QueryService→Scope）在运营台是常态，2 跳会把真校验判成「没校验」（首版实测 11 条假红）。
  if (visited.has(key) || depth > 4) return false;
  visited.add(key);
  const body = methodBody(cls, method);
  if (!body) return false;
  if (hasScopeCall(body)) return true;
  const fields = fieldsOf(cls);
  for (const m of body.matchAll(/\b([a-z][A-Za-z0-9_]*)\.([A-Za-z0-9_]+)\s*\(/g)) {
    const type = fields.get(m[1]);
    if (!type || !byClass.has(type)) continue;
    if (enforcedWithin(type, m[2], depth + 1, visited)) return true;
  }
  for (const m of body.matchAll(/(?<![.\w])([a-z][A-Za-z0-9_]+)\s*\(/g)) {
    if (
      ['if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'throw', 'super', 'this'].includes(
        m[1]
      )
    ) {
      continue;
    }
    if (enforcedWithin(cls, m[1], depth + 1, visited)) return true;
  }
  return false;
}

// ────────── R1 拦截器本体 ──────────
if (!existsSync(INTERCEPTOR)) {
  errors.push(`R1 拦截器缺失：${relative(ROOT, INTERCEPTOR)}（柜机对象级授权统一闸被删）`);
} else {
  const src = srcOf.get(INTERCEPTOR) || maskComments(readFileSync(INTERCEPTOR, 'utf8'));
  if (!src.includes('requireDeviceAccess(')) {
    errors.push('R1 拦截器不再调用 requireDeviceAccess( —— 闸形同虚设');
  }
  if (!src.includes('URI_TEMPLATE_VARIABLES_ATTRIBUTE')) {
    errors.push(
      'R1 拦截器未从 URI_TEMPLATE_VARIABLES_ATTRIBUTE 取路径变量 —— 取不到 {deviceId} 就永远放行'
    );
  }
  if (!/DEVICE_VAR\s*=\s*"deviceId"/.test(src) && !src.includes('"deviceId"')) {
    errors.push('R1 拦截器未使用 deviceId 作为路径变量名');
  }
  if (!/HttpStatus\.UNAUTHORIZED/.test(src)) {
    errors.push('R1 拦截器对「无操作员身份」未 fail-closed（必须 401，不能静默放行）');
  }
  if (/request\.getParameter\(\s*"deviceId"/.test(src)) {
    errors.push(
      'R1 拦截器读了 request.getParameter("deviceId") —— 会触发 form body 解析、吃掉 @RequestBody'
    );
  }
}

// ────────── R2 注册面 ──────────
const webSrc = existsSync(WEB_CONFIG)
  ? srcOf.get(WEB_CONFIG) || maskComments(readFileSync(WEB_CONFIG, 'utf8'))
  : '';
if (!webSrc) {
  errors.push(`R2 找不到 ${relative(ROOT, WEB_CONFIG)}`);
} else {
  const registration = webSrc.match(
    /addInterceptor\(\s*opsDeviceScopeInterceptor\s*\)([\s\S]{0,400}?);/
  );
  if (!registration) {
    errors.push(
      'R2 拦截器未在 WebConfig#addInterceptors 注册（加回 .addInterceptor(opsDeviceScopeInterceptor)）'
    );
  } else {
    const chain = registration[1];
    if (!chain.includes('OPS_PREFIX')) {
      errors.push('R2 注册未使用 OpsDeviceScopeInterceptor.OPS_PREFIX —— 路径口径两处维护');
    }
    const orderMatch = chain.match(/\.order\(\s*(-?\d+)\s*\)/);
    if (!orderMatch) {
      warns.push('R2 注册未显式 .order()，与 AuthInterceptor 同为 0 时靠插入顺序，易被重排打乱');
    } else if (Number(orderMatch[1]) <= 0) {
      errors.push(
        `R2 注册 order=${orderMatch[1]} ≤ 0：会先于 AuthInterceptor 执行而取不到身份（全站运营后台 401）`
      );
    }
    if (chain.includes('excludePathPatterns')) {
      errors.push('R2 注册上出现 excludePathPatterns —— 排除清单就是静默绕过口');
    }
  }
}

// ────────── R3 前缀一致性 ──────────
if (!existsSync(INTERCEPTOR)) {
  // R1 已报，跳过
} else {
  const src = srcOf.get(INTERCEPTOR) || '';
  const prefixMatch = src.match(/OPS_PREFIX\s*=\s*"([^"]+)"/);
  if (!prefixMatch) {
    errors.push('R3 OPS_PREFIX 常量缺失');
  } else if (prefixMatch[1] !== '/api/v2/ops/**') {
    errors.push(`R3 OPS_PREFIX=${prefixMatch[1]}，期望 /api/v2/ops/**（改前缀等于整批端点脱管）`);
  }
}

const apiFiles = walk(API_DIR);
if (!SKIP_SANITY && apiFiles.length < 40) {
  errors.push(`R3 只扫到 ${apiFiles.length} 个控制器文件（<40）—— 目录结构变了，门禁会假绿`);
}
const opsControllers = [];
const foreignPrefix = [];
for (const f of apiFiles) {
  const src = srcOf.get(f) || maskComments(readFileSync(f, 'utf8'));
  const cls = f.match(/([A-Za-z0-9_]+)\.java$/)[1];
  const anno = src.match(/@RequestMapping\s*\(\s*"([^"]+)"/);
  if (!anno) continue;
  const prefix = anno[1];
  if (prefix.startsWith('/api/v2/ops')) opsControllers.push({ cls, prefix, src });
  else if (/\/ops(\/|$|\*)/.test(prefix)) foreignPrefix.push(`${cls} (${prefix})`);
}
if (!SKIP_SANITY && opsControllers.length < 30) {
  errors.push(
    `R3 只识别到 ${opsControllers.length} 个运营控制器（<30）—— 前缀口径变了，门禁会假绿`
  );
}
if (foreignPrefix.length) {
  errors.push(`R3 运营控制器不在 /api/v2/ops 前缀下（拦截器管不到）：${foreignPrefix.join('、')}`);
}

// ────────── R4 查询串 deviceId 例外 ──────────
/**
 * 从方法级 mapping 注解往后取「真实处理方法」的形参表。
 *
 * 🔴 不能用 `^[^;{]*?\b(\w+)\s*\(` 这种懒匹配：它会在 `@GetMapping(` 处就命中，
 * 把方法名解析成 "GetMapping"、形参表解析成注解自己的参数表——于是每个端点都被跳过，
 * R4 恒绿（首版实测：注入「@RequestParam deviceId 且不校验」的运营端点仍然 exit 0）。
 * 正确做法：先找 parenDepth=0 的方法体 `{`，再从它往前配平到形参表的 `(`。
 */
function handlerParamsAt(src, annoEnd) {
  let depth = 0;
  let bodyOpen = -1;
  for (let i = annoEnd + 1; i < src.length; i++) {
    const c = src[i];
    if (c === '(') depth++;
    else if (c === ')') depth--;
    else if (c === ';' && depth === 0)
      return null; // 注解在字段/形参上，不是方法
    else if (c === '{' && depth === 0) {
      bodyOpen = i;
      break;
    }
  }
  if (bodyOpen < 0) return null;
  let j = bodyOpen - 1;
  while (j > 0 && /\s/.test(src[j])) j--;
  if (src[j] !== ')') return null;
  let d = 0;
  let k = j;
  for (; k >= 0; k--) {
    if (src[k] === ')') d++;
    else if (src[k] === '(') {
      d--;
      if (d === 0) break;
    }
  }
  if (k < 0) return null;
  const nameMatch = src.slice(0, k).match(/([A-Za-z0-9_]+)\s*$/);
  if (!nameMatch) return null;
  return { name: nameMatch[1], params: src.slice(k + 1, j) };
}

const baseline = loadBaseline();
const queryParamOffenders = [];
for (const { cls, src } of opsControllers) {
  const ANNO = /@(?:Get|Post|Put|Patch|Delete)Mapping\b/g;
  let m;
  while ((m = ANNO.exec(src))) {
    // 注解自己的括号区间（可能无括号：@PostMapping）
    let annoEnd = m.index + m[0].length - 1;
    if (src[annoEnd] !== '(') {
      annoEnd = m.index + m[0].length - 1;
    } else {
      let d = 0;
      for (let i = annoEnd; i < src.length; i++) {
        if (src[i] === '(') d++;
        else if (src[i] === ')') {
          d--;
          if (!d) {
            annoEnd = i;
            break;
          }
        }
      }
    }
    const h = handlerParamsAt(src, annoEnd);
    if (!h) continue;
    const takesQueryDeviceId = /@RequestParam\s*\([^)]*"deviceId"[^)]*\)/.test(h.params);
    if (!takesQueryDeviceId) continue;
    if (/@PathVariable\s*\(\s*(?:name\s*=\s*)?"deviceId"/.test(h.params)) continue;
    if (enforcedWithin(cls, h.name)) continue;
    if (baseline.has(`${cls}#${h.name}`)) continue;
    queryParamOffenders.push(`${cls}#${h.name}`);
  }
}
if (queryParamOffenders.length) {
  errors.push(
    `R4 以下运营端点把 deviceId 放在 @RequestParam（拦截器看不见）且自身不过范围判据：\n    ` +
      queryParamOffenders.join('\n    ') +
      `\n    → 改成 @PathVariable，或在方法体调 requireDeviceFilter/requireDeviceAccess，` +
      `或在 ${BASELINE_PATH} 带原因登记`
  );
}

// 基线陈旧：登记了但已修好 → 要求删除，防基线变成「永久豁免」
for (const key of baseline.keys()) {
  const [cls, method] = key.split('#');
  if (!byClass.has(cls)) {
    warns.push(`R4 基线条目 ${key} 的类已不存在（删掉该行）`);
    continue;
  }
  if (enforcedWithin(cls, method)) {
    warns.push(`R4 基线条目 ${key} 已过范围判据，请从基线删除（棘轮只降不升）`);
  }
}

function loadBaseline() {
  const p = join(ROOT, BASELINE_PATH);
  if (!existsSync(p)) return new Map();
  const json = JSON.parse(readFileSync(p, 'utf8'));
  return new Map(Object.entries(json.entries || {}));
}

// ────────── 输出 ──────────
if (!errors.length && !warns.length) {
  console.log(`${TAG} OK：拦截器本体/注册面/前缀口径/查询串例外四类判据全部通过`);
}
for (const w of warns) console.log(`${TAG} WARN ${w}`);
if (errors.length) {
  for (const e of errors) console.error(`${TAG} FAIL ${e}`);
  process.exit(1);
}
