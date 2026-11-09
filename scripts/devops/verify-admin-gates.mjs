#!/usr/bin/env node
/**
 * 负向验证：两道 admin 前端门禁（write-perm-codes / csv-escape）必须「能红、报对文件、注释不误伤」。
 *
 * 铁律依据：
 *   - 铁律 4/75：改门禁必须负向验证（绿→注入红→还原绿）。
 *   - 铁律 51：判据与写侧口径一致；用事务/沙箱保证零残留。
 *
 * 为什么用沙箱而不是改真实文件：真实树是并发多会话工作区，注入后若进程被打断
 * 会留下半成品（`.gatebak` 残留、构建噪音）。这里复制最小树到 `.tmp/` 下跑。
 *
 * 用法：node scripts/devops/verify-admin-gates.mjs
 */
import { mkdirSync, writeFileSync, rmSync, readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const TAG = '[verify-admin-gates]';
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SANDBOX = join(ROOT, '.tmp', 'gate-sandbox-admin');

const PERM_GATE = join(ROOT, 'scripts', 'check-admin-write-perm-codes.mjs');
const CSV_GATE = join(ROOT, 'scripts', 'check-admin-csv-escape.mjs');

let failures = 0;
function ok(msg) {
  console.log(`PASS  ${msg}`);
}
function bad(msg) {
  console.error(`FAIL  ${msg}`);
  failures += 1;
}

function runGate(script, env) {
  const r = spawnSync(process.execPath, [script], {
    cwd: ROOT,
    encoding: 'utf8',
    shell: false,
    env: { ...process.env, ...env }
  });
  return { code: r.status ?? 1, out: `${r.stdout || ''}${r.stderr || ''}` };
}

/** 真实树指纹：注入前后必须一致（证明没碰真实文件）。 */
function treeFingerprint(dir) {
  const h = createHash('sha256');
  const walk = (d) => {
    for (const e of readdirSync(d, { withFileTypes: true }).sort((a, b) =>
      a.name.localeCompare(b.name)
    )) {
      const full = join(d, e.name);
      if (e.isDirectory()) walk(full);
      else h.update(full).update(readFileSync(full));
    }
  };
  walk(dir);
  return h.digest('hex');
}

// ── 0. 真实树基线 ─────────────────────────────────────────────────────────
const ADMIN_SRC = join(ROOT, 'clients', 'admin-vue', 'src');
const before = treeFingerprint(ADMIN_SRC);

const permBase = runGate(PERM_GATE, {});
if (permBase.code !== 0) bad(`权限门禁基线应为绿，实得 exit ${permBase.code}\n${permBase.out}`);
else ok('权限门禁 基线绿（真实树 236 文件）');

const csvBase = runGate(CSV_GATE, {});
if (csvBase.code !== 0) bad(`CSV 门禁基线应为绿，实得 exit ${csvBase.code}\n${csvBase.out}`);
else ok('CSV 门禁 基线绿');

// ── 1. 沙箱准备 ───────────────────────────────────────────────────────────
rmSync(SANDBOX, { recursive: true, force: true });
const PERM_DIR = join(SANDBOX, 'perm');
mkdirSync(PERM_DIR, { recursive: true });

const PERM_ENV = { ADMIN_WRITE_PERM_ROOT: PERM_DIR, ADMIN_WRITE_PERM_MIN_FILES: '1' };

// 合法：两个都是写码，无前缀关系
writeFileSync(
  join(PERM_DIR, 'OkWriteCodes.vue'),
  `<script setup lang="ts">
const acts = [{ key: 'a', perm: ['ops:sku:edit', 'ops:sku:delete'] }];
</script>
`
);
// 合法：与后端显式 OR 双门一致（OpsGapFeaturesController: {rbac:assign, rbac:assign:device}）
writeFileSync(
  join(PERM_DIR, 'OkBackendOr.vue'),
  `<script setup lang="ts">
const acts = [{ key: 'a', perm: ['ops:rbac:assign', 'ops:rbac:assign:device'] }];
</script>
`
);

let r = runGate(PERM_GATE, PERM_ENV);
if (r.code !== 0) bad(`权限门禁：合法用例不得报红，实得 exit ${r.code}\n${r.out}`);
else ok('权限门禁 合法用例（写码并列 / 后端 OR 组合）不误伤');

// 违规：数组里混入查看码
writeFileSync(
  join(PERM_DIR, 'BadPermArray.vue'),
  `<script setup lang="ts">
const canDelete = true;
const acts = [{ key: 'delete', perm: ['ops:feedback', 'ops:feedback:reply'] }];
</script>
`
);
r = runGate(PERM_GATE, PERM_ENV);
if (r.code === 0) bad('权限门禁：注入查看码数组应报红，实得绿');
else if (!r.out.includes('BadPermArray.vue')) bad(`权限门禁：报红但未指到注入文件\n${r.out}`);
else ok('权限门禁 注入 perm 数组查看码 → 红且报对文件');

// 违规：hasPerm OR 形态
writeFileSync(
  join(PERM_DIR, 'BadPermOr.vue'),
  `<script setup lang="ts">
const auth = { hasPerm: (_c: string) => true };
const canDelete = () => auth.hasPerm('ops:feedback') || auth.hasPerm('ops:feedback:reply');
</script>
`
);
r = runGate(PERM_GATE, PERM_ENV);
if (r.code === 0) bad('权限门禁：注入 hasPerm OR 应报红，实得绿');
else if (!r.out.includes('BadPermOr.vue')) bad(`权限门禁：报红但未指到 BadPermOr.vue\n${r.out}`);
else ok('权限门禁 注入 hasPerm OR → 红且报对文件');

// 注释不误伤：注释里的旧写法不得命中
rmSync(join(PERM_DIR, 'BadPermArray.vue'));
rmSync(join(PERM_DIR, 'BadPermOr.vue'));
writeFileSync(
  join(PERM_DIR, 'Commented.vue'),
  `<script setup lang="ts">
// 历史写法（已删）：perm: ['ops:feedback', 'ops:feedback:reply']
const acts: unknown[] = [];
</script>
`
);
r = runGate(PERM_GATE, PERM_ENV);
if (r.code !== 0) bad(`权限门禁：注释不应命中，实得 exit ${r.code}\n${r.out}`);
else ok('权限门禁 注释态不误伤');

// ── 2. CSV 门禁 ───────────────────────────────────────────────────────────
const CSV_DIR = join(SANDBOX, 'csv');
mkdirSync(CSV_DIR, { recursive: true });
const CSV_ENV = { ADMIN_CSV_ESCAPE_ROOT: CSV_DIR, ADMIN_CSV_ESCAPE_MIN_FILES: '1' };

writeFileSync(
  join(CSV_DIR, 'OkCsv.vue'),
  `<script setup lang="ts">
import { downloadCsv } from '@/utils/csv';
function exportIt() {
  const blob = new Blob(['a,b'], { type: 'text/csv;charset=utf-8' });
  void blob;
  downloadCsv('x.csv', ['a', 'b'], [['1', '2']]);
}
</script>
`
);
r = runGate(CSV_GATE, CSV_ENV);
if (r.code !== 0) bad(`CSV 门禁：合规用例不得报红，实得 exit ${r.code}\n${r.out}`);
else ok('CSV 门禁 合规用例（downloadCsv）不误伤');

// 导入控件不算导出（真实树曾因此误报）
writeFileSync(
  join(CSV_DIR, 'ImportOnly.vue'),
  `<template><input type="file" accept=".csv,text/csv" /></template>
`
);
r = runGate(CSV_GATE, CSV_ENV);
if (r.code !== 0) bad(`CSV 门禁：仅导入控件不应报红，实得 exit ${r.code}\n${r.out}`);
else ok('CSV 门禁 导入控件（accept=text/csv）不误伤');
rmSync(join(CSV_DIR, 'ImportOnly.vue'));

// 违规：手写拼装
writeFileSync(
  join(CSV_DIR, 'BadCsv.vue'),
  `<script setup lang="ts">
function exportIt(rows: string[][]) {
  const csv =
    '\\ufeff' + rows.map((row) => row.map((c) => \`"\${String(c).replace(/"/g, '""')}"\`).join(',')).join('\\r\\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  void blob;
}
</script>
`
);
r = runGate(CSV_GATE, CSV_ENV);
if (r.code === 0) bad('CSV 门禁：注入手写转义应报红，实得绿');
else if (!r.out.includes('BadCsv.vue')) bad(`CSV 门禁：报红但未指到 BadCsv.vue\n${r.out}`);
else ok('CSV 门禁 注入手写转义 → 红且报对文件');

// ── 3. 清理与零残留 ───────────────────────────────────────────────────────
rmSync(SANDBOX, { recursive: true, force: true });
if (existsSync(SANDBOX)) bad(`沙箱未清理干净：${SANDBOX}`);
else ok('沙箱已删除（零残留）');

const after = treeFingerprint(ADMIN_SRC);
if (before !== after) bad('真实树被写入（指纹变化）');
else ok('真实树指纹未变（零写入）');

if (failures > 0) {
  console.error(`\n${TAG} FAIL: ${failures} 项未通过`);
  process.exit(1);
}
console.log(
  `\n${TAG} PASS: 两道门禁三态验证全部通过（基线绿 / 红能红且报对文件 / 注释与合理设计不误伤 / 零残留）`
);
