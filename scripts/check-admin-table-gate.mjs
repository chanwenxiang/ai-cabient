#!/usr/bin/env node
/**
 * CI / 本地：管理后台表格收敛门禁（CrudTable 化后的防回潮闸）。
 *
 *   node scripts/check-admin-table-gate.mjs               # 按基线校验
 *   node scripts/check-admin-table-gate.mjs --write-baseline  # 重新生成基线（收紧后手动跑）
 *
 * 四类指标：
 *   1. rawElTableFiles  views 里仍直接写 <el-table> 的文件（应只减不增）
 *   2. headerSortFiles  views 里表头排序残留（sortable="custom" / :default-sort / @sort-change，
 *      全站约定为工具条「升/降序」按钮，无表头箭头）
 *   3. nativeButtons    views 里原生 <button> 计数（应走 el-button / TableActions）
 *   4. copySync         shared-uni 共享组件的本地拷贝必须与共享包一致（uni easycom 约定）
 *
 * 基线：scripts/admin-table-gate-baseline.json。出现「新增文件 / 计数上涨」即失败；
 * 指标改善时会提示收紧基线（人工确认后提交新基线）。
 */
import { readFileSync, writeFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const viewsDir = join(root, 'clients/admin-vue/src/views');
const baselinePath = join(__dirname, 'admin-table-gate-baseline.json');
const WRITE = process.argv.includes('--write-baseline');

const fail = (msg) => {
  console.error(`[admin-table-gate] FAIL ${msg}`);
};
const walkVue = (dir, out = []) => {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walkVue(p, out);
    else if (name.endsWith('.vue')) out.push(p);
  }
  return out;
};
const rel = (p) => relative(root, p).replaceAll('\\', '/');

const viewFiles = walkVue(viewsDir);
const read = (p) => {
  try {
    return readFileSync(p, 'utf8');
  } catch {
    return '';
  }
};

// —— 计算当前指标 ——————————————————————————————————————————
const rawElTableFiles = [];
const headerSortFiles = [];
const nativeButtons = {};
for (const f of viewFiles) {
  const src = read(f);
  if (/<el-table[>\s]/.test(src)) rawElTableFiles.push(rel(f));
  if (/sortable="custom"|:default-sort|@sort-change/.test(src)) headerSortFiles.push(rel(f));
  const n = (src.match(/<button[\s>]/g) || []).length;
  if (n > 0) nativeButtons[rel(f)] = n;
}
rawElTableFiles.sort();
headerSortFiles.sort();

// —— 共享组件拷贝一致性 ————————————————————————————————————
const stripCopyComments = (lines) => {
  const out = [];
  let inHead = true;
  for (const l of lines) {
    const t = l.trim();
    if (
      inHead &&
      (t === '' ||
        t === '<!--' ||
        t === '-->' ||
        t.includes('Keep this file identical') ||
        t.includes('Keep in sync') ||
        t.includes('Canonical:'))
    )
      continue;
    inHead = false;
    out.push(l);
  }
  return out;
};
const copySyncDrift = [];
const canonDir = join(root, 'packages/shared-uni/src/components');
for (const mp of ['consumer-mp', 'merchant-mp']) {
  const localDir = join(root, `clients/${mp}/src/components`);
  if (!existsSync(localDir)) continue;
  for (const name of readdirSync(canonDir)) {
    const local = join(localDir, name);
    if (!existsSync(local)) continue;
    const a = stripCopyComments(readFileSync(join(canonDir, name), 'utf8').split('\n'));
    const b = stripCopyComments(readFileSync(local, 'utf8').split('\n'));
    const same = a.length === b.length && a.every((l, i) => l === b[i]);
    if (!same) copySyncDrift.push(`${mp}/src/components/${name}`);
  }
}

// —— 输出 / 比对基线 ——————————————————————————————————————
const current = { rawElTableFiles, headerSortFiles, nativeButtons, copySyncDrift };

if (WRITE) {
  writeFileSync(baselinePath, JSON.stringify(current, null, 2) + '\n');
  console.log(
    `[admin-table-gate] 基线已写入 ${relative(root, baselinePath)}：` +
      `裸表格 ${rawElTableFiles.length} 文件 / 表头排序 ${headerSortFiles.length} 文件 / ` +
      `原生按钮 ${Object.keys(nativeButtons).length} 文件`
  );
  process.exit(0);
}

const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));
const errors = [];

const diffList = (name, base, cur) => {
  const baseSet = new Set(base);
  const added = cur.filter((f) => !baseSet.has(f));
  if (added.length) {
    errors.push(
      `${name} 新增 ${added.length} 个文件（应走 CrudTable / 工具条排序）：\n    ${added.join('\n    ')}`
    );
  }
  if (cur.length < base.length) {
    console.log(
      `[admin-table-gate] ${name} 较基线减少 ${base.length - cur.length} 个文件，可收紧基线（--write-baseline 后提交）`
    );
  }
};
diffList('裸 <el-table>', baseline.rawElTableFiles || [], rawElTableFiles);
diffList('表头排序残留', baseline.headerSortFiles || [], headerSortFiles);

const baseBtn = baseline.nativeButtons || {};
for (const [f, n] of Object.entries(nativeButtons)) {
  if (!(f in baseBtn)) {
    errors.push(`原生 <button> 新增于 ${f} (${n} 处，应走 el-button/TableActions)`);
  } else if (n > baseBtn[f]) {
    errors.push(`原生 <button> 在 ${f} 增加：${baseBtn[f]} → ${n}`);
  }
}
for (const [f, n] of Object.entries(baseBtn)) {
  const cur = nativeButtons[f];
  if (cur !== undefined && cur < n) {
    console.log(`[admin-table-gate] 原生 <button> 在 ${f} 减少：${n} → ${cur}，可收紧基线`);
  }
}
if (copySyncDrift.length) {
  errors.push(
    `shared-uni 组件拷贝漂移（请以 packages/shared-uni/src/components 为准同步）：\n    ${copySyncDrift.join('\n    ')}`
  );
}

if (errors.length) {
  for (const e of errors) fail(e);
  process.exit(1);
}
console.log(
  `[admin-table-gate] OK（裸表格 ${rawElTableFiles.length} / 表头排序 ${headerSortFiles.length} / ` +
    `原生按钮 ${Object.keys(nativeButtons).length} 文件 / 拷贝一致）`
);
