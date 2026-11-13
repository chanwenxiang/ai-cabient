#!/usr/bin/env node
/**
 * `check-write-action-attribution.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-write-action-attribution.test.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate, hasAuditCallEvidence } from './check-write-action-attribution.mjs';

const TAG = '[check-write-action-attribution.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REGISTRY = JSON.parse(
  readFileSync(resolve(ROOT, 'scripts/write-attribution-registry.json'), 'utf8')
);

// 1) 真实仓面 ⇒ 零问题
{
  const cap = REGISTRY.entries.map((e) => ({
    file: e.file,
    writes: e.writes,
    auditCapable: false
  }));
  for (let i = 0; i < REGISTRY.capableCount; i++) {
    cap.push({ file: `capable/Capable${i}Controller.java`, writes: 1, auditCapable: true });
  }
  const r = evaluate(cap, REGISTRY);
  assert(r.problems.length === 0, `真实登记应零问题：${r.problems.slice(0, 3).join('|')}`);
}

// 2) 可达控制器被拆（appendLog 没了）⇒ 可达数下降红（MA-082 复发形态）
{
  const cap = REGISTRY.entries.map((e) => ({
    file: e.file,
    writes: e.writes,
    auditCapable: false
  }));
  for (let i = 0; i < REGISTRY.capableCount - 1; i++) {
    cap.push({ file: `capable/Capable${i}Controller.java`, writes: 1, auditCapable: true });
  }
  const r = evaluate(cap, REGISTRY);
  assert(
    r.problems.some((p) => p.includes('可达数下降')),
    `拆留痕须红：${r.problems.join('|')}`
  );
}

// 3) 新零归因文件未登记 ⇒ 红
{
  const cap = REGISTRY.entries.map((e) => ({
    file: e.file,
    writes: e.writes,
    auditCapable: false
  }));
  cap.push({ file: 'api/NewOpsController.java', writes: 5, auditCapable: false });
  for (let i = 0; i < REGISTRY.capableCount; i++) {
    cap.push({ file: `capable/Capable${i}Controller.java`, writes: 1, auditCapable: true });
  }
  const r = evaluate(cap, REGISTRY);
  assert(
    r.problems.some((p) => p.includes('NewOpsController') && p.includes('零归因未登记')),
    `新缺口须红：${r.problems.join('|')}`
  );
}

// 4) TBD 补了留痕 ⇒ 必须销账红（真 TBD 可能已被销完——maxTbd=0 时合成一条验证判据）
{
  const reg = JSON.parse(JSON.stringify(REGISTRY));
  if (!reg.entries.some((e) => e.cls === 'TBD')) {
    reg.entries.push({
      file: 'api/SyntheticTbdController.java',
      writes: 1,
      cls: 'TBD',
      why: '合成条目（判据自证用）'
    });
  }
  const cap = reg.entries.map((e) => ({
    file: e.file,
    writes: e.writes || 1,
    auditCapable: e.cls === 'TBD' ? true : false
  }));
  for (let i = 0; i < reg.capableCount; i++) {
    cap.push({ file: `capable/Capable${i}Controller.java`, writes: 1, auditCapable: true });
  }
  const r = evaluate(cap, reg);
  assert(
    r.problems.some((p) => p.includes('欠账销账')),
    `补留痕后须催销账：${r.problems.join('|')}`
  );
}

// 5) TBD 棘轮：登记表超 maxTbd ⇒ 红
{
  const reg = JSON.parse(JSON.stringify(REGISTRY));
  reg.maxTbd = reg.entries.filter((e) => e.cls === 'TBD').length - 1;
  const cap = reg.entries.map((e) => ({ file: e.file, writes: e.writes, auditCapable: false }));
  for (let i = 0; i < reg.capableCount; i++) {
    cap.push({ file: `capable/Capable${i}Controller.java`, writes: 1, auditCapable: true });
  }
  const r = evaluate(cap, reg);
  assert(
    r.problems.some((p) => p.includes('TBD 棘轮击穿')),
    `TBD 超限须红：${r.problems.join('|')}`
  );
}

// 6) 登记文件被删 ⇒ 陈旧红（锚点用仍在册的条目；OpsOta 已销账不能再用）
{
  const anchorEntry =
    REGISTRY.entries.find((e) => e.file.includes('OpsPlatformFund')) || REGISTRY.entries[0];
  const anchor = anchorEntry.file.split('/').pop().replace('.java', '');
  const cap = REGISTRY.entries
    .filter((e) => e.file !== anchorEntry.file)
    .map((e) => ({
      file: e.file,
      writes: e.writes,
      auditCapable: false
    }));
  for (let i = 0; i < REGISTRY.capableCount; i++) {
    cap.push({ file: `capable/Capable${i}Controller.java`, writes: 1, auditCapable: true });
  }
  const r = evaluate(cap, REGISTRY);
  assert(
    r.problems.some((p) => p.includes(anchor) && p.includes('陈旧')),
    `删文件须销账：${r.problems.join('|')}`
  );
}

// 5) 证据判据本体（S8③：判据改动须同批带负向用例）
//    旧写法 `/appendLog\s*\(/` 把 **定义点** 也算证据 ⇒ 1-hop 证据集里含 AdminAuditService
//    自己（它就是 appendLog 的定义类）⇒ 凡注入过审计服务的控制器恒判可达，删掉真实调用不红。
{
  const CALL =
    'class FooController { private final AdminAuditService auditService;' +
    ' @PostMapping void bar() { auditService.appendLog(1L, "A", "T", "id", "d"); } }';
  const DEF_ONLY = 'class AdminAuditService { public void appendLog(Long o, String a) {} }';
  const NO_ARG = 'class FooController { @PostMapping void bar() {} }';

  assert(hasAuditCallEvidence(CALL, []) === true, '控制器自身有真实调用 ⇒ 应可达');
  assert(
    hasAuditCallEvidence(DEF_ONLY, []) === false,
    '只有 appendLog 定义（无调用点）⇒ 不得算证据（旧写法在这里误判 true）'
  );
  assert(
    hasAuditCallEvidence(NO_ARG, [DEF_ONLY]) === false,
    '只注入了 AdminAuditService 却一次都没调 ⇒ 必须不可达（证据自喂回归钉）'
  );
  assert(
    hasAuditCallEvidence(NO_ARG, [CALL]) === true,
    '1-hop 业务 service 里有真实调用 ⇒ 合法可达'
  );
  assert(hasAuditCallEvidence(NO_ARG, []) === false, '全无证据 ⇒ 不可达');
  // 已知局限（防被误读成已覆盖委托链）：service→service2 的 2-hop 仍判不到，方向是安全侧。
  assert(
    hasAuditCallEvidence(NO_ARG, ['class AService { void x() { bService.doWork(); } }']) === false,
    '2-hop 委托仍属盲区，本断言钉住「盲区已知」而非「已修」'
  );
}

console.log(`${TAG} OK：${checks} 项断言全过`);
