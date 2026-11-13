#!/usr/bin/env node
/**
 * `check-retention-registry.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-retention-registry.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate, extractTables } from './check-retention-registry.mjs';
import { compareVersions } from './check-migration-number-order.mjs';

const TAG = '[check-retention-registry.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const REGISTRY = JSON.parse(readFileSync(resolve(ROOT, 'scripts/retention-registry.json'), 'utf8'));
const SCHED = readFileSync(
  resolve(
    ROOT,
    'services/trade-service/src/main/java/com/aicabinet/trade/service/GrowthLogArchiveScheduler.java'
  ),
  'utf8'
).replace(/\/\*[\s\S]*?\*\//g, ' ');

// 1) 真实仓面：迁移全集 × 真注册表 ⇒ 零问题
{
  const { readdirSync } = await import('node:fs');
  const { join } = await import('node:path');
  const migDir = join(ROOT, 'services/trade-service/src/main/resources/db/migration');
  const files = readdirSync(migDir)
    .filter((f) => /^V\d+__.*\.sql$/.test(f))
    .sort((a, b) => compareVersions(a.match(/^V([\d._]+)__/)[1], b.match(/^V([\d._]+)__/)[1]));
  // 逐表状态机（与 CLI 同口径）：DROP→重建终态由最后一次事件决定
  const stateMap = new Map();
  for (const f of files) {
    const s = extractTables(readFileSync(join(migDir, f), 'utf8'));
    for (const t of s.created) stateMap.set(t, 'alive');
    for (const t of s.dropped) stateMap.set(t, 'dead');
  }
  const state = {
    created: [...stateMap.keys()],
    dropped: [...stateMap.entries()].filter(([, v]) => v === 'dead').map(([k]) => k),
    live: [...stateMap.entries()]
      .filter(([, v]) => v === 'alive')
      .map(([k]) => k)
      .sort()
  };
  const adminSrc = readFileSync(
    resolve(
      ROOT,
      'services/trade-service/src/main/java/com/aicabinet/trade/service/AdminDataManageService.java'
    ),
    'utf8'
  );
  const fundsM = adminSrc.match(/FUNDS_LEDGER_TABLES\s*=\s*Set\.of\(([^)]*)\)/);
  const fundsNamed = fundsM[1]
    .split(',')
    .map((s) => (s.match(/"([^"]+)"/) || [])[1])
    .filter(Boolean);
  const r = evaluate(state, REGISTRY, SCHED, fundsNamed);
  assert(r.problems.length === 0, `真实仓面应零问题：${r.problems.slice(0, 5).join('|')}`);
  assert(r.stats.live >= 150, `活表应 ≥150，实际 ${r.stats.live}`);
}

// 2) 新表不登记 ⇒ 红（完备性本体）
{
  const state = { live: ['brand_new_table', 'notification_log'], dropped: [] };
  const reg = { tables: REGISTRY.tables.slice(0, 50), maxTbd: 15 };
  const r = evaluate(state, reg, SCHED, []);
  assert(
    r.problems.some((p) => p.includes('brand_new_table') && p.includes('未登记')),
    `新表须红：${r.problems.join('|')}`
  );
}

// 3) 被 DROP 的表留在册 ⇒ 陈旧红
{
  const state = { live: ['notification_log'], dropped: ['old_dropped_table'] };
  const reg = {
    tables: [
      ...REGISTRY.tables.slice(0, 3),
      { table: 'old_dropped_table', policy: 'RETAIN', why: 'x' }
    ],
    maxTbd: 15
  };
  const r = evaluate(state, reg, SCHED, []);
  assert(
    r.problems.some((p) => p.includes('old_dropped_table') && p.includes('DROP')),
    `幽灵条目须红：${r.problems.join('|')}`
  );
}

// 4) 策略声明了但没实现（config 键不在 Scheduler 源码）⇒ 假账红
{
  const state = { live: ['x_log'], dropped: [] };
  const reg = {
    tables: [
      {
        table: 'x_log',
        policy: 'days',
        value: 30,
        config: 'ops.log_retention.never_implemented',
        why: 'y'
      }
    ],
    maxTbd: 0
  };
  const r = evaluate(state, reg, SCHED, []);
  assert(
    r.problems.some((p) => p.includes('策略未实现')),
    `假账策略须红：${r.problems.join('|')}`
  );
}

// 5) 真实现的政策（temperature_days 在 Scheduler 里）⇒ 绿
{
  const state = { live: ['device_temperature_reading'], dropped: [] };
  const reg = {
    tables: [
      {
        table: 'device_temperature_reading',
        policy: 'days',
        value: 90,
        config: 'ops.log_retention.temperature_days',
        why: 'R22-9'
      }
    ],
    maxTbd: 0
  };
  const r = evaluate(state, reg, SCHED, []);
  assert(!r.problems.some((p) => p.includes('策略未实现')), `真实现应绿：${r.problems.join('|')}`);
}

// 6) TBD 棘轮：超 maxTbd ⇒ 红
{
  const state = { live: ['t1_log', 't2_log', 't3_log'], dropped: [] };
  const reg = {
    tables: [
      { table: 't1_log', policy: 'TBD', why: 'x' },
      { table: 't2_log', policy: 'TBD', why: 'x' },
      { table: 't3_log', policy: 'TBD', why: 'x' }
    ],
    maxTbd: 2
  };
  const r = evaluate(state, reg, SCHED, []);
  assert(
    r.problems.some((p) => p.includes('TBD 棘轮击穿')),
    `TBD 超限须红：${r.problems.join('|')}`
  );
}

// 7) S5 交叉：资金表给了非 RETAIN 策略 ⇒ 红
{
  const state = { live: ['wallet_ledger'], dropped: [] };
  const reg = {
    tables: [
      {
        table: 'wallet_ledger',
        policy: 'days',
        value: 30,
        config: 'ops.log_retention.temperature_days',
        why: 'x'
      }
    ],
    maxTbd: 0
  };
  const r = evaluate(state, reg, SCHED, ['wallet_ledger']);
  assert(
    r.problems.some((p) => p.includes('资金语义表')),
    `资金表清理策略须红：${r.problems.join('|')}`
  );
}

// 8) extractTables：CREATE + DROP 配平
{
  const s = extractTables(
    'CREATE TABLE a (id int); CREATE TABLE IF NOT EXISTS b (id int); DROP TABLE IF EXISTS a;'
  );
  assert(s.live.includes('b') && !s.live.includes('a'), `live 应只含 b：${JSON.stringify(s.live)}`);
  assert(s.dropped.includes('a'), `dropped 应含 a`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
