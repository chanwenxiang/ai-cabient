#!/usr/bin/env node
/**
 * `check-docker-tests-guarded.mjs` 的负向夹具（S8③/S8④：改判据必须同批动夹具）。
 *
 * 不 import 判据文件——它顶层就直接跑扫描（无 import.meta 守卫），import 会把整条门禁
 * 连着执行一遍，夹具失败时看到的会是门禁的报错而不是自己的断言。这里改为解析源码里的
 * `GUARDED` 字面量，逐条验登记内容本身的真实性。
 *
 *   node scripts/check-docker-tests-guarded.test.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[check-docker-tests-guarded.test]';
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const GATE = resolve(ROOT, 'scripts/check-docker-tests-guarded.mjs');
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const src = readFileSync(GATE, 'utf8');
const body = src.slice(src.indexOf('GUARDED = new Map(['));
assert(body.length > 100, 'GUARDED 清单提取失败（结构变了要先同步本夹具）');

// 逐条：[ '路径', { job: '…', why: '…' } ]
const entries = [...body.matchAll(/\[\s*'([^']+\.java)'\s*,\s*\{([\s\S]*?)\}\s*\]/g)];
assert(entries.length >= 30, `GUARDED 条目数异常（读到 ${entries.length}，提取器可能失效）`);

const JOBS = new Set(['integration', 'build']);
let badJob = 0;
let thinWhy = 0;
let missing = 0;
let notTestcontainers = 0;
for (const [, rel, block] of entries) {
  const job = (block.match(/job:\s*'([^']+)'/) || [])[1];
  if (!job || !JOBS.has(job)) badJob++;
  const whyText = (block.match(/why:\s*([\s\S]*)$/) || ['', ''])[1].replace(/['+\s]/g, '');
  if (whyText.length < 20) thinWhy++;
  const abs = resolve(ROOT, rel);
  if (!existsSync(abs)) {
    missing++;
    continue;
  }
  if (!readFileSync(abs, 'utf8').includes('@Testcontainers')) notTestcontainers++;
}
assert(badJob === 0, `有 ${badJob} 条登记缺少合法 job（integration/build）`);
assert(thinWhy === 0, `有 ${thinWhy} 条登记的 why 依据短于 20 字（空话登记=假守卫）`);
assert(missing === 0, `有 ${missing} 条登记指向不存在的测试文件（清单腐烂）`);
assert(
  notTestcontainers === 0,
  `有 ${notTestcontainers} 条登记的目标类不带 @Testcontainers（不是 Docker 依赖用例，不该占位）`
);

// 本批新增条目（CB-086 欠款账龄查询）：正向用例，退成未登记/写错 job 都要红
const mine = entries.find(([, rel]) => rel.endsWith('WalletArrearsSqlIT.java'));
assert(
  !!mine,
  'WalletArrearsSqlIT 必须在 GUARDED 里登记（否则它只在有 Docker 时跑，CI 缺 Docker 就 0 用例假绿）'
);
assert(
  /job:\s*'integration'/.test(mine[2]),
  'WalletArrearsSqlIT 属 *IT.java ⇒ job 必须是 integration（failsafe）'
);
assert(
  /CDATA/.test(mine[2]),
  'WalletArrearsSqlIT 的 why 必须写明它守的是 mapper XML 那类 mock 单测抓不到的缺陷'
);

// R18-2/4/5 并发实证条目（51916f22 判据扩面）：正向用例，钉住批次原子扣/upsert 建档/CAS 失效三判据的 GUARDED 登记
const r18 = entries.find(([, rel]) => rel.endsWith('InventoryConsistencyR18IT.java'));
assert(
  !!r18,
  'InventoryConsistencyR18IT 必须在 GUARDED 里登记（R18-2/4/5 并发实证，判据扩面的正向用例）'
);
assert(
  /job:\s*'integration'/.test(r18[2]),
  'InventoryConsistencyR18IT 属 *IT.java ⇒ job 必须是 integration（failsafe）'
);

// 同族的第二条（CB-086 锁柜腿，2026-11-14）：它不在 integration/ 目录而在 service/ 包，
// 清单腐烂最容易发生在这种「路径不合常规」的条目上 ⇒ 逐条钉住登记的真实性与 why 的信息量。
const lock = entries.find(([, rel]) => rel.endsWith('WalletArrearsLockSqlIT.java'));
assert(
  !!lock,
  'WalletArrearsLockSqlIT 必须在 GUARDED 里登记（@Testcontainers(disabledWithoutDocker=true) 的类' +
    '一旦缺 Docker 就跑 0 用例，只有清单能证明它由 integration 守）'
);
assert(
  /job:\s*'integration'/.test(lock[2]),
  'WalletArrearsLockSqlIT 是 *IT.java ⇒ job 必须是 integration（failsafe），不能因放在 service 包就记成 build'
);
assert(
  /operator_id/.test(lock[2]) && /LIKE/.test(lock[2]),
  'WalletArrearsLockSqlIT 的 why 必须同时点名两条「mock 版替不掉」的腿：' +
    '锁柜原因要真的落库（下轮靠 LIKE 找回）与审计 operator_id 的 NOT NULL+FK 归一' +
    '——否则 why 会退化成「加了个 IT」这类空话登记'
);

console.log(`${TAG} OK：${checks} 项断言全过（GUARDED ${entries.length} 条逐条验真）`);
