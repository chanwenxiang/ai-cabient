#!/usr/bin/env node
/**
 * `check-gate-test-coupling.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-gate-test-coupling.test.mjs
 */
import { evaluateCoupling } from './check-gate-test-coupling.mjs';

const TAG = '[check-gate-test-coupling.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const REG = {
  fixtureMap: { 'check-ledger-evidence.mjs': 'scripts/devops/verify-ledger-evidence-gate.mjs' }
};

// 1) 改门禁 + 同批改 sibling test ⇒ 绿
{
  const p = evaluateCoupling(['scripts/check-foo.mjs', 'scripts/check-foo.test.mjs'], REG);
  assert(p.length === 0, `同批带 test 应绿：${p.join('|')}`);
}

// 2) 改门禁 + 同批改 fixtureMap 指向的验证脚本 ⇒ 绿
{
  const p = evaluateCoupling(
    ['scripts/check-ledger-evidence.mjs', 'scripts/devops/verify-ledger-evidence-gate.mjs'],
    REG
  );
  assert(p.length === 0, `fixtureMap 同批应绿：${p.join('|')}`);
}

// 3) 改门禁 + 没有任何夹具改动 ⇒ 红（S8③ 本体）
{
  const p = evaluateCoupling(['scripts/check-foo.mjs', 'services/x/Y.java'], REG);
  assert(p.length === 1, `判据改动无夹具须红，实际 ${p.length}`);
  assert(p[0].includes('S8③'), `红因要点名 S8③：${p[0]}`);
}

// 4) exempt 豁免不免除本条（没有夹具的门禁改判据=先补夹具）
{
  const p = evaluateCoupling(['scripts/check-compose-restart.mjs'], { fixtureMap: {} });
  assert(p.length === 1, `豁免不免除判据对账：${p.join('|')}`);
}

// 5) 只改 .test.mjs 自身 ⇒ 不算门禁判据改动，绿
{
  const p = evaluateCoupling(['scripts/check-foo.test.mjs'], REG);
  assert(p.length === 0, `仅改 test 应绿：${p.join('|')}`);
}

// 6) 非 scripts/ 的同名文件不误报（clients/ 下无门禁语义）
{
  const p = evaluateCoupling(['clients/admin/test/check-foo.mjs'], REG);
  assert(p.length === 0, `非 scripts 路径应忽略：${p.join('|')}`);
}

// 7) 子目录门禁（scripts/devops/check-x.mjs 形态预留）同样受管
{
  const p = evaluateCoupling(['scripts/devops/check-sub.mjs'], REG);
  assert(
    p.length === 1 && p[0].startsWith('scripts/devops/check-sub.mjs'),
    `子目录门禁应受管：${p.join('|')}`
  );
}

// 8) Windows 反斜杠路径归一后同样命中
{
  const p = evaluateCoupling(['scripts\\check-foo.mjs'], REG);
  assert(p.length === 1, `反斜杠应归一：${p.join('|')}`);
}

// 9) selfTest 形态门禁（夹具与判据同文件共存）：改判据=连夹具一起改，不算违规
{
  const p = evaluateCoupling(['scripts/check-image-tags.mjs'], REG, {
    selfTestGate: (base) => base === 'check-image-tags.mjs'
  });
  assert(p.length === 0, `selfTest 形态应豁免 diff 对账：${p.join('|')}`);
}

// 10) 登记在册的存量欠账（couplingDebt）放行本批改动
{
  const p = evaluateCoupling(['scripts/check-compose-restart.mjs'], REG, {
    debt: { 'check-compose-restart.mjs': 'b8b76dd3 欠第一条 .test.mjs' }
  });
  assert(p.length === 0, `debt 记账应放行：${p.join('|')}`);
}

// 11) debt 卫生：欠账门禁一旦补了 sibling test，条目必须销掉（全局校验，不看改动集）
{
  const p = evaluateCoupling([], REG, {
    debt: { 'check-foo.mjs': '欠账中' },
    testExists: (n) => n === 'check-foo.test.mjs'
  });
  assert(p.length === 1 && p[0].includes('销'), `debt 补夹具后须红催销账：${p.join('|')}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
