#!/usr/bin/env node
/**
 * `check-pending-state-progress.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-pending-state-progress.test.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  evaluateStateLine,
  evaluateHostCoverage,
  extractEnumConstants,
  stripComments
} from './check-pending-state-progress.mjs';

const TAG = '[check-pending-state-progress.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const REGISTRY = JSON.parse(
  readFileSync(resolve(ROOT, 'scripts/pending-state-registry.json'), 'utf8')
);

/** 全仓主源码索引（与门禁 main 同款口径，仅测试用） */
function realIndex() {
  const walk = (dir, out = []) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      const st = statSync(full);
      if (st.isDirectory()) {
        if (['node_modules', 'target', 'static'].includes(name)) continue;
        walk(full, out);
      } else if (name.endsWith('.java')) out.push(full);
    }
    return out;
  };
  return walk(join(ROOT, 'services'))
    .filter((p) => /src[\\/]main[\\/]/.test(p))
    .map((p) => stripComments(readFileSync(p, 'utf8')))
    .join('\n');
}

// 1) 真实登记的行 + 真实索引 ⇒ 绿（锚点/告警/宿主三向）
{
  const line = REGISTRY.stateLines.find((l) => l.entity === 'MachinePayeeBill');
  const p = evaluateStateLine(line, realIndex());
  assert(p.length === 0, `已登记行应绿：${p.join('|')}`);
}

// 2) 推进器锚点不可寻（方法被改名）⇒ 红
{
  const line = REGISTRY.stateLines.find((l) => l.entity === 'MachinePayeeBill');
  const bad = { ...line, advancers: [{ ...line.advancers[0], anchor: 'noSuchMethodAnymore' }] };
  const p = evaluateStateLine(bad, realIndex());
  assert(
    p.some((x) => x.includes('锚点不可寻')),
    `改名须红：${p.join('|')}`
  );
}

// 3) 推进器类不存在 ⇒ 红
{
  const line = REGISTRY.stateLines.find((l) => l.entity === 'MachinePayeeBill');
  const bad = {
    ...line,
    advancers: [{ ...line.advancers[0], class: 'services/trade-service/src/main/java/x/Gone.java' }]
  };
  const p = evaluateStateLine(bad, realIndex());
  assert(
    p.some((x) => x.includes('推进器类不存在')),
    `类删了须红：${p.join('|')}`
  );
}

// 4) 告警 code 字面量全仓不可寻 ⇒ 红
{
  const line = REGISTRY.stateLines.find((l) => l.entity === 'MachinePayeeBill');
  const bad = { ...line, advancers: [{ ...line.advancers[0], alert: 'NEVER_EMITTED_CODE_999' }] };
  const p = evaluateStateLine(bad, realIndex());
  assert(
    p.some((x) => x.includes('字面量全仓不可寻')),
    `告警假登记须红：${p.join('|')}`
  );
}

// 5) host 文件不存在 ⇒ 红
{
  const line = REGISTRY.stateLines.find((l) => l.entity === 'MachinePayeeBill');
  const p = evaluateStateLine({ ...line, host: 'services/x/Nope.java' }, realIndex());
  assert(
    p.some((x) => x.includes('host 文件不存在')),
    `host 漂移须红：${p.join('|')}`
  );
}

// 6) 枚举完备性：注入一个没登记的新常量 ⇒ 判据前提成立（S2 本体：新状态不登记即红）
{
  const ssLine = REGISTRY.stateLines.find((l) => l.entity === 'ShoppingSession');
  const src = readFileSync(resolve(ROOT, ssLine.host), 'utf8');
  const injected = src.replace('CREATED,', 'CREATED,\n    PENDING_NEW_STATE,');
  const constants = extractEnumConstants(stripComments(injected), 'SessionState');
  assert(constants.includes('PENDING_NEW_STATE'), '注入的常量应被抽取到');
  const covered = new Set([
    ...(ssLine.terminal || []),
    ...(ssLine.gaps || []).map((g) => g.state),
    ...(ssLine.uncertain || []).map((u) => u.state)
  ]);
  for (const adv of ssLine.advancers || []) {
    for (const f of adv.from || []) covered.add(f);
    if (adv.to) covered.add(adv.to);
  }
  assert(
    constants.some((c) => !covered.has(c)),
    '新枚举常量不在登记覆盖集——未登记即红的判据前提成立'
  );
}

// 7) 宿主完备性：真实 domain 实体未登记 ⇒ 红；登记后 ⇒ 绿
{
  const rel = 'services/trade-service/src/main/java/com/aicabinet/trade/domain/AdCampaign.java';
  const p = evaluateHostCoverage([rel], { stateLines: [], nonLifecycle: [] });
  assert(
    p.some((x) => x.includes('AdCampaign')),
    `未登记宿主须红：${p.join('|')}`
  );
  const ok = evaluateHostCoverage([rel], {
    stateLines: [{ entity: 'AdCampaign' }],
    nonLifecycle: []
  });
  assert(ok.length === 0, `登记后宿主应绿：${ok.join('|')}`);
}

// 8) 真实仓面：登记表全行 + 全宿主，用真实索引 ⇒ 零问题
{
  const index = realIndex();
  const problems = [];
  for (const line of REGISTRY.stateLines) problems.push(...evaluateStateLine(line, index));
  const domainFiles = REGISTRY.stateLines.map((l) => l.host);
  // 宿主扫描需要全量 domain 文件——用真实 walk 同款（与门禁 main 一致）
  const walk = (dir, out = []) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      const st = statSync(full);
      if (st.isDirectory()) {
        if (['node_modules', 'target', 'static'].includes(name)) continue;
        walk(full, out);
      } else if (name.endsWith('.java')) out.push(full);
    }
    return out;
  };
  const allDomain = walk(join(ROOT, 'services'))
    .filter((p) => /(^|[\\/])domain[\\/]/.test(p))
    .map((p) => relative(ROOT, p).replace(/\\/g, '/'));
  problems.push(...evaluateHostCoverage(allDomain, REGISTRY));
  assert(problems.length === 0, `真实仓面应零问题：${problems.slice(0, 5).join(' | ')}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
