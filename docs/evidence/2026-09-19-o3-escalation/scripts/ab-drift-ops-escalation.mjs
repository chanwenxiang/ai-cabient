#!/usr/bin/env node
/**
 * `check-ops-alert-escalation` 的**负向对照**（O3）。
 *
 * 目的：证明这个门禁"真的会红"。做法是对**真实文件**注入一处漂移 → 断言门禁变红 →
 * 还原 → 断言 sha256 与原文**逐字节一致** → 断言门禁回绿。
 *
 * 🔴 两条纪律（本项目踩过坑，写在这里免得下次重犯）：
 *   1. **变异必须真的改变输入**：先断言 mutate 后文本 ≠ 原文；否则"没生效的变异"会得到
 *      "门禁没红"的假结论（O4 批次真踩过：只换了 `/evt` 没换 `/cmd`）。
 *   2. **基线不绿即中止**：门禁本来就红的话，后面每条"变红"都毫无意义。
 *
 *   node docs/evidence/2026-09-19-o3-escalation/scripts/ab-drift-ops-escalation.mjs
 */
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../../../');
const GATE = join(root, 'scripts/check-ops-alert-escalation.mjs');

const DISPATCHER = join(
  root,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/OpsAlertDispatcher.java'
);
const CONFIG = join(
  root,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java'
);
const ROSTER = join(
  root,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/OnCallRoster.java'
);
const CHANNELS_GATE = join(root, 'scripts/check-ops-alert-channels.mjs');

const TAG = '[ab-drift-escalation]';

const sha256 = (text) => createHash('sha256').update(text, 'utf8').digest('hex');

function runGate() {
  const r = spawnSync(process.execPath, [GATE], { encoding: 'utf8' });
  return { status: r.status, out: `${r.stdout || ''}${r.stderr || ''}`.trim() };
}

const originals = new Map();
for (const f of [DISPATCHER, CONFIG, ROSTER, CHANNELS_GATE]) {
  originals.set(f, readFileSync(f, 'utf8'));
}

function restoreAll() {
  for (const [f, text] of originals) writeFileSync(f, text, 'utf8');
}

/**
 * 单条漂移：改一处 → 必红 → 还原 → 逐字节一致 + 回绿。
 *
 * ⚠️ 模式里的 `\n` 必须按**该文件在磁盘上的真实行尾**归一：本仓 Java/脚本在磁盘上是 CRLF
 * （`git ls-files --eol` 显示 `i/lf w/crlf`），直接拿 LF 模式去匹配会「找不到片段」，
 * 而那会被误报成"变异失败"，掩盖真正的判据问题。
 */
function drift(name, file, from, to, opts = {}) {
  const original = originals.get(file);
  const eol = original.includes('\r\n') ? '\r\n' : '\n';
  const norm = (s) => (eol === '\r\n' ? s.replace(/\n/g, '\r\n') : s);
  const fromN = norm(from);
  const toN = norm(to);
  const mutated = opts.all ? original.replaceAll(fromN, toN) : original.replace(fromN, toN);
  if (mutated === original) {
    throw new Error(`${name}: 变异未生效（未找到目标片段）: ${from.slice(0, 60)}`);
  }
  try {
    writeFileSync(file, mutated, 'utf8');
    const red = runGate();
    if (red.status === 0) {
      console.error(`  ✗ ${name} ⇒ 门禁**没有变红**（判据恒真）`);
      console.error(`    ${red.out.split('\n')[0]}`);
      process.exitCode = 1;
      return;
    }
  } finally {
    restoreAll();
  }
  const after = readFileSync(file, 'utf8');
  if (sha256(after) !== sha256(original)) {
    throw new Error(`${name}: 还原后与原文不一致（sha256 不同）—— 实验被污染`);
  }
  const green = runGate();
  if (green.status !== 0) {
    throw new Error(`${name}: 还原后门禁仍为红：${green.out.split('\n')[0]}`);
  }
  console.log(`  ✓ ${name} ⇒ 红，还原字节一致且回绿`);
}

// ---------- 基线 ----------
const baseline = runGate();
if (baseline.status !== 0) {
  console.error(`${TAG} 基线不绿，中止：`);
  console.error(baseline.out);
  process.exit(1);
}
console.log(`${TAG} 基线绿 ✓\n`);

