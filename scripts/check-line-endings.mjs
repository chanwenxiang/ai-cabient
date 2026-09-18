#!/usr/bin/env node
/**
 * 行尾门禁 —— `.gitattributes` 声明了 `eol=lf` 的文件，**索引与磁盘都不许是 CRLF**。
 *
 * 背景（2026-09-18，同类缺陷第二次出现）
 * ------------------------------------
 * 本机 `core.autocrlf=true`（检出方向 LF→CRLF）与 `.gitattributes` 的 `eol=lf`（要求磁盘留 LF）
 * 是两套**互相冲突**的规则。冲突的产物是磁盘上留 CRLF、索引里是 LF —— 归一化后内容完全相同，于是：
 *
 *   git status   → ` M path`（说改了）
 *   git diff     → 空（说没改）
 *   git add path → blob 哈希不变（纯 stat 刷新，什么都不提交）
 *
 * 这就是「纯行尾假改动」：既污染提交、又**训练人忽略 `git status`**（真正的内容改动会淹没在噪音里）。
 * 它也不只是噪音：`clients/**` 与 `packages/**` 是 vite/rollup 的构建输入，产物文件名带内容哈希，
 * 磁盘 CRLF 会让本地产物与 CI 产物哈希不同 ⇒ 产物门禁假红（见 `.gitattributes` 内的实测说明）。
 *
 * 为什么必须静态判：`.gitattributes` 自己不会去修磁盘。`git add` 也只是刷新 stat，
 * 磁盘仍是 CRLF，之后任何一次工具改写（编辑器 / OneDrive / 脚本落盘）都会让 ` M` 再次出现。
 *
 * 判据（判**有效值**，不判「文件在不在」）
 * --------------------------------------
 * 用 `git ls-files --eol --cached --others --exclude-standard` 取「索引行尾 / 磁盘行尾 / 生效属性」三元组：
 *   - 属性含 `eol=lf` 且**磁盘**行尾是 crlf/mixed  ⇒ 红（就是那些假改动）
 *   - 属性含 `eol=lf` 且**索引**行尾是 crlf/mixed  ⇒ 红（说明 CRLF blob 真被提交过）
 *
 * 带 `--others` 是刻意的：默认只列已跟踪文件，而「新建文件一落盘就是 CRLF」在 `git status` 里
 * 显示为 `??`（不是 ` M`），谁都不会觉得有问题，提交后才变成永久噪音。带上它才能在进仓库前拦住。
 * 二进制（`text=auto` 识别为 `w/-text`，如 `clients/**` 下的 118 个 png/jpg）天然不在此列。
 *
 * 防恒真：受控文件总数、声明 `eol=lf` 的文件数低于下限时直接红 —— 锚点漂了就是失去判别力，
 * 不能因为「一条都没扫到」而假绿。
 *
 * 修复
 * ----
 *   node scripts/check-line-endings.mjs --list   # 只列违规清单
 *   node scripts/check-line-endings.mjs --fix    # 就地把 CRLF 改写为 LF（只动 \r\n，字节安全）
 * 修完 `git status` 应当只剩**真正的内容改动**。
 *
 *   node scripts/check-line-endings.mjs
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-line-endings]';

const fail = (msg) => {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
};

const LIST_ONLY = process.argv.includes('--list');
const FIX = process.argv.includes('--fix');

/** 解析下限：远低于真实规模即说明输出格式已变，判据失去意义。 */
const MIN_SCANNED = 500;
const MIN_LF_MANAGED = 100;

const DISK_BAD = new Set(['w/crlf', 'w/mixed']);
const INDEX_BAD = new Set(['i/crlf', 'i/mixed']);

