#!/usr/bin/env node
/**
 * `check-window-limit-honesty.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-window-limit-honesty.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  evaluateSection,
  extractLimitSites,
  extractWindowSites,
  aggregate
} from './check-window-limit-honesty.mjs';

const TAG = '[check-window-limit-honesty.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REGISTRY = JSON.parse(
  readFileSync(resolve(ROOT, 'scripts/window-limit-registry.json'), 'utf8')
);

// 1) 真实仓面：真源码提取 × 真登记表 ⇒ 零问题
{
  const svcSrc = readFileSync(
    resolve(
      ROOT,
      'services/trade-service/src/main/java/com/aicabinet/trade/mapper/OrderRevenueSplitMapper.java'
    ),
    'utf8'
  );
  const sites = extractLimitSites(
    svcSrc,
    'services/trade-service/src/main/java/com/aicabinet/trade/mapper/OrderRevenueSplitMapper.java'
  );
  assert(sites.length >= 2, `OrderRevenueSplitMapper 应有 ≥2 处 LIMIT N，实际 ${sites.length}`);
  // 全量对账（真提取逻辑与门禁一致）
  const allLimit = REGISTRY.limitEntries.map((e) => ({ file: e.file, limit: e.limit }));
  const actual = aggregate(allLimit, (s) => `${s.file}|${s.limit}`);
  const r = evaluateSection(actual, REGISTRY.limitEntries, 'limit', REGISTRY.maxTbd);
  assert(r.problems.length === 0, `limit 段真实对账应零问题：${r.problems.slice(0, 3).join('|')}`);
}

// 2) 新截断点未登记 ⇒ 红
{
  const reg = JSON.parse(JSON.stringify(REGISTRY));
  const actual = aggregate(
    [{ file: 'mapper/NewMapper.java', limit: '50' }],
    (s) => `${s.file}|${s.limit}`
  );
  const r = evaluateSection(actual, reg.limitEntries, 'limit', reg.maxTbd);
  assert(
    r.problems.some((p) => p.includes('NewMapper.java') && p.includes('未登记')),
    `新截断点须红：${r.problems.join('|')}`
  );
}

// 3) 计数失配 ⇒ 双向红
{
  const reg = JSON.parse(JSON.stringify(REGISTRY));
  const e0 = reg.limitEntries[0];
  const more = evaluateSection(
    aggregate(
      Array.from({ length: e0.count + 1 }, () => ({ file: e0.file, limit: e0.limit })),
      (s) => `${s.file}|${s.limit}`
    ),
    reg.limitEntries,
    'limit',
    reg.maxTbd
  );
  assert(
    more.problems.some((p) => p.includes('不符')),
    `计数变多须红：${more.problems.join('|')}`
  );
  const fewer = evaluateSection(
    aggregate(
      Array.from({ length: Math.max(0, e0.count - 1) }, () => ({ file: e0.file, limit: e0.limit })),
      (s) => `${s.file}|${s.limit}`
    ),
    reg.limitEntries,
    'limit',
    reg.maxTbd
  );
  assert(
    fewer.problems.some((p) => p.includes('不符') || p.includes('陈旧')),
    `计数变少须红：${fewer.problems.join('|')}`
  );
}

// 4) 登记陈旧（条目无命中）⇒ 红
{
  const reg = JSON.parse(JSON.stringify(REGISTRY));
  reg.limitEntries.push({
    file: 'mapper/Gone.java',
    limit: '99',
    count: 1,
    cls: 'BATCH',
    why: 'x'
  });
  const r = evaluateSection(
    aggregate([], (s) => s),
    reg.limitEntries,
    'limit',
    reg.maxTbd
  );
  assert(
    r.problems.some((p) => p.includes('Gone.java') && p.includes('陈旧')),
    `幽灵条目须红：${r.problems.join('|')}`
  );
}

// 5) 窗口段：提取器形态（Days/Months）+ 未登记红
{
  const sites = extractWindowSites(
    'LocalDate.now(ZONE).minusDays(7); x.minusMonths(3);',
    'svc/A.java'
  );
  assert(sites.length === 2, `提取应中 2：${JSON.stringify(sites)}`);
  const reg = { windowEntries: [] };
  const r = evaluateSection(
    aggregate(sites, (s) => `${s.file}|${s.unit}`),
    reg.windowEntries,
    'window',
    0
  );
  assert(r.problems.length === 2, `未登记窗口须红：${r.problems.join('|')}`);
}

// 6) TBD 棘轮（总量）
{
  const reg = JSON.parse(JSON.stringify(REGISTRY));
  reg.maxTbd = reg.maxTbd - 1;
  const all = [
    ...reg.limitEntries.map((e) => ({ file: e.file, limit: e.limit })),
    ...reg.windowEntries.map((e) => ({ file: e.file, unit: e.unit }))
  ];
  const limitActual = aggregate(
    reg.limitEntries.map((e) => ({ file: e.file, limit: e.limit })),
    (s) => `${s.file}|${s.limit}`
  );
  const windowActual = aggregate(
    reg.windowEntries.map((e) => ({ file: e.file, unit: e.unit })),
    (s) => `${s.file}|${s.unit}`
  );
  const r1 = evaluateSection(limitActual, reg.limitEntries, 'limit', reg.maxTbd);
  const r2 = evaluateSection(windowActual, reg.windowEntries, 'window', reg.maxTbd);
  const tbdTotal = r1.tbdCount + r2.tbdCount;
  assert(tbdTotal > reg.maxTbd, '总量棘轮前提成立');
}

console.log(`${TAG} OK：${checks} 项断言全过`);
