#!/usr/bin/env node
/**
 * `check-consistency-rule-fixtures.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-consistency-rule-fixtures.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate } from './check-consistency-rule-fixtures.mjs';

const TAG = '[check-consistency-rule-fixtures.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REGISTRY = JSON.parse(
  readFileSync(resolve(ROOT, 'scripts/consistency-rule-registry.json'), 'utf8')
);
const SVC = readFileSync(
  resolve(
    ROOT,
    'services/trade-service/src/main/java/com/aicabinet/trade/service/DataConsistencyService.java'
  ),
  'utf8'
);
const FIXTURE = (rel) => (rel.endsWith('.java') ? readFileSync(resolve(ROOT, rel), 'utf8') : '');

// 1) 真实仓面 ⇒ 零问题
{
  const r = evaluate(SVC, REGISTRY, () => true, FIXTURE);
  assert(r.problems.length === 0, `真实仓面应零问题：${r.problems.join('|')}`);
}

// 2) 新巡检规则未登记 ⇒ 红（完备性本体）
{
  const injected = SVC + '\n    void checkBrandNewRule() {\n    }';
  const r = evaluate(injected, REGISTRY, () => true, FIXTURE);
  assert(
    r.problems.some((p) => p.includes('checkBrandNewRule') && p.includes('未登记')),
    `新规则须红：${r.problems.join('|')}`
  );
}

// 3) 规则删除不销账 ⇒ 陈旧红
{
  const reg = JSON.parse(JSON.stringify(REGISTRY));
  reg.entries.push({
    rule: 'checkDeletedRule',
    fixtureClass: REGISTRY.entries[0].fixtureClass,
    fixtureRefs: 3,
    fixtureOk: true,
    why: 'x'
  });
  const r = evaluate(SVC, reg, () => true, FIXTURE);
  assert(
    r.problems.some((p) => p.includes('checkDeletedRule') && p.includes('陈旧')),
    `幽灵规则须红：${r.problems.join('|')}`
  );
}

// 4) 夹具被删（fixture 文件不再引用规则）⇒ 红
{
  const r = evaluate(
    SVC,
    REGISTRY,
    () => true,
    () => '// 夹具被清空'
  );
  assert(
    r.problems.some((p) => p.includes('不再引用')),
    `夹具被删须红：${r.problems.join('|')}`
  );
}

// 5) 夹具计数漂移（登记 fixtureOk=true 但现引 1 处）⇒ 红
{
  const reg = JSON.parse(JSON.stringify(REGISTRY));
  const okRule = reg.entries.find((e) => e.fixtureOk);
  const drifted = JSON.parse(JSON.stringify(REGISTRY));
  drifted.entries.find((e) => e.rule === okRule.rule).fixtureRefs = 1;
  drifted.entries.find((e) => e.rule === okRule.rule).fixtureOk = false;
  const r = evaluate(SVC, drifted, () => true, FIXTURE);
  assert(
    r.problems.some((p) => p.includes('夹具计数漂移')),
    `计数漂移须红：${r.problems.join('|')}`
  );
}

// 6) 双夹具棘轮：已齐数下降 ⇒ 红
{
  const reg = JSON.parse(JSON.stringify(REGISTRY));
  reg.seedFixtureOk = REGISTRY.seedFixtureOk + 1;
  const r = evaluate(SVC, reg, () => true, FIXTURE);
  assert(
    r.problems.some((p) => p.includes('夹具退化')),
    `夹具退化须红：${r.problems.join('|')}`
  );
}

console.log(`${TAG} OK：${checks} 项断言全过`);