// ---------- E1 入口接线 ----------
drift(
  'D1/E1 trySend 不再触发升级',
  DISPATCHER,
  '        Fanout fanout = fanout(type, title, message, extra, extraUrls);\n' +
    '        escalateIfNeeded(type, title, message, fanout.anyDelivered());\n' +
    '        return !fanout.anyConfigured() || fanout.anyDelivered();',
  '        Fanout fanout = fanout(type, title, message, extra, extraUrls);\n' +
    '        return !fanout.anyConfigured() || fanout.anyDelivered();'
);
drift(
  'D2/E1 send 不再触发升级',
  DISPATCHER,
  '        escalateIfNeeded(type, title, message, fanout.anyDelivered());\n    }\n',
  '    }\n'
);

// ---------- E2 默认关 ----------
drift(
  'D3/E2 seed 值被改成 "true"',
  CONFIG,
  'upsertIfAbsent(OPS_ALERT_ESCALATION_ENABLED, FALSE,',
  'upsertIfAbsent(OPS_ALERT_ESCALATION_ENABLED, "true",'
);
drift(
  'D4/E2 读取兜底被改成 true',
  DISPATCHER,
  'getBoolean(SystemConfigService.OPS_ALERT_ESCALATION_ENABLED, false)',
  'getBoolean(SystemConfigService.OPS_ALERT_ESCALATION_ENABLED, true)'
);

// ---------- E3 不猜收件人 ----------
drift(
  'D5/E3 无值班人分支里多了一次投递',
  DISPATCHER,
  '            log.warn("ops alert escalation skipped (no on-call person at now) type={}", type);\n' +
    '            return EscalationOutcome.NO_ONCALL;',
  '            log.warn("ops alert escalation skipped (no on-call person at now) type={}", type);\n' +
    '            tryPost(ESCALATION_SMS, type, "", Map.of());\n' +
    '            return EscalationOutcome.NO_ONCALL;'
);

// ---------- E4 判业务码 ----------
drift(
  'D6/E4 SMS/PHONE 不再判业务码',
  DISPATCHER,
  '            case ESCALATION_SMS, ESCALATION_PHONE -> "code";\n',
  ''
);

// ---------- E5 渠道绑定配置键 ----------
drift(
  'D7/E5 一级升级错绑到电话的配置键',
  DISPATCHER,
  'escalateTo(ESCALATION_SMS, SystemConfigService.OPS_ALERT_ESCALATION_SMS_WEBHOOK,',
  'escalateTo(ESCALATION_SMS, SystemConfigService.OPS_ALERT_ESCALATION_PHONE_WEBHOOK,'
);

// ---------- E6 类型拼写 ----------
drift(
  'D8/E6 默认白名单里某个类型拼错一个字母',
  CONFIG,
  'WECHAT_REFUND_ABNORMAL,',
  'WECHAT_REFUND_ABNORMALL,'
);

// ---------- E7 命名空间 ----------
drift(
  'D9/E7 值班表键脱离 ops.alert. 命名空间',
  CONFIG,
  'OPS_ALERT_ONCALL_ROSTER = "ops.alert.oncall_roster"',
  'OPS_ALERT_ONCALL_ROSTER = "oncall.roster"'
);

// ---------- E8 不得豁免 ----------
drift(
  'D10/E8 升级键被塞进 INTERNAL_ALERT_KEYS',
  CHANNELS_GATE,
  'const INTERNAL_ALERT_KEYS = new Set([]);',
  "const INTERNAL_ALERT_KEYS = new Set(['ops.alert.oncall_roster']);"
);

// ---------- E9 值班表 fail-closed ----------
drift(
  'D11/E9 值班表解析不再退化为空表',
  ROSTER,
  'return EMPTY;',
  'return null;',
  { all: true }
);

if (process.exitCode) {
  console.error(`\n${TAG} 有用例未按预期变红`);
  process.exit(1);
}
console.log(`\n${TAG} 11/11 用例符合预期`);
console.log(`${TAG} OK：门禁对升级链的 9 类失效都能红，且还原后逐字节一致`);
