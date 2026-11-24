#!/usr/bin/env node
import assert from 'node:assert/strict';
import { headerHelpProblems, titleBlocks } from './check-admin-header-help.mjs';

let checks = 0;
function check(name, fn) {
  fn();
  checks += 1;
  console.log(`  ✓ ${name}`);
}

const short = `<div class="page-card-head__title"><span class="title">页面</span><span class="hint">实时范围：近 7 天</span></div>`;
check('短标签与动态读数保留', () => {
  assert.deepEqual(headerHelpProblems(short), []);
});

check('长说明常驻=红', () => {
  const src = `<div class="page-card-head__title"><span class="hint">这是一段超过二十四个字符的常驻业务口径说明，应该收进问号</span></div>`;
  assert.ok(headerHelpProblems(src).some((p) => p.includes('常驻')));
});

check('常驻 hint 内嵌 HelpTip=红', () => {
  const src = `<div class="page-card-head__title"><span class="hint">短说明<HelpTip content="完整口径" /></span></div>`;
  assert.ok(headerHelpProblems(src).some((p) => p.includes('双显')));
});

check('标题旁独立 HelpTip=绿', () => {
  const src = `<div class="page-card-head__title"><span class="title">页面<HelpTip content="完整口径" /></span></div>`;
  assert.deepEqual(headerHelpProblems(src), []);
});

check('page-hint 双显=红', () => {
  const src = `<span class="page-hint">设备编号 1<HelpTip content="字段口径" /></span>`;
  assert.ok(headerHelpProblems(src).some((p) => p.includes('page-hint/header-hint')));
});

check('header-hint 动态短读数=绿', () => {
  const src = `<span class="header-hint">当前范围：{{ rangeLabel }}</span>`;
  assert.deepEqual(headerHelpProblems(src), []);
});

check('锚点缺失不得假绿', () => {
  assert.deepEqual(titleBlocks('<div class="other"><span class="hint">长说明</span></div>'), []);
});

console.log(`[check-admin-header-help.test] OK：${checks} 项断言全过`);
