#!/usr/bin/env node
/**
 * 门禁：渠道 HTTP 不得留在 `@Transactional` 方法体里（F3b）。
 *
 * 为什么需要静态判据：渠道扣款/退款/建单都是**外部事实**——HTTP 返回成功后本地事务仍可能
 * 回滚，于是「钱已动、账没有」。F3a 用 CHARGE_PENDING 痕迹 + after-commit 收口把这条缝变成
 * 可巡检；本门禁负责**不再新增**这类调用形态，存量按 `tx-boundary-channel-call-baseline.json`
 * 带理由登记（棘轮只降不升）。
 *
 * 判据：
 *   R1 `@Transactional` 方法体（含 1 跳 self./this. 调用展开）出现渠道客户端/资金调用 → 红
 *   R2 基线里登记的方法必须仍真实存在且仍命中 R1（防止修完后忘记摘豁免，变永久免检）
 *   R3 扫描面自检：`@Transactional` 方法数 / 渠道调用数低于下限即报「扫描面塌了」
 *
 * 注意：扫源码前统一 maskComments()（等长空格保行号），否则注释里的示例调用会造假红。
 *
 * 用法：
 *   node scripts/check-tx-boundary-channel-call.mjs
 *   node scripts/check-tx-boundary-channel-call.mjs --self-test
 *   GATE_ROOT=<sandbox> node scripts/check-tx-boundary-channel-call.mjs
 */
import { existsSync, readFileSync, statSync, readdirSync } from 'node:fs';
import { dirname, join, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'services', 'trade-service', 'src', 'main', 'java');
const BASELINE = join(ROOT, 'scripts', 'tx-boundary-channel-call-baseline.json');

/** 等长空格覆盖注释和字符串字面量，避免注释里的示例/字面量里的 URL 触发假红。 */
export function maskComments(src) {
  const out = src.split('');
  let i = 0;
  let state = 'code';
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (state === 'code') {
      if (c === '"') state = 'str';
      else if (c === "'") state = 'chr';
      else if (c === '/' && n === '/') {
        state = 'line';
        // 行注释的首个 '/' 必须当场涂掉：下一轮才进 line 分支，漏这一个字符会
        // 让 `// client.fetch()` 残留一个 `client`，判据把注释当真代码（夹具实测）。
        out[i] = ' ';
      }
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
        out[i] = ' ';
        if (i + 1 < out.length) out[i + 1] = ' ';
        i += 2;
        continue;
      }
      if (c === '"') state = 'code';
      else if (c !== '\n') out[i] = ' ';
    } else if (state === 'chr') {
      if (c === '\\') {
        out[i] = ' ';
        if (i + 1 < out.length) out[i + 1] = ' ';
        i += 2;
        continue;
      }
      if (c === "'") state = 'code';
      else if (c !== '\n') out[i] = ' ';
    }
    i++;
  }
  return out.join('');
}

/** 渠道调用的形态：明确列举，而不是 `.*Client` 通配（通配会把本地 DTO Client 也算进来）。 */
const CHANNEL_METHOD_CALL =
  /\b(?:chargeOrderWithPlan|chargeOrder|submitChannelCharge|refundOrder|refundAmount|completeWechatOrderForSession|createServiceOrder)\s*\(/g;
/** 字段名不一定叫 xxxClient：如 AdRevenueSyncService 的字段就叫 client，须从字段声明反查类型。 */
const CLIENT_FIELD_RE =
  /\b(?:private|protected|public)\s+(?:final\s+)?(?:[A-Za-z0-9_]+\.)*([A-Z][A-Za-z0-9_]*Client)\s+(\w+)\s*[;=]/g;

export function clientFieldNames(src) {
  return [...new Set([...src.matchAll(CLIENT_FIELD_RE)].map((m) => m[2]))];
}

const METHOD_RE =
  /@Transactional\b[\s\S]{0,500}?\b(?:public|private|protected|static|\s)+[\w<>\[\],\s.?]+\s+(\w+)\s*\([^)]*\)\s*\{/g;
const SELF_CALL_RE = /\b(?:self|this)\s*\.\s*(\w+)\s*\(/g;

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.java')) out.push(p);
  }
  return out;
}

