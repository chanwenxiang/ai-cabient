#!/usr/bin/env node
/** S8③ 配套夹具：共享产物「未跟踪 ≠ 显式忽略」判据。 */
import assert from 'node:assert/strict';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(join(dirname(fileURLToPath(import.meta.url)), '..'));
const gate = await import(
  pathToFileURL(join(ROOT, 'scripts', 'check-shared-dist-tracked.mjs')).href
);

const pkgs = ['a', 'b', 'c', 'd'];
assert.deepEqual(gate.evaluate(pkgs, new Set(['a', 'b', 'c']), new Set(['d'])), [], '基线应绿');

const red = gate.evaluate(pkgs, new Set(['a', 'b']), new Set());
assert.ok(
  red.some((e) => e.includes('c:')),
  '未跟踪且未忽略必须红'
);
assert.ok(
  gate.evaluate(['a'], new Set(['a']), new Set()).some((e) => e.includes('R0')),
  '扫描面塌了必须红'
);
assert.ok(
  gate.evaluate(pkgs, new Set(), new Set(['a', 'b', 'c', 'd'])).some((e) => e.includes('R0')),
  '全部跳过也必须红'
);

console.log('[check-shared-dist-tracked.test] OK：4 项断言全过');
