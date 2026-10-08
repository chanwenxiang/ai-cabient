#!/usr/bin/env node
/**
 * 只读诊断：检查 admin 构建输入（clients/admin-vue + packages + scripts）在
 * **工作区磁盘字节**里到底是 LF 还是 CRLF，并与 git HEAD 里的 blob 比对。
 *
 * 背景（.gitattributes 已写明的踩坑，2026-09-16 实测过）：
 *   vite/rollup 产物文件名带内容哈希，哈希取决于源文件字节。
 *   同一份源码，Windows checkout 成 CRLF、Linux CI 是 LF ⇒ 两套哈希完全不同的产物
 *   ⇒ 产物门禁必然假红。
 *   `.gitattributes` 的 `clients/** text=auto eol=lf` 只保证**新检出**是 LF；
 *   已存在的工作区文件不会被自动改写 ⇒ 必须实测，不能假设。
 */
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = 'D:/ai-generated code/ai-cabinet';

function listTracked(prefix) {
  const out = execFileSync('git', ['ls-files', '--', prefix], {
    cwd: ROOT, encoding: 'utf8', maxBuffer: 64 << 20,
  });
  return out.split('\n').map((s) => s.trim()).filter(Boolean);
}

function gitBlob(rel) {
  try {
    return execFileSync('git', ['cat-file', 'blob', `HEAD:${rel}`], {
      cwd: ROOT, encoding: 'buffer', maxBuffer: 64 << 20,
    });
  } catch {
    return null;
  }
}

const prefixes = ['clients/admin-vue', 'packages', 'scripts'];
const files = prefixes.flatMap(listTracked);

let workCrLf = 0, headCrLf = 0, mismatch = 0;
const mismatchList = [];
let binary = 0;

for (const rel of files) {
  const blob = gitBlob(rel);
  if (!blob) continue;
  // 二进制判定：blob 内含 NUL
  if (blob.includes(0)) { binary++; continue; }
  const headHas = blob.includes(Buffer.from('\r\n'));
  let work;
  try {
    work = readFileSync(join(ROOT, rel));
  } catch {
    continue;
  }
  const workHas = work.includes(Buffer.from('\r\n'));
  if (headHas) headCrLf++;
  if (workHas) workCrLf++;
  if (workHas !== headHas) {
    mismatch++;
    if (mismatchList.length < 20) {
      mismatchList.push(`  ${rel}  HEAD=${headHas ? 'CRLF' : 'LF'}  工作区=${workHas ? 'CRLF' : 'LF'}`);
    }
  }
}

console.log(`扫描构建输入 ${files.length} 个文件（二进制 ${binary} 个已跳过）`);
console.log(`  HEAD 里含 CRLF 的: ${headCrLf}`);
console.log(`  工作区含 CRLF 的  : ${workCrLf}`);
console.log(`  工作区≠HEAD 的    : ${mismatch}`);
if (mismatchList.length) {
  console.log('\n不一致文件（前 20 条）：');
  mismatchList.forEach((l) => console.log(l));
}
console.log(
  mismatch === 0
    ? '\n✅ 工作区字节与 HEAD 一致（都是 LF）⇒ 换行符不是差异来源'
    : '\n❌ 存在工作区≠HEAD 的文件 ⇒ 构建输入字节不同 ⇒ 产物哈希必然不同'
);
process.exit(mismatch === 0 ? 0 : 1);