/** 解析 `git ls-files --eol`：`i/lf  w/lf  attr/text=auto eol=lf\t<path>`。 */
function scan() {
  let raw;
  try {
    // ⚠️ 必须带 `--others`：默认只列**已跟踪**文件，而「新文件一落盘就是 CRLF」正是
    // 最容易漏的一幕 —— 它在 `git status` 里显示为 `??`（不是 ` M`），谁都不会觉得有问题，
    // 一旦提交就变成永久噪音。带 `--others` 才能在这条路径进仓库**之前**拦住它。
    raw = execFileSync('git', ['ls-files', '--eol', '--cached', '--others', '--exclude-standard'], {
      cwd: root,
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024
    });
  } catch (e) {
    fail(`无法执行 \`git ls-files --eol\`：${e.message}`);
  }
  const rows = [];
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    // ⚠️ 不能用空白切分：attr 列自带空格（`attr/text=auto eol=lf`），只有 path 前那个是 TAB。
    const tab = line.indexOf('\t');
    if (tab < 0) continue;
    const m = line
      .slice(0, tab)
      .trim()
      .match(/^(\S+)\s+(\S+)\s+(.*)$/);
    if (!m) continue;
    rows.push({ path: line.slice(tab + 1), index: m[1], worktree: m[2], attr: m[3] });
  }
  return rows;
}

const rows = scan();
if (rows.length < MIN_SCANNED) {
  fail(
    `只解析出 ${rows.length} 个受版本控制的文件（期望 ≥ ${MIN_SCANNED}）：git ls-files --eol 的输出格式可能已变`
  );
}

const managed = rows.filter((r) => /eol=lf/.test(r.attr));
if (managed.length < MIN_LF_MANAGED) {
  fail(
    `只解析出 ${managed.length} 个声明 \`eol=lf\` 的文件（期望 ≥ ${MIN_LF_MANAGED}）：` +
      '.gitattributes 里的规则可能被删除或改写，本门禁已失去判别力'
  );
}

const diskBad = managed.filter((r) => DISK_BAD.has(r.worktree));
const indexBad = managed.filter((r) => INDEX_BAD.has(r.index));

if (LIST_ONLY) {
  for (const r of diskBad) console.log(`D  ${r.worktree}\t${r.path}`);
  for (const r of indexBad) console.log(`I  ${r.index}\t${r.path}`);
  process.exit(0);
}

let fixedCount = 0;
if (FIX && diskBad.length) {
  for (const r of diskBad) {
    const abs = resolve(root, r.path);
    // latin1 逐字节往返：只替换 \r\n，其余字节（含 BOM / 非 UTF-8）原样保留。
    const buf = readFileSync(abs);
    const next = Buffer.from(buf.toString('latin1').replace(/\r\n/g, '\n'), 'latin1');
    if (next.length !== buf.length) {
      writeFileSync(abs, next);
      fixedCount++;
    }
  }
  const after = scan();
  const stillBad = after.filter((r) => /eol=lf/.test(r.attr) && DISK_BAD.has(r.worktree));
  console.log(`${TAG} --fix 改写 ${fixedCount} 个文件，复查剩余磁盘 CRLF：${stillBad.length}`);
  if (stillBad.length) {
    console.error(stillBad.map((r) => `  - ${r.path}`).join('\n'));
    process.exit(1);
  }
  console.log(`${TAG} 提示：磁盘已归 LF，\`git status\` 现在应只剩真正的内容改动。`);
  process.exit(0);
}

if (diskBad.length || indexBad.length) {
  console.error(
    `${TAG} FAIL: ${diskBad.length} 个文件磁盘是 CRLF、${indexBad.length} 个文件索引是 CRLF，` +
      '而 .gitattributes 要求 eol=lf：'
  );
  for (const r of diskBad) console.error(`  - w/${r.worktree.slice(2)}  ${r.path}`);
  for (const r of indexBad)
    console.error(`  - i/${r.index.slice(2)}  ${r.path}   (建议 git add --renormalize)`);
  console.error(
    '\n  这些就是「纯行尾假改动」：git status 说改了、git diff 说没改、git add 什么都提交不了。\n' +
      '  修复：node scripts/check-line-endings.mjs --fix\n' +
      '  注意 `git add` 只是刷新 stat，**不会**把磁盘改回 LF，所以别拿它当修复。'
  );
  process.exit(1);
}

console.log(
  `${TAG} OK: 扫描 ${rows.length} 个文件（含未跟踪），其中 ${managed.length} 个声明 eol=lf 的文本文件索引与磁盘均为 LF`
);
