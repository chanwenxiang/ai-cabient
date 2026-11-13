#!/usr/bin/env node
/**
 * `check-audit-gates-wiring.mjs` 规则 6（S8④ 夹具棘轮）的负向用例。
 *
 *   node scripts/check-audit-gates-wiring.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluateFixtureCoverage } from './check-audit-gates-wiring.mjs';

const TAG = '[check-audit-gates-wiring.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// 评估器快捷入参：tests=带 sibling test 的门禁集合；sources=门禁源码表（原样，剥注释由本助手
// 按 main() 同款实现代做——sourceOf 契约收「已剥注释」的源码）；files=存在的文件表
const stripJsComments = (src) =>
  src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');
function run({ gates, tests = [], sources = {}, files = [], registry }) {
  const testSet = new Set(tests);
  const fileSet = new Set(files);
  return evaluateFixtureCoverage({
    gateNames: gates,
    registry,
    testExists: (n) => testSet.has(n),
    sourceOf: (n) => stripJsComments(sources[n] || ''),
    existsFn: (rel) => fileSet.has(rel)
  });
}

// 1) 有 sibling test ⇒ 绿
{
  const r = run({
    gates: ['check-a.mjs'],
    tests: ['check-a.test.mjs'],
    registry: { maxExempt: 0, seedExempt: [], exempt: [], fixtureMap: {} }
  });
  assert(r.problems.length === 0, `有 test 应绿：${r.problems.join('|')}`);
}

// 2) 无任何覆盖且未豁免 ⇒ 红（S8④ 本体）
{
  const r = run({
    gates: ['check-b.mjs'],
    registry: { maxExempt: 0, seedExempt: [], exempt: [], fixtureMap: {} }
  });
  assert(r.problems.length === 1, `无夹具未豁免须红，实际 ${r.problems.length}`);
  assert(r.problems[0].includes('S8④'), `红因要点名 S8④：${r.problems[0]}`);
}

// 3) 无覆盖但已豁免（基线内）⇒ 绿
{
  const r = run({
    gates: ['check-b.mjs'],
    registry: { maxExempt: 1, seedExempt: ['check-b.mjs'], exempt: ['check-b.mjs'], fixtureMap: {} }
  });
  assert(r.problems.length === 0, `基线豁免应绿：${r.problems.join('|')}`);
}

// 4) 新门禁不带夹具直接扩豁免 ⇒ 两红（无基线子集 + maxExempt 击穿）
{
  const r = run({
    gates: ['check-b.mjs', 'check-new.mjs'],
    registry: {
      maxExempt: 1,
      seedExempt: ['check-b.mjs'],
      exempt: ['check-b.mjs', 'check-new.mjs'],
      fixtureMap: {}
    }
  });
  assert(r.problems.length === 2, `扩豁免须两红，实际 ${r.problems.length}`);
  assert(
    r.problems.some((p) => p.includes('seedExempt')) &&
      r.problems.some((p) => p.includes('maxExempt')),
    `两道棘轮都要响：${r.problems.join('|')}`
  );
}

// 5) 已带夹具却留在豁免 ⇒ 陈旧红（强制移出、棘轮收紧）
{
  const r = run({
    gates: ['check-a.mjs'],
    tests: ['check-a.test.mjs'],
    registry: { maxExempt: 1, seedExempt: ['check-a.mjs'], exempt: ['check-a.mjs'], fixtureMap: {} }
  });
  assert(
    r.problems.length === 1 && r.problems[0].includes('陈旧'),
    `陈旧豁免须红：${r.problems.join('|')}`
  );
}

// 6) fixtureMap 覆盖 ⇒ 绿；目标文件缺失 ⇒ 假覆盖红
{
  const ok = run({
    gates: ['check-c.mjs'],
    files: ['scripts/devops/verify-c.mjs'],
    registry: {
      maxExempt: 0,
      seedExempt: [],
      exempt: [],
      fixtureMap: { 'check-c.mjs': 'scripts/devops/verify-c.mjs' }
    }
  });
  assert(ok.problems.length === 0, `fixtureMap 覆盖应绿：${ok.problems.join('|')}`);
  const broken = run({
    gates: ['check-c.mjs'],
    registry: {
      maxExempt: 0,
      seedExempt: [],
      exempt: [],
      fixtureMap: { 'check-c.mjs': 'scripts/devops/verify-c.mjs' }
    }
  });
  assert(
    broken.problems.length === 2 && broken.problems.some((p) => p.includes('假覆盖')),
    `目标缺失须双红（失覆盖+假覆盖）：${broken.problems.join('|')}`
  );
}

// 7) 自带 selfTest（剥注释后真形态）⇒ 绿；注释里提 selfTest 不算（本门禁第一版就红在这）
{
  const real = run({
    gates: ['check-d.mjs'],
    sources: {
      'check-d.mjs':
        'function selfTest() { assert(false); }\nif (process.argv.includes("--self-test")) { selfTest(); }'
    },
    registry: { maxExempt: 0, seedExempt: [], exempt: [], fixtureMap: {} }
  });
  assert(real.problems.length === 0, `真 selfTest 应覆盖：${real.problems.join('|')}`);

  const commentOnly = run({
    gates: ['check-e.mjs'],
    sources: { 'check-e.mjs': '// 参考：邻居门禁有 selfTest( 与 --self-test 两个标记' },
    registry: { maxExempt: 0, seedExempt: [], exempt: [], fixtureMap: {} }
  });
  assert(
    commentOnly.problems.length === 1,
    `注释形态不得算覆盖：${commentOnly.problems.join('|')}`
  );
}

// 8) 豁免指向已删除的门禁 ⇒ 陈旧红
{
  const r = run({
    gates: [],
    registry: {
      maxExempt: 1,
      seedExempt: ['check-gone.mjs'],
      exempt: ['check-gone.mjs'],
      fixtureMap: {}
    }
  });
  assert(
    r.problems.length === 1 && r.problems[0].includes('已不存在'),
    `幽灵豁免须红：${r.problems.join('|')}`
  );
}

// 9) 同时在 fixtureMap 与 exempt ⇒ 登记冲突红
{
  const r = run({
    gates: ['check-c.mjs'],
    files: ['scripts/devops/verify-c.mjs'],
    registry: {
      maxExempt: 1,
      seedExempt: ['check-c.mjs'],
      exempt: ['check-c.mjs'],
      fixtureMap: { 'check-c.mjs': 'scripts/devops/verify-c.mjs' }
    }
  });
  assert(
    r.problems.length >= 1 && r.problems.some((p) => p.includes('冲突')),
    `登记冲突须红（伴随陈旧豁免双报）：${r.problems.join('|')}`
  );
}

// 10) 真实仓面一致性：真登记表 × 真门禁清单 × 真文件系统 ⇒ 零问题
{
  const registry = JSON.parse(
    readFileSync(resolve(ROOT, 'scripts/gate-fixture-registry.json'), 'utf8')
  );
  const { readdirSync, existsSync } = await import('node:fs');
  const walk = (dir, out = []) => {
    for (const f of readdirSync(dir, { withFileTypes: true })) {
      const p = dir + '/' + f.name;
      if (f.isDirectory()) walk(p, out);
      else if (/^check-.*\.mjs$/.test(f.name)) out.push(f.name);
    }
    return out;
  };
  const all = walk(resolve(ROOT, 'scripts'));
  const gates = all.filter((n) => !n.endsWith('.test.mjs'));
  const r = run({
    gates,
    tests: all.filter((n) => n.endsWith('.test.mjs')),
    registry,
    sources: Object.fromEntries(
      gates.map((n) => [n, readFileSync(resolve(ROOT, 'scripts', n), 'utf8')])
    ),
    files: [...Object.values(registry.fixtureMap || [])]
  });
  assert(r.problems.length === 0, `真实仓面应零问题：${r.problems.join('|')}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
