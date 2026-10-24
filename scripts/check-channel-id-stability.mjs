#!/usr/bin/env node
/**
 * 渠道单号 / 幂等键稳定性门禁。
 *
 * 缺陷类（lessons #97 / #225，已真实复现）：
 *   - 退款 `out_refund_no`、分账回退 `out_return_no`、代付 `idem_key` 是**资金侧的幂等键**。
 *     重试时只要单号变了，渠道就当成一笔**新业务** ⇒ 重复退款 / 重复打款窗口。
 *   - 另一条更隐蔽的路径是「没传单号」（null/空串）时下游兜底生成随机号：第一次调用拿到
 *     随机号，重试再换一个，渠道侧依然无法识别为同一笔。
 *
 * 判据（缺一即红）：
 *   1. 赋值/传入渠道单号或幂等键的表达式，禁止出现随机/时间源
 *      （`BizIds.*`、`UUID.randomUUID`、`System.currentTimeMillis`、`Instant.now`…）。
 *   2. 确定性生成器必须仍是确定性的：`PayoutAccountService.newIdemKey`、
 *      `PaymentService.fullRefundNo`、`OrderPaymentService.deterministicRefundNo`
 *      的方法体里同样禁止随机/时间源。
 *   3. fail-closed 守卫必须存在且**真的被调用**：`requireStableRefundNo` /
 *      `requireStableReturnNo` 体内必须有 `throw`，且所有渠道退款入口都要先过它 ——
 *      否则空单号会一路裸奔到渠道客户端。
 *
 * 永远 fail-closed：锚点方法/调用点找不到、扫描器一个目标都没命中，都按失败处理
 * （那是门禁自己失效，不是「没问题」）。
 *
 *   node scripts/check-channel-id-stability.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-channel-id-stability]';
const SERVICES = join(root, 'services');

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const TRADE = 'services/trade-service/src/main/java/com/aicabinet/trade';
const FILES = {
  payoutAccount: `${TRADE}/service/PayoutAccountService.java`,
  payment: `${TRADE}/service/PaymentService.java`,
  orderPayment: `${TRADE}/service/OrderPaymentService.java`,
  lineWithdraw: `${TRADE}/service/LineWithdrawService.java`,
  merchantWithdraw: `${TRADE}/service/MerchantWithdrawService.java`,
  profitSharing: `${TRADE}/payment/WeChatProfitSharingService.java`
};

/* ------------------------------------------------------------------ *
 * 代码结构工具（掩码保持字符偏移，行号/位置两套文本通用）
 * ------------------------------------------------------------------ */
function maskNonCode(src) {
  const out = [];
  let state = 'code';
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') {
        out.push('  ');
        i += 2;
        state = 'line';
        continue;
      }
      if (c === '/' && n === '*') {
        out.push('  ');
        i += 2;
        state = 'block';
        continue;
      }
      if (c === '"' && src.startsWith('"""', i)) {
        out.push('   ');
        i += 3;
        state = 'text';
        continue;
      }
      if (c === '"') {
        out.push(' ');
        i += 1;
        state = 'str';
        continue;
      }
      if (c === "'") {
        out.push(' ');
        i += 1;
        state = 'chr';
        continue;
      }
      out.push(c);
      i += 1;
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        out.push('\n');
        state = 'code';
      } else out.push(' ');
      i += 1;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') {
        out.push('  ');
        i += 2;
        state = 'code';
      } else {
        out.push(c === '\n' ? '\n' : ' ');
        i += 1;
      }
      continue;
    }
    if (state === 'str') {
      if (c === '\\') {
        out.push('  ');
        i += 2;
      } else if (c === '"') {
        out.push(' ');
        i += 1;
        state = 'code';
      } else {
        out.push(c === '\n' ? '\n' : ' ');
        i += 1;
      }
      continue;
    }
    if (state === 'chr') {
      if (c === '\\') {
        out.push('  ');
        i += 2;
      } else if (c === "'") {
        out.push(' ');
        i += 1;
        state = 'code';
      } else {
        out.push(' ');
        i += 1;
      }
      continue;
    }
    if (c === '"' && src.startsWith('"""', i)) {
      out.push('   ');
      i += 3;
      state = 'code';
      continue;
    }
    out.push(c === '\n' ? '\n' : ' ');
    i += 1;
  }
  return out.join('');
}

