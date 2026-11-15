#!/usr/bin/env node
/**
 * check-audit-report-registered 的沙箱夹具（三态 + 边界）。
 *
 * 为什么需要独立夹具：本门禁的核心是「报告有、台账无 ⇒ 红」。若只在真实仓跑一次绿，
 * 无法证明它在「漏登记」时真的会红——而漏登记正是要堵的洞（R23 四端轮 22 条全漏）。
 *
 * 验证项：
 *   S1 基线（报告编号都已登记）           → 0 error
 *   S2 注入未登记编号 C99                  → ≥1 error 且指向 C99（判据能红）
 *   S3 还原注入                             → 回到 0 error（判据不残留）
 *   S4 注释 / 代码块里的编号                → 不触发（maskComments 生效，防假红）
 *   S5 号段声明与台账重叠（无豁免）         → 0 error + WARN（R2 的边界：静态不可判，只提示）
 *   S6 号段声明 + 「不领号」声明            → 0 error + 免登记 WARN（豁免形态可识别）
 *   S7 台账被截断（< MIN_LEDGER）           → 红（防台账换文件后静默通过）
 *
 * 零残留：全程只写 os.tmpdir() 下的沙箱；真实仓文件在任何阶段都不被写入，
 * 收尾断言真实台账与真实报告 md5 前后一致。沙箱清理失败只告警不影响结论
 * （Windows safe-delete 垫片会拦批量删，不能让它把夹具判成假红）。
 */
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { run } from '../check-audit-report-registered.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const TAG = '[verify-audit-report-registered]';
const LEDGER_REL = 'docs/module-audit/ISSUES.md';
const REPORT_REL = 'docs/AUDIT_FAKE_R23.md';

const md5 = (p) => createHash('md5').update(readFileSync(p)).digest('hex');
const before = {
  ledger: md5(join(ROOT, LEDGER_REL)),
  real: md5(join(ROOT, 'docs/AUDIT_R23_COMBINED_2026-11-15.md'))
};

const sandbox = mkdtempSync(join(tmpdir(), 'audit-registered-'));
const sandboxLedger = join(sandbox, LEDGER_REL);
const sandboxReport = join(sandbox, REPORT_REL);
mkdirSync(dirname(sandboxLedger), { recursive: true });
// 另两份报告只为满足 MIN_REPORTS=3（防假绿的扫描面下限），内容不含缺陷编号
const fillerA = join(sandbox, 'docs/AUDIT_FILLER_A_R23.md');
const fillerB = join(sandbox, 'docs/module-audit/R23/FILLER_B.md');
mkdirSync(dirname(fillerB), { recursive: true });
writeFileSync(fillerA, '# filler A\n\n无编号。\n', 'utf8');
writeFileSync(fillerB, '# filler B\n\n无编号。\n', 'utf8');

const realLedger = readFileSync(join(ROOT, LEDGER_REL), 'utf8');

const writeReport = (body) => writeFileSync(sandboxReport, body, 'utf8');

const BASE_REPORT = [
  '# 假报告（夹具）',
  '',
  '| # | 级 | 说明 |',
  '|---|---|---|',
  '| C01 | P1 | 提现二次解冻 |',
  '| C02 | P1 | 视频无授权 |',
  '| MA-262 | P1 | 同上 |',
  ''
].join('\n');

const results = [];
function check(id, desc, fn) {
  try {
    const detail = fn();
    results.push({ id, desc, ok: true, detail });
  } catch (e) {
    results.push({ id, desc, ok: false, detail: e.message });
  }
}
function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

writeFileSync(sandboxLedger, realLedger, 'utf8');

check('S1', '基线：报告编号全部已登记 → 绿', () => {
  writeReport(BASE_REPORT);
  const r = run(sandbox);
  assert(r.errors.length === 0, `期望 0 error，实际 ${r.errors.length}：${r.errors[0]}`);
  return `scanned=${r.scanned} ledger=${r.ledgerCount}`;
});

