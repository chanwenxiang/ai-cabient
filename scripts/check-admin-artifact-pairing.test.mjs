#!/usr/bin/env node
/**
 * check-admin-artifact-pairing 的范围基点决策夹具（MA-293，2026-11-15）。
 *
 * 主门禁的 --selftest 已覆盖主判据与豁免约束；本夹具钉住**范围基点**这一层：
 * CI push 注入 `github.event.before`（ADMIN_PAIR_CI_BEFORE）后必须走权威直连范围、
 * 引用缺失必须红——那是两处历史 fail-open（SKIP 假绿 / mb==head 恒绿）的解药。
 *
 *   node scripts/check-admin-artifact-pairing.test.mjs
 */
import assert from 'node:assert/strict';
import { pairingRangePlan } from './lib/admin-pairing-base.mjs';

const TAG = '[check-admin-artifact-pairing.test]';
let checks = 0;

function check(name, fn) {
  fn();
  checks += 1;
  console.log(`  ✓ ${name}`);
}

// ① 本地（无 CI before）+ merge-base 失败 ⇒ 维持 SKIP 语义（不误伤首次推送）
check('无 CI before + merge-base 失败 ⇒ skip', () => {
  const p = pairingRangePlan({
    ciBefore: '',
    beforeRefExists: false,
    mergeBaseOk: false,
    mergeBaseOut: '',
    head: 'h1'
  });
  assert.equal(p.action, 'skip');
});

// ② 本地 + mb==head ⇒ ok-empty（真「无待推提交」）
check('无 CI before + mb==head ⇒ ok-empty', () => {
  const p = pairingRangePlan({
    ciBefore: '',
    beforeRefExists: false,
    mergeBaseOk: true,
    mergeBaseOut: 'h1',
    head: 'h1'
  });
  assert.equal(p.action, 'ok-empty');
});

// ③ 本地正常 ⇒ proceed（merge-base 作基点）
check('无 CI before + mb<head ⇒ proceed(mb)', () => {
  const p = pairingRangePlan({
    ciBefore: '',
    beforeRefExists: false,
    mergeBaseOk: true,
    mergeBaseOut: 'base1',
    head: 'h1'
  });
  assert.equal(p.action, 'proceed');
  assert.equal(p.rangeBase, 'base1');
});

// ④ CI before + 引用不存在 ⇒ fail（原 SKIP 假绿的解药）
check('CI before 引用缺失 ⇒ fail', () => {
  const p = pairingRangePlan({
    ciBefore: 'abc123',
    beforeRefExists: false,
    mergeBaseOk: false,
    mergeBaseOut: '',
    head: 'h1'
  });
  assert.equal(p.action, 'fail');
});

// ⑤ CI before 存在 ⇒ proceed（权威基点直连，绕开恒真的 mb==head）
check('CI before 存在 ⇒ proceed(before)', () => {
  const p = pairingRangePlan({
    ciBefore: 'abc123',
    beforeRefExists: true,
    mergeBaseOk: true,
    mergeBaseOut: 'h1',
    head: 'h1'
  });
  assert.equal(p.action, 'proceed');
  assert.equal(p.rangeBase, 'abc123');
});

// ⑥ CI before == head（空推送边缘）⇒ ok-empty
check('CI before == HEAD ⇒ ok-empty', () => {
  const p = pairingRangePlan({
    ciBefore: 'h1',
    beforeRefExists: true,
    mergeBaseOk: true,
    mergeBaseOut: 'h1',
    head: 'h1'
  });
  assert.equal(p.action, 'ok-empty');
});

// ⑦ ciBefore 前后空白被 trim
check('ciBefore 空白 trim', () => {
  const p = pairingRangePlan({
    ciBefore: '  abc123 \n',
    beforeRefExists: true,
    mergeBaseOk: true,
    mergeBaseOut: 'h1',
    head: 'h2'
  });
  assert.equal(p.action, 'proceed');
  assert.equal(p.rangeBase, 'abc123');
});

console.log(`${TAG} OK：${checks} 项断言全过（范围基点三态：fail / skip / ok-empty / proceed）`);