function matchPair(s, open) {
  const openCh = s[open];
  const closeCh = openCh === '{' ? '}' : ')';
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    if (s[i] === openCh) depth++;
    else if (s[i] === closeCh) {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

const lineOf = (s, index) => s.slice(0, index).split('\n').length;

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'target' || name === 'build') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.java')) out.push(p);
  }
  return out;
}

/** 渠道资金幂等标识（驼峰与 JSON 键两种拼写）。 */
const CHANNEL_ID_ALT = 'outRefundNo|outReturnNo|outRequestNo|outBatchNo|idemKey';
/** setter 名在 `set` 之后首字母大写：`setOutRefundNo` / `setIdemKey`。 */
const CHANNEL_ID_SETTER_ALT = 'OutRefundNo|OutReturnNo|OutRequestNo|OutBatchNo|IdemKey';
const CHANNEL_JSON_ALT = 'out_refund_no|out_return_no|out_request_no|out_batch_no|idem_key';

/**
 * 已核实的豁免：键 = `<归一化路径>#<标识名>`。当前为空。
 *
 * 🔴 豁免不是「懒得修」的垃圾桶。原 PayScore 豁免已于 2026-10-24 撤销：复核微信契约
 * （CB-064）发现 v3《商户预授权》没有 `out_request_no` 字段（要求 `authorization_code`），
 * 代码已改名为 `authorizationCode`，授权协议号不属于本门禁管的资金幂等键
 * （outRefundNo/outReturnNo/outBatchNo/idemKey）。
 *
 * 豁免必须**真的命中**（下面会校验）：文件被改名/规则失效时豁免条目会变成红，
 * 避免它悄悄退化成永久放行。
 */
const EXEMPT = new Map();
const NON_DETERMINISTIC = [
  ['BizIds.', '内部流水号生成器（随机/时间序）'],
  ['UUID.randomUUID', 'UUID 随机'],
  ['randomUUID', 'UUID 随机'],
  ['currentTimeMillis', '当前时间戳'],
  ['nanoTime', '纳秒时间戳'],
  ['Instant.now', '当前时刻'],
  ['LocalDateTime.now', '当前时刻'],
  ['LocalDate.now', '当前日期'],
  ['LocalTime.now', '当前时间'],
  ['Math.random', '随机数'],
  ['new Random', '随机数'],
  ['SecureRandom', '安全随机数'],
  ['new Date(', '当前时刻'],
  ['Clock.system', '系统时钟']
];

function nonDeterministicIn(expr) {
  return NON_DETERMINISTIC.filter(([token]) => expr.includes(token));
}

/** 去重后的原因列表（`UUID.randomUUID` 与 `randomUUID` 会同时命中）。 */
function whyText(hits) {
  return [...new Set(hits.map(([, why]) => why))].join(' / ');
}

/**
 * 收集所有「渠道幂等标识被赋值/传入」的表达式片段。
 * @returns {{line:number, name:string, expr:string}[]}
 */
function collectChannelIdExpressions(raw, sanitized) {
  const hits = [];
  const push = (index, name, expr) => {
    hits.push({ line: lineOf(raw, index), name, expr: expr.replace(/\s+/g, ' ').trim() });
  };

  // 普通赋值：`String outRefundNo = …;` / `this.idemKey = …;`
  const assignRe = new RegExp(`\\b(${CHANNEL_ID_ALT})\\s*=(?!=)\\s*`, 'g');
  let m;
  while ((m = assignRe.exec(sanitized))) {
    const end = sanitized.indexOf(';', m.index + m[0].length);
    push(m.index, m[1], sanitized.slice(m.index + m[0].length, end < 0 ? undefined : end));
  }

  // setter：`request.setIdemKey(…)`
  for (const s of sanitized.matchAll(new RegExp(`\\.set(${CHANNEL_ID_SETTER_ALT})\\s*\\(`, 'g'))) {
    const open = s.index + s[0].length - 1;
    const close = matchPair(sanitized, open);
    if (close < 0) continue;
    push(s.index, s[1][0].toLowerCase() + s[1].slice(1), sanitized.slice(open + 1, close));
  }

  // JSON 键：`body.put("out_refund_no", …)`
  for (const s of raw.matchAll(new RegExp(`\\.put\\s*\\(\\s*"(${CHANNEL_JSON_ALT})"\\s*,`, 'g'))) {
    const open = sanitized.indexOf('(', s.index);
    const close = open < 0 ? -1 : matchPair(sanitized, open);
    if (open < 0 || close < 0) continue;
    push(s.index, s[1], sanitized.slice(s.index + s[0].length, close));
  }

  return hits;
}