check('S2', '注入未登记 C99 → 红且指向 C99', () => {
  writeReport(`${BASE_REPORT}| C99 | P1 | 夹具注入的未登记条目 |\n`);
  const r = run(sandbox);
  assert(r.errors.length >= 1, '期望 ≥1 error，实际 0（门禁对漏登记是瞎的）');
  assert(
    r.errors.some((e) => e.includes('C99')),
    `error 未指向 C99：${r.errors[0]}`
  );
  return r.errors.find((e) => e.includes('C99'));
});

check('S3', '还原注入 → 回到绿（无状态残留）', () => {
  writeReport(BASE_REPORT);
  const r = run(sandbox);
  assert(r.errors.length === 0, `还原后仍有 ${r.errors.length} error：${r.errors[0]}`);
  return 'restored';
});

check('S4', '注释 / 代码块里的编号不触发（maskComments）', () => {
  writeReport(
    [BASE_REPORT, '<!-- C77 只出现在注释里 -->', '```', 'C88 只出现在代码块里', '```', ''].join(
      '\n'
    )
  );
  const r = run(sandbox);
  const hits = r.errors.filter((e) => /C7[7-9]|C8[89]/.test(e));
  assert(hits.length === 0, `注释/代码块编号被误判：${hits[0]}`);
  return 'no false positive';
});

check('S5', '号段声明与台账重叠（无豁免）→ 不红但 WARN（R2 边界）', () => {
  writeReport(`${BASE_REPORT}\n## 建议新 MA 号（保留段 MA-262..270）\n`);
  const r = run(sandbox);
  assert(r.errors.length === 0, `R2 不应阻断，实际 ${r.errors.length} error`);
  assert(
    r.warnings.some((w) => w.includes('号段')),
    '期望有号段重叠 WARN，实际没有（撞号完全无人提示）'
  );
  return r.warnings.find((w) => w.includes('号段'));
});

check('S6', '号段声明 + 「不领号」声明 → 免登记放行', () => {
  writeReport(
    `${BASE_REPORT}\n## 建议新 MA 号（保留段 MA-262..270）\n\n> 仅本报告标注，不写 ISSUES.md。\n`
  );
  const r = run(sandbox);
  assert(r.errors.length === 0, `期望 0 error，实际 ${r.errors[0]}`);
  assert(
    r.warnings.some((w) => w.includes('免登记')),
    '期望有「免登记」WARN，实际没有（豁免形态没被识别）'
  );
  return r.warnings.find((w) => w.includes('免登记'));
});

check('S7', '台账被截断（< MIN_LEDGER）→ 红', () => {
  writeFileSync(sandboxLedger, '| ID | 级 |\n|---|---|\n| MA-001 | P2 |\n', 'utf8');
  writeReport(BASE_REPORT);
  const r = run(sandbox);
  assert(
    r.errors.some((e) => e.includes('台账条目数')),
    '台账被截断却未红（换文件/截断后门禁会静默通过）'
  );
  writeFileSync(sandboxLedger, realLedger, 'utf8');
  return r.errors.find((e) => e.includes('台账条目数'));
});

// 收尾：真实仓零改动 + 清理沙箱（失败只告警）
const after = {
  ledger: md5(join(ROOT, LEDGER_REL)),
  real: md5(join(ROOT, 'docs/AUDIT_R23_COMBINED_2026-11-15.md'))
};
const untouched = before.ledger === after.ledger && before.real === after.real;
if (!untouched) {
  results.push({ id: 'S8', desc: '真实仓零改动', ok: false, detail: '真实台账或报告 md5 变化' });
} else {
  results.push({ id: 'S8', desc: '真实仓零改动（md5 前后一致）', ok: true, detail: 'untouched' });
}
try {
  rmSync(sandbox, { recursive: true, force: true });
} catch (e) {
  console.log(`${TAG} WARN 沙箱清理失败（不影响结论）：${e.message}`);
}

const failed = results.filter((r) => !r.ok);
for (const r of results) {
  console.log(`${r.ok ? 'PASS' : 'FAIL'} ${r.id} ${r.desc} → ${r.detail ?? ''}`);
}
if (failed.length) {
  console.error(`${TAG} FAIL ${failed.length}/${results.length}`);
  process.exit(1);
}
console.log(
  `${TAG} OK ${results.length}/${results.length}（含 S2 判据能红、S4 防假红、S5/S6 豁免边界）`
);
