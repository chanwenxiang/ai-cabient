#!/usr/bin/env node
/**
 * 门禁：会话续期契约（冷启动不拿过期票撞接口 / 续期失败分档不压平）。
 *
 * 背景（2026-11-11 实证）：商户端开发者工具控制台每次冷启动必报
 *   `GET http://192.168.0.26/api/v2/dicts/runtime 401`
 * 网关日志证明它是**设计内自愈**：401 后 1 秒内必跟一次 200（`401 81B` → `200 91KB`）。
 * 成因链：会话 JWT 30 分钟到期 → `App.onLaunch` 的 `loadRuntimeDict()` 是唯一「启动即发」的
 * 带鉴权请求 → 先吃 401 → `refreshTokenSilently` 换票 → 重放 200。
 * 开发者工具对 4xx 的原生打印 JS 侧压不掉，只能在源头**不发那次必败请求**。
 *
 * 同一轮还发现第二处隐患（未在该环境触发）：`refreshTokenSilently` 把**任何**非
 * (200 & code=0) 的刷新响应压成 `401 UNAUTHORIZED` —— 网关 `auth_ratelimit`(5r/s) 的 429、
 * 上游 5xx、契约异常（200 但无 token）都被 `isMpAuthFailure` 判真 ⇒ 清会话 + 踢回登录页。
 *
 * 判据（三条，全部作用在「谁负责什么」的边界上，不数关键词）：
 *   R1 冷启动入口：`clients/merchant-mp/src/utils/dict-runtime.ts` 的 `loadRuntimeDict`
 *      必须 **先** `await ensureSessionFresh()` 再 `sharedLoadRuntimeDict(...)`，
 *      且该函数必须来自 `@/utils/merchant-api`（禁止本地造一个空实现绕过）。
 *   R2 分档：`packages/shared-uni/src/request.ts` 的 `refreshTokenSilently` 内，
 *      每个 `'UNAUTHORIZED'` 必须处在「显式 401/403 判定」的邻近分支里；
 *      无条件 reject 401 会重演「限流当失效」。
 *   R3 判定面：`isMpAuthFailure` 只认 401/403（禁止改成 `>= 400`，否则 5xx 也会登出）。
 *
 * 扫描前统一 `maskComments()` 抹注释（替换为等长空格、保留换行）：本文件自己的注释里
 * 就写着 `401 UNAUTHORIZED` 与 `await ensureSessionFresh()`，不抹会自己把自己判红。
 *
 * 边界：消费端 `consumer-mp` **不在** R1 范围——它的 `getToken()` 自带
 * `parseConsumerExpiresAt/isConsumerBearerExpired`，过期即清会话返回空串，
 * `loadRuntimeDict` 的前置守卫本来就不会发请求（这也是 401 只出现在商户端 appid 的原因）。
 *
 * 用法：node scripts/check-session-refresh-contract.mjs
 * 负向验证：node scripts/verify-session-refresh-contract.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[check-session-refresh-contract]';
const ROOT = process.env.SESSION_REFRESH_GATE_ROOT
  ? process.env.SESSION_REFRESH_GATE_ROOT
  : join(dirname(fileURLToPath(import.meta.url)), '..');

const DICT_RUNTIME = join(ROOT, 'clients', 'merchant-mp', 'src', 'utils', 'dict-runtime.ts');
const REQUEST_TS = join(ROOT, 'packages', 'shared-uni', 'src', 'request.ts');

const problems = [];

function readOrFail(path, label) {
  try {
    return readFileSync(path, 'utf8');
  } catch {
    problems.push(`${label} 不存在：${path}`);
    return '';
  }
}

/**
 * 注释抹除器：行注释与块注释的内容替换为**等长空格**（保留换行 ⇒ 行号不变），
 * 字符串/模板字符串原样保留（避免把 `'//'` 之类误判成注释）。
 */
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
    // state === 'str'
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

/** 从 `marker` 起取函数体：按花括号配平（跳过字符串），找不到返回 ''。 */
function sliceFunctionBody(code, marker) {
  const start = code.indexOf(marker);
  if (start < 0) return '';
  const braceStart = code.indexOf('{', start);
  if (braceStart < 0) return '';
  let depth = 0;
  let i = braceStart;
  while (i < code.length) {
    const c = code[i];
    if (c === '{') depth += 1;
    else if (c === '}') {
      depth -= 1;
      if (depth === 0) return code.slice(braceStart, i + 1);
    }
    i += 1;
  }
  return '';
}