/** 提取方法体（按方法名找到声明，再做花括号配对）。返回原文+掩码两份（偏移一致）。 */
function methodBody(raw, sanitized, name, { requireStatic = false } = {}) {
  const declRe = new RegExp(
    `(?:^|\\n)[ \\t]*(?:(?:public|private|protected)\\s+)?(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\],.?]+\\s+${name}\\s*\\(`,
    'g'
  );
  for (const decl of sanitized.matchAll(declRe)) {
    if (requireStatic && !/\bstatic\b/.test(decl[0])) continue;
    const openParen = sanitized.indexOf('(', decl.index + decl[0].length - 1);
    const closeParen = openParen < 0 ? -1 : matchPair(sanitized, openParen);
    if (closeParen < 0) continue;
    const openBrace = sanitized.indexOf('{', closeParen);
    if (openBrace < 0) continue;
    const closeBrace = matchPair(sanitized, openBrace);
    if (closeBrace < 0) continue;
    return {
      line: lineOf(raw, decl.index),
      body: sanitized.slice(openBrace + 1, closeBrace),
      rawBody: raw.slice(openBrace + 1, closeBrace)
    };
  }
  return null;
}

/* ------------------------------------------------------------------ *
 * 自检：先证明检测器本身是对的
 * ------------------------------------------------------------------ */
function selfCheck() {
  const probe = (src) => collectChannelIdExpressions(src, maskNonCode(src));

  const randomHits = probe('void f() {\n  String outRefundNo = BizIds.nextNumeric();\n}\n');
  if (randomHits.length !== 1 || nonDeterministicIn(randomHits[0].expr).length === 0) {
    fail('自检失败：BizIds 生成的 outRefundNo 未被识别');
  }

  const stableHits = probe(
    'void f() {\n  String outRefundNo = "BR" + req.getRequestId() + "-S" + i;\n}\n'
  );
  if (stableHits.length !== 1 || nonDeterministicIn(stableHits[0].expr).length !== 0) {
    fail('自检失败：确定性的 outRefundNo 被误报');
  }

  const setterHits = probe('void f() {\n  req.setIdemKey(UUID.randomUUID().toString());\n}\n');
  if (setterHits.length !== 1 || nonDeterministicIn(setterHits[0].expr).length === 0) {
    fail('自检失败：setter 传入 UUID 未被识别');
  }

  const putHits = probe(
    'void f() {\n  body.put("out_refund_no", System.currentTimeMillis() + "");\n}\n'
  );
  if (putHits.length !== 1 || nonDeterministicIn(putHits[0].expr).length === 0) {
    fail('自检失败：JSON 键传入时间戳未被识别');
  }

  // 注释/字符串里的「赋值」不得计入
  const noise = probe('void f() {\n  // String outRefundNo = BizIds.nextNumeric();\n}\n');
  if (noise.length !== 0) {
    fail('自检失败：注释里的赋值被当成真实赋值');
  }
}
selfCheck();

/* ------------------------------------------------------------------ *
 * 扫描
 * ------------------------------------------------------------------ */
const javaFiles = walk(SERVICES).filter((f) => f.includes(`${join('src', 'main', 'java')}`));
if (javaFiles.length === 0) fail('services/**/src/main/java 下没有 java 文件，门禁锚点失效');

const problems = [];
let totalHits = 0;
const exemptUsed = new Set();

for (const file of javaFiles) {
  const raw = readFileSync(file, 'utf8');
  const sanitized = maskNonCode(raw);
  const hits = collectChannelIdExpressions(raw, sanitized);
  totalHits += hits.length;
  const rel = relative(root, file).replace(/\\/g, '/');
  for (const hit of hits) {
    const bad = nonDeterministicIn(hit.expr);
    if (!bad.length) continue;
    const exemptKey = `${rel}#${hit.name}`;
    if (EXEMPT.has(exemptKey)) {
      exemptUsed.add(exemptKey);
      continue;
    }
    problems.push(
      `${rel}:${hit.line} ${hit.name} = ${hit.expr} —— 渠道幂等键不得用${whyText(
        bad
      )}生成；重试换了单号 = 渠道视角的新业务（双重退款/打款窗口）`
    );
  }
}

