#!/usr/bin/env node
/**
 * `check-composed-literal-roundtrip.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-composed-literal-roundtrip.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate, aggregate, extractEntries } from './check-composed-literal-roundtrip.mjs';

const TAG = '[check-composed-literal-roundtrip.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REGISTRY = JSON.parse(
  readFileSync(resolve(ROOT, 'scripts/composed-literal-registry.json'), 'utf8')
);

// 1) 真实仓面 ⇒ 零问题
{
  const { readdirSync } = await import('node:fs');
  const { join, relative } = await import('node:path');
  const files = readdirSync(
    join(ROOT, 'services/trade-service/src/main/java/com/aicabinet/trade'),
    {
      recursive: true
    }
  ).filter((f) => f.endsWith('.java'));
  // 全量走主流程同款扫描（device/gateway/common 也扫，这里抽 trade 主面）
  const { statSync } = await import('node:fs');
  const all = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) walk(p);
      else if (name.endsWith('.java')) {
        const rel = relative(ROOT, p).replace(/\\/g, '/');
        all.push(...extractEntries(readFileSync(p, 'utf8'), rel));
      }
    }
  };
  walk(join(ROOT, 'services/trade-service/src/main/java'));
  walk(join(ROOT, 'services/device-service/src/main/java'));
  walk(join(ROOT, 'services/jiangyi-gateway/src/main/java'));
  walk(join(ROOT, 'services/common'));
  const r = evaluate(aggregate(all), REGISTRY, REGISTRY.maxTbd);
  assert(r.problems.length === 0, `真实仓面应零问题：${r.problems.slice(0, 3).join('|')}`);
}

// 2) 新增未登记的 reader ⇒ 红
{
  const actual = aggregate([{ file: 'x/Y.java', sep: '#', kind: 'reader' }]);
  const r = evaluate(actual, { entries: [] }, 0);
  assert(
    r.problems.length === 1 && r.problems[0].includes('未登记'),
    `未登记须红：${r.problems.join('|')}`
  );
  assert(r.problems[0].includes('R22-1'), `红因要点名家族：${r.problems[0]}`);
}

// 3) 计数失配 ⇒ 双向红
{
  const reg = {
    entries: [{ file: 'x/Y.java', sep: '#', kind: 'reader', count: 2, classification: 'TBD' }]
  };
  const more = evaluate(
    aggregate([
      { file: 'x/Y.java', sep: '#', kind: 'reader' },
      { file: 'x/Y.java', sep: '#', kind: 'reader' },
      { file: 'x/Y.java', sep: '#', kind: 'reader' }
    ]),
    reg,
    1
  );
  assert(
    more.problems.some((p) => p.includes('不符')),
    `计数失配须红：${more.problems.join('|')}`
  );
  const fewer = evaluate(aggregate([{ file: 'x/Y.java', sep: '#', kind: 'reader' }]), reg, 1);
  assert(
    fewer.problems.some((p) => p.includes('销账')),
    `少命中须红：${fewer.problems.join('|')}`
  );
}

// 4) writer 与 reader 是不同 kind，不互相抵扣
{
  const reg = {
    entries: [{ file: 'x/Y.java', sep: '#', kind: 'writer', count: 1, classification: 'TBD' }]
  };
  const r = evaluate(aggregate([{ file: 'x/Y.java', sep: '#', kind: 'reader' }]), reg, 1);
  assert(r.problems.length === 2, `writer/reader 方向须分开记账：${r.problems.join('|')}`);
}

// 5) TBD 棘轮
{
  const reg = {
    entries: [
      { file: 'a.java', sep: '；', kind: 'writer', count: 1, classification: 'TBD' },
      { file: 'b.java', sep: '；', kind: 'writer', count: 1, classification: 'TBD' }
    ]
  };
  const r = evaluate(
    aggregate([
      { file: 'a.java', sep: '；', kind: 'writer' },
      { file: 'b.java', sep: '；', kind: 'writer' }
    ]),
    reg,
    1
  );
  assert(
    r.problems.some((p) => p.includes('TBD 棘轮击穿')),
    `TBD 超限须红：${r.problems.join('|')}`
  );
}

// 6) String.join 识别为 writer
{
  const e = extractEntries('String.join("；", parts);', 'z.java');
  assert(
    e.length === 1 && e[0].kind === 'writer' && e[0].sep === '；',
    `join 应识别为 writer：${JSON.stringify(e)}`
  );
}

console.log(`${TAG} OK：${checks} 项断言全过`);
