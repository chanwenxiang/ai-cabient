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

// 5b) 🔴 数字后缀形态必须命中（2026-11-14 补：旧 `\b` 在 `100` 与 `L` 之间不成立 ⇒ 漏 `* 100L`）
//     Java 金额是 long，`yuan * 100L` 比 `yuan * 100` 更常见，漏这个形态等于后端段白扫。
{
  assert(
    countHits('long c = yuan * 100L;') === 1,
    `* 100L 必须命中：${countHits('long c = yuan * 100L;')}`
  );
  assert(countHits('long c = yuan * 100l;') === 1, `* 100l 必须命中`);
  assert(countHits('double d = x * 100.0;') === 1, `* 100.0 必须命中`);
  assert(countHits('int a = x * 100;') === 1, `* 100 必须命中`);
  assert(countHits('int a = x * 10000L;') === 0, `* 10000L 不该命中（前缀 100 后接数字）`);
  assert(countHits('int a = x * 1000;') === 0, `* 1000 不该命中`);
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
  const walk = (dir, exts, out = []) => {
    if (!existsSync(dir)) return out;
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) {
        if (['node_modules', 'dist', 'dev', 'target', 'static'].includes(name)) continue;
        walk(p, exts, out);
      } else if (exts.some((e) => name.endsWith(e))) out.push(p);
    }
    return out;
  };
  const actual = [];
  for (const d of DIRS) {
    for (const f of walk(join(ROOT, d), ['.vue', '.ts'])) {
      const n = countHits(readFileSync(f, 'utf8'));
      if (n > 0)
        actual.push({ file: f.replace(/\\/g, '/').replace(/^.*?clients\//, 'clients/'), count: n });
    }
  }
  const problems = evaluate(actual, baseline.entries);
  assert(problems.length === 0, `真实仓面应零问题：${problems.slice(0, 3).join('|')}`);

  // 6b) 后端段真实仓面：javaEntries × services Java 扫描 ⇒ 零问题
  const SERVICES = ['trade-service', 'device-service', 'common', 'jiangyi-gateway'];
  const javaActual = [];
  for (const svc of SERVICES) {
    for (const f of walk(join(ROOT, 'services', svc, 'src', 'main', 'java'), ['.java'])) {
      const n = countHits(readFileSync(f, 'utf8'));
      if (n > 0)
        javaActual.push({
          file: f.replace(/\\/g, '/').replace(/^.*?services\//, 'services/'),
          count: n
        });
    }
  }
  const javaProblems = evaluate(javaActual, baseline.javaEntries || [], 'java');
  assert(javaProblems.length === 0, `后端真实仓面应零问题：${javaProblems.slice(0, 3).join('|')}`);
}

// 7) 后端段报错指向 YuanFen 单源（不是 yuanToCents shared）
{
  const p = evaluate([{ file: 'services/x/Y.java', count: 1 }], [], 'java');
  assert(
    p.length === 1 && p[0].includes('YuanFen.toCents') && !p[0].includes('yuanToCents（shared）'),
    `后端报错须指向 YuanFen：${p.join('|')}`
  );
}

console.log(`${TAG} OK：${checks} 项断言全过`);
