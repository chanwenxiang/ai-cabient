#!/usr/bin/env node
/**
 * 门禁：PowerShell 脚本字符集一致性（审计 P1-13）。
 *
 * 规则：.ps1 含**任何非 ASCII** 字符 ⇒ 必须带 UTF-8 BOM。
 * 原因：Windows PowerShell 5.1 对无 BOM 文件按 ANSI（GBK/CP936）解析，中文注释/字符串
 * 直接乱码；含中文的无 BOM 脚本在他人机器上必炸。纯 ASCII 脚本无需 BOM（ASCII≡UTF-8）。
 *
 * 修复：`python -c "..."` 或编辑器另存为「带 BOM 的 UTF-8」。
 * 用法：node scripts/check-ps1-bom.mjs
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

function* walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      yield* walk(full);
    } else if (entry.name.endsWith('.ps1')) {
      yield full;
    }
  }
}

const offenders = [];
let checked = 0;
for (const file of walk(join(root, 'scripts'))) {
  checked++;
  const raw = readFileSync(file);
  const hasBom = raw.length >= 3 && raw[0] === 0xef && raw[1] === 0xbb && raw[2] === 0xbf;
  if (hasBom) continue;
  const hasNonAscii = raw.some((b) => b > 0x7f);
  if (hasNonAscii) {
    offenders.push(relative(root, file).replace(/\\/g, '/'));
  }
}

if (offenders.length) {
  console.error(
    '[check-ps1-bom] 以下 .ps1 含非 ASCII 字符但无 UTF-8 BOM（PS 5.1 将按 ANSI 解析乱码）：'
  );
  for (const o of offenders) console.error(`  - ${o}`);
  console.error('  修复：为文件加 UTF-8 BOM（前 3 字节 EF BB BF），或移除非 ASCII 内容。');
  process.exit(1);
}
console.log(`[check-ps1-bom] OK：${checked} 个 .ps1 无「非 ASCII 缺 BOM」违规`);