// ── R1：冷启动入口必须先续期，且续期能力来自 merchant-api ──────────────────
const dictRaw = readOrFail(DICT_RUNTIME, '商户端 dict-runtime.ts');
if (dictRaw) {
  const dict = maskComments(dictRaw);
  if (!/loadRuntimeDict/.test(dict)) {
    problems.push(
      'R1：dict-runtime.ts 里找不到 loadRuntimeDict —— 结构被改，门禁失效，请同步更新判据'
    );
  }
  const ensureIdx = dict.indexOf('await ensureSessionFresh(');
  const loadIdx = dict.indexOf('sharedLoadRuntimeDict(');
  if (ensureIdx < 0) {
    problems.push(
      'R1：loadRuntimeDict 缺少 `await ensureSessionFresh()` —— 冷启动会用过期 JWT 撞业务接口，控制台必现红色 401'
    );
  } else if (loadIdx >= 0 && ensureIdx > loadIdx) {
    problems.push('R1：`await ensureSessionFresh()` 必须排在 sharedLoadRuntimeDict(...) 之前');
  }
  const importRe =
    /import\s*\{[^}]*\bensureSessionFresh\b[^}]*\}\s*from\s*['"]@\/utils\/merchant-api['"]/;
  if (!importRe.test(dict)) {
    problems.push(
      "R1：ensureSessionFresh 必须从 '@/utils/merchant-api' 导入（禁止本地空实现绕过）"
    );
  }
}

// ── R2/R3：请求层分档 ──────────────────────────────────────────────────────
const requestRaw = readOrFail(REQUEST_TS, 'shared-uni request.ts');
if (requestRaw) {
  const code = maskComments(requestRaw);

  // R2：refreshTokenSilently 内 'UNAUTHORIZED' 必须与 401/403 判定同处一个分支窗口
  const refreshBody = sliceFunctionBody(code, 'export async function refreshTokenSilently');
  if (!refreshBody) {
    problems.push('R2：找不到 refreshTokenSilently 函数体 —— 结构被改，门禁失效，请同步更新判据');
  } else {
    const lines = refreshBody.split('\n');
    lines.forEach((line, idx) => {
      if (!/['"]UNAUTHORIZED['"]/.test(line)) return;
      const from = Math.max(0, idx - 8);
      const window = lines.slice(from, idx + 1).join('\n');
      const guarded = /statusCode\s*===\s*(401|403)/.test(window);
      if (!guarded) {
        problems.push(
          `R2：refreshTokenSilently 第 ${idx + 1} 行的 UNAUTHORIZED 不在 401/403 判定分支内 —— ` +
            '无条件压平会把网关 429/5xx 当成会话失效并强制登出'
        );
      }
    });
    if (!/statusCode\s*===\s*401/.test(refreshBody)) {
      problems.push('R2：refreshTokenSilently 必须显式分档 401（真失效），不能只靠兜底 reject');
    }
  }

  // R3：isMpAuthFailure 只认 401/403
  const authBody = sliceFunctionBody(code, 'export function isMpAuthFailure');
  if (!authBody) {
    problems.push('R3：找不到 isMpAuthFailure 函数体 —— 结构被改，门禁失效，请同步更新判据');
  } else {
    if (!/===\s*401/.test(authBody) || !/===\s*403/.test(authBody)) {
      problems.push('R3：isMpAuthFailure 必须精确判定 401 / 403');
    }
    if (/status\s*>=\s*400/.test(authBody)) {
      problems.push(
        'R3：isMpAuthFailure 不得用 status >= 400 兜底 —— 5xx/429 属于可重试失败，判成鉴权失效会误登出'
      );
    }
  }
}

if (problems.length) {
  console.error(`${TAG} FAIL: 会话续期契约被破坏（${problems.length} 条）`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}

console.log(`${TAG} OK（R1 冷启动续期 / R2 刷新分档 / R3 鉴权判定面）`);