/** 返回方法体（含大括号配对），以及方法内的 self 调用名。 */
export function methodBody(src, openBraceIndex) {
  let depth = 0;
  for (let i = openBraceIndex; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(openBraceIndex + 1, i);
    }
  }
  return src.slice(openBraceIndex + 1);
}

/** 收集某个类里所有方法的 body，供 1 跳 self 展开。 */
function allMethodBodies(src) {
  const map = new Map();
  const re = /\b(?:public|private|protected)\s+[\w<>\[\],\s.?]+\s+(\w+)\s*\([^)]*\)\s*\{/g;
  let m;
  while ((m = re.exec(src))) {
    map.set(m[1], methodBody(src, re.lastIndex - 1));
  }
  return map;
}

export function channelCalls(body, clientFields = []) {
  const hits = [...body.matchAll(CHANNEL_METHOD_CALL)].map((m) => m[0].trim());
  if (clientFields.length) {
    // 类型名直接调用（静态工具）与字段名调用（实例客户端）都要认。
    const re = new RegExp('\\b(?:' + clientFields.join('|') + ')\\s*\\.\\s*\\w+\\s*\\(', 'g');
    hits.push(
      ...[...body.matchAll(re)]
        // isXxx/hasXxx/getXxx 是本地能力探测（如 alipayPayClient.isConfigured()），
        // 不发外部资金动作，判红它们会把门禁变成「不可关的噪音」。
        .filter((m) => !/\.\s*(?:is|has|get|resolve)[A-Z]\w*\s*\(/.test(m[0]))
        .map((m) => m[0].trim())
    );
  }
  // 同一个调用可能被「方法名形态」和「字段名形态」各抓一次，按被调用方法名去重。
  const byName = new Map();
  for (const h of hits) {
    const name = h.match(/(\w+)\s*\($/)?.[1] || h;
    if (!byName.has(name)) byName.set(name, h);
  }
  return [...byName.values()];
}

export function selfCalls(body) {
  return [...new Set([...body.matchAll(SELF_CALL_RE)].map((m) => m[1]))];
}

/** 真实树扫描：返回 { offenders, txMethods, channelMethods, methodIndex }。 */
export function evaluate() {
  const offenders = [];
  const methodIndex = new Map();
  let txMethods = 0;
  let channelMethods = 0;

  for (const file of walk(SRC)) {
    const rel = relative(ROOT, file).replace(/\\/g, '/');
    const src = maskComments(readFileSync(file, 'utf8'));
    const bodies = allMethodBodies(src);
    const clientFields = clientFieldNames(src);
    for (const [name, body] of bodies) methodIndex.set(`${rel}#${name}`, body);

    METHOD_RE.lastIndex = 0;
    let m;
    while ((m = METHOD_RE.exec(src))) {
      txMethods++;
      const name = m[1];
      const body = methodBody(src, METHOD_RE.lastIndex - 1);
      let calls = channelCalls(body, clientFields);
      const hops = [];
      for (const target of selfCalls(body)) {
        const nested = bodies.get(target);
        if (nested && channelCalls(nested, clientFields).length) {
          calls = calls.concat(channelCalls(nested, clientFields).map((c) => `${target}→${c}`));
          hops.push(target);
        }
      }
      calls = [...new Set(calls)];
      if (!calls.length) continue;
      channelMethods++;
      offenders.push({
        key: `${rel}#${name}`,
        calls,
        hops,
        line: src.slice(0, m.index).split('\n').length
      });
    }
  }
  return { offenders, txMethods, channelMethods, methodIndex };
}

/** 基线校验：条目必须仍对应一个真实命中的方法（R2 棘轮）。 */
export function checkBaseline(offenders) {
  const errors = [];
  if (!existsSync(BASELINE)) {
    errors.push(`缺少基线文件 ${relative(ROOT, BASELINE)}：首次落地必须显式登记存量，不能静默放行`);
    return errors;
  }
  let baseline;
  try {
    baseline = JSON.parse(readFileSync(BASELINE, 'utf8'));
  } catch (e) {
    return [`基线 JSON 解析失败：${e.message}`];
  }
  const live = new Set(offenders.map((o) => o.key));
  for (const entry of baseline.entries || []) {
    if (!entry.key || !entry.reason) {
      errors.push(`基线条目缺 key 或 reason：${JSON.stringify(entry)}`);
      continue;
    }
    if (!live.has(entry.key)) {
      errors.push(`基线条目已不再命中（修复后请摘掉豁免，防永久免检）：${entry.key}`);
    }
  }
  for (const o of offenders) {
    if (!(baseline.entries || []).some((e) => e.key === o.key)) {
      errors.push(
        `R1 ${o.key}:${o.line} 在 @Transactional 内调渠道 ${o.calls.join(', ')}` +
          (o.hops.length ? `（1 跳 self: ${o.hops.join(', ')}）` : '')
      );
    }
  }
  return errors;
}

/** 扫描面自检（R3）：方法数塌了就是正则失配，门禁会假绿。 */
export function scanSanity(txMethods, channelMethods) {
  const errors = [];
  if (txMethods < 100) {
    errors.push(`R3 只识别到 ${txMethods} 个 @Transactional 方法（<100）——扫描面塌了`);
  }
  if (channelMethods < 5) {
    errors.push(`R3 只识别到 ${channelMethods} 个「事务内调渠道」方法（<5）——扫描面塌了`);
  }
  return errors;
}

/** 内联自检：不碰真实树，验解析函数与判据本身。 */
export function selfTest() {
  const assert = (cond, msg) => {
    if (!cond) throw new Error(`[check-tx-boundary-channel-call self-test] ${msg}`);
  };
  const masked = maskComments(
    '// client.fetchX();\n@Transactional\nvoid a() { client.fetchX(); String s = "client.fetchY()"; }'
  );
  assert(!/fetchX/.test(masked.split('\n')[0]), '行注释未被遮蔽');
  assert(!/fetchY/.test(masked), '字符串字面量未被遮蔽');
  assert(/fetchX\(\)/.test(masked.split('\n')[2] || masked), '真实代码被误遮蔽');
  const src = 'private final com.x.WeChatPayClient weChatPayClient;\nprivate final Client field2;';
  assert(clientFieldNames(src).includes('weChatPayClient'), '全限定类型字段名未识别');
  const body = 'void a() { if (x) { y(); } } void b() {}';
  assert(/if \(x\)/.test(methodBody(body, body.indexOf('{'))), '大括号配对错误');
  assert(
    channelCalls('client.isConfigured(); client.fetchX();', ['client']).length === 1,
    '能力探测被当成渠道调用'
  );
  assert(channelCalls('client.fetchX();', ['client'])[0].includes('fetchX'), '字段调用未识别');
  assert(selfCalls('self.run(); this.go();').join(',') === 'run,go', 'self 调用解析错误');
  console.log('[check-tx-boundary-channel-call self-test] OK：9 项断言全过');
}

export function runCli() {
  if (process.argv.includes('--self-test')) {
    selfTest();
    return;
  }
  const { offenders, txMethods, channelMethods } = evaluate();
  const errors = [...scanSanity(txMethods, channelMethods), ...checkBaseline(offenders)];
  if (errors.length) {
    console.error(`[check-tx-boundary-channel-call] ${errors.length} 项失败：`);
    for (const e of errors) console.error(`  - ${e}`);
    process.exitCode = 1;
  } else {
    console.log(
      `[check-tx-boundary-channel-call] OK：${txMethods} 个事务方法，` +
        `${offenders.length} 处存量已登记（棘轮），无新增`
    );
  }
}

const invokedDirectly =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) runCli();