for (const key of EXEMPT.keys()) {
  if (!exemptUsed.has(key)) {
    problems.push(
      `豁免 ${key} 没有命中任何违规 —— 文件改名/规则失效后它会变成永久放行，请复核后再决定去留`
    );
  }
}

if (totalHits === 0) {
  fail('一条「渠道幂等标识赋值/传入」都没扫到 —— 接口被重命名时门禁会变假绿，请同步更新本门禁');
}

/**
 * 锚点：确定性生成器 + fail-closed 守卫。
 * callers 列的是「必须调用它」的方法 —— 守卫存在但没人调用等于没有。
 */
const ANCHORS = [
  {
    file: FILES.payoutAccount,
    method: 'newIdemKey',
    static: true,
    must: [[/requestId/, '必须直接用 requestId 组成幂等键']],
    callers: [{ file: FILES.lineWithdraw }, { file: FILES.merchantWithdraw }]
  },
  {
    file: FILES.payment,
    method: 'fullRefundNo',
    static: true,
    must: [[/"RF-FULL-"/, '全额退款单号必须是 RF-FULL-<orderId>']],
    callers: []
  },
  {
    file: FILES.orderPayment,
    method: 'deterministicRefundNo',
    static: true,
    must: [],
    callers: [{ file: FILES.orderPayment }]
  },
  {
    file: FILES.payment,
    method: 'requireStableRefundNo',
    static: true,
    must: [[/\bthrow\b/, '空单号必须抛错（fail-closed）']],
    callers: [{ file: FILES.payment }]
  },
  {
    file: FILES.profitSharing,
    method: 'requireStableReturnNo',
    static: true,
    must: [[/\bthrow\b/, '空单号必须抛错（fail-closed）']],
    callers: [{ file: FILES.profitSharing }]
  }
];

for (const anchor of ANCHORS) {
  const abs = join(root, anchor.file);
  if (!existsSync(abs)) {
    problems.push(`门禁锚点失效：找不到 ${anchor.file}`);
    continue;
  }
  const raw = readFileSync(abs, 'utf8');
  const sanitized = maskNonCode(raw);
  const method = methodBody(raw, sanitized, anchor.method, { requireStatic: anchor.static });
  if (!method) {
    problems.push(`门禁锚点失效：${anchor.file} 里找不到方法 ${anchor.method}`);
    continue;
  }

  const nondet = nonDeterministicIn(method.body);
  if (nondet.length) {
    problems.push(
      `${anchor.file}:${method.line} ${anchor.method} 体内出现${whyText(
        nondet
      )} —— 确定性生成器被改写后，重试会换号`
    );
  }
  for (const [re, why] of anchor.must) {
    if (!re.test(method.rawBody))
      problems.push(`${anchor.file}:${method.line} ${anchor.method} ${why}`);
  }
  for (const caller of anchor.callers) {
    const callerPath = join(root, caller.file);
    if (!existsSync(callerPath)) {
      problems.push(`门禁锚点失效：找不到 ${caller.file}`);
      continue;
    }
    const callerRaw = readFileSync(callerPath, 'utf8');
    const callerSanitized = maskNonCode(callerRaw);
    // 调用点按「文件级」判定：同名重载（如 finalizeLiveOrderRefund 的两个签名）之间会互相委托，
    // 逐方法判定会把「A 重载调用、B 重载转发」误判成没人调用。
    // 🔴 但**定义本身也会被正则命中**：同文件锚点必须减掉声明那一处，否则「守卫还在、
    // 调用点全被删」会假绿（2026-10-24 负向注入实测）。
    const callRe = new RegExp(`\\b(?:[\\w.]+\\.)?${anchor.method}\\s*\\(`, 'g');
    const occurrences = [...callerSanitized.matchAll(callRe)].length;
    const sameFile =
      relative(root, callerPath).replace(/\\/g, '/') === anchor.file.replace(/\\/g, '/');
    const calls = occurrences - (sameFile ? 1 : 0);
    if (calls < 1) {
      problems.push(
        `${caller.file} 没有调用 ${anchor.method} —— ` +
          '空单号会一路裸奔到渠道客户端，不能跳过 fail-closed 守卫'
      );
    }
  }
}

if (problems.length) {
  fail(`\n  ${problems.join('\n  ')}\n`);
}

console.log(
  `${TAG} OK：${javaFiles.length} 个 java 文件、${totalHits} 处渠道幂等标识引用，` +
    '无随机/时间源；确定性生成器与 fail-closed 守卫（含调用点）均在位'
);
