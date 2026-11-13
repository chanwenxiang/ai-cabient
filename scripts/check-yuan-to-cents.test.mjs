#!/usr/bin/env node
/**
 * `check-yuan-to-cents.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-yuan-to-cents.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate, countHits } from './check-yuan-to-cents.mjs';

const TAG = '[check-yuan-to-cents.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// 1) 双向棘轮：与基线相等 ⇒ 绿
{
  assert(
    evaluate([{ file: 'a.vue', count: 2 }], [{ file: 'a.vue', count: 2 }]).length === 0,
    '与基线相等应绿'
  );
}

// 2) 新文件出现未登记命中 ⇒ 红
{
  const p = evaluate(
    [
      { file: 'a.vue', count: 2 },
      { file: 'new.vue', count: 1 }
    ],
    [{ file: 'a.vue', count: 2 }]
  );
  assert(p.length === 1 && p[0].includes('不在基线'), `新文件须红：${p.join('|')}`);
}

// 3) 命中变多/变少 ⇒ 双向红
{
  const more = evaluate([{ file: 'a.vue', count: 3 }], [{ file: 'a.vue', count: 2 }]);
  assert(more[0].includes('新增未登记'), `变多须红：${more.join('|')}`);
  const fewer = evaluate([{ file: 'a.vue', count: 1 }], [{ file: 'a.vue', count: 2 }]);
  assert(fewer[0].includes('销账'), `变少须红：${fewer.join('|')}`);
}

// 4) 基线陈旧（文件零命中）⇒ 红
{
  const p = evaluate([], [{ file: 'gone.vue', count: 1 }]);
  assert(p[0].includes('基线陈旧'), `陈旧须红：${p.join('|')}`);
}

// 5) 计数形态：`*100` / `* 100` / `*  100` 都命中；`* 1000`、`/ 100` 不命中
{
  assert(
    countHits('x * 100') === 1 && countHits('x*100') === 1 && countHits('x *  100') === 1,
    '形态变体应命中'
  );
  assert(countHits('x * 1000') === 0, `* 1000 不该命中：${countHits('x * 1000')}`);
  assert(countHits('x / 100') === 0, `/ 100 属分→元门禁，不在此`);
}

// 6) 真实仓面：基线 × 真实扫描 ⇒ 零问题
{
  const { readdirSync, statSync, existsSync } = await import('node:fs');
  const { join } = await import('node:path');
  const baseline = JSON.parse(
    readFileSync(resolve(ROOT, 'scripts/yuan-to-cents-baseline.json'), 'utf8')
  );
  const DIRS = [
    'clients/admin-vue/src/views',
    'clients/admin-vue/src/components',
    'clients/admin-vue/src/composables',
    'clients/merchant-mp/src',
    'clients/consumer-mp/src'
  ];
  const walk = (dir, out = []) => {
    if (!existsSync(dir)) return out;
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) {
        if (['node_modules', 'dist', 'dev'].includes(name)) continue;
        walk(p, out);
      } else if (/\.(vue|ts)$/.test(name)) out.push(p);
    }
    return out;
  };
  const actual = [];
  for (const d of DIRS) {
    for (const f of walk(join(ROOT, d))) {
      const n = countHits(readFileSync(f, 'utf8'));
      if (n > 0)
        actual.push({ file: f.replace(/\\/g, '/').replace(/^.*?clients\//, 'clients/'), count: n });
    }
  }
  const problems = evaluate(actual, baseline.entries);
  assert(problems.length === 0, `真实仓面应零问题：${problems.slice(0, 3).join('|')}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
