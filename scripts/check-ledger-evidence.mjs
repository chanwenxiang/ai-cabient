#!/usr/bin/env node
/**
 * 台账证据锚点门禁（防假账）：docs 活文档里反引号引用的「证据锚点」必须真实存在。
 *
 * 为什么需要：台账长期报假账的根因是「写了已验证/已修复，但没有可核对的锚点」，
 * 锚点哪怕写了也常常是编造的（脚本名拼错、迁移文件名记错、引用已归档路径）。
 * 本门禁把「锚点真实性」变成 CI 判据：引用的东西不存在 ⇒ 红。
 *
 * 判据（只校验三类强锚点，避免误伤普通代码词）：
 *   R1  `check:xxx`            → 必须出现在 package.json 的 scripts 里；
 *   R2  `scripts/…` `docs/…` `.cursor/…`（反引号内含 `/` 的仓库路径）→ 文件必须存在；
 *   R3  `V123__xxx.sql` / `R__xxx.sql`（Flyway 迁移与种子文件名）→ 必须存在于 services 下
 *       任意层级的 db/migration 或 db/seed 目录（R__ 种子按 seed 口径一并收录）。
 *
 * 范围：docs 下全部 .md（含子目录），排除归档/历史现场目录（archive、uat-screenshots、evidence、
 * pass-notes、figures）——归档件是历史快照，里面的旧锚点不随现实演进，不在判定范围。
 *
 * 有意不做（铁律 64：日期驱动门禁会到期自爆）：
 *   - 不校验「最后核实日期」是否过期；日期只作人读字段，由活文档规范约束。
 *
 * 防假绿：扫描文件数 < MIN_SCANNED 视为范围失效直接红（防止 docs 路径改掉后静默扫空）。
 * 用法：node scripts/check-ledger-evidence.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-ledger-evidence]';
const MIN_SCANNED = 50;
const EXCLUDED_DIRS = new Set([
  'archive',
  'uat-screenshots',
  'evidence',
  'pass-notes',
  'figures',
  'node_modules'
]);

export function listMdFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (EXCLUDED_DIRS.has(entry)) continue;
      listMdFiles(full, out);
    } else if (entry.endsWith('.md')) {
      out.push(full);
    }
  }
  return out;
}

/** 收集 Flyway 迁移文件名（Windows/POSIX 均按路径段判断）。 */
function collectMigrationNames() {
  const names = new Set();
  for (const top of ['services', 'edge']) {
    const base = join(ROOT, top);
    if (!existsSync(base)) continue;
    const stack = [base];
    while (stack.length) {
      const dir = stack.pop();
      let entries;
      try {
        entries = readdirSync(dir);
      } catch {
        continue;
      }
      for (const entry of entries) {
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) {
          if (entry === 'node_modules' || entry === 'target') continue;
          stack.push(full);
        } else if (entry.endsWith('.sql') && /[/\\]db[/\\](migration|seed)[/\\]/.test(full)) {
          names.add(entry);
        }
      }
    }
  }
  return names;
}

const PATH_LIKE = /^(scripts|docs|\.cursor)\/[A-Za-z0-9][A-Za-z0-9_./-]*[A-Za-z0-9_-]$/;
const MIGRATION_LIKE = /^(V\d+__|R__)[A-Za-z0-9_]+\.sql$/;
const CHECK_LIKE = /^check:[a-z0-9][a-z0-9-]*$/;

export function checkProject(root) {
  const docsDir = join(root, 'docs');
  const findings = [];
  if (!existsSync(docsDir)) {
    findings.push(`${TAG} 范围失效：docs/ 不存在`);
    return { findings, scanned: 0 };
  }

  const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
  const scriptNames = new Set(Object.keys(pkg.scripts ?? {}));
  const migrationNames = collectMigrationNames();

  const files = listMdFiles(docsDir);
  for (const file of files) {
    const rel = file.slice(root.length + 1).replaceAll('\\', '/');
    const lines = readFileSync(file, 'utf8').split('\n');
    for (let i = 0; i < lines.length; i++) {
      const spans = lines[i].match(/`([^`\n]+)`/g) ?? [];
      for (const raw of spans) {
        const token = raw.slice(1, -1).trim();
        if (CHECK_LIKE.test(token)) {
          // R1: check:* 必须是 package.json 里真实登记的脚本
          if (!scriptNames.has(token)) {
            findings.push(`${TAG} ${rel}:${i + 1} R1 锚点脚本不存在: \`${token}\``);
          }
        } else if (MIGRATION_LIKE.test(token)) {
          // R2: Flyway 迁移文件必须存在
          if (!migrationNames.has(token)) {
            findings.push(`${TAG} ${rel}:${i + 1} R2 迁移文件不存在: \`${token}\``);
          }
        } else if (PATH_LIKE.test(token)) {
          // R3: 仓库路径必须存在（排除目录前缀式引用，见铁律 86）
          if (!existsSync(join(root, token))) {
            findings.push(`${TAG} ${rel}:${i + 1} R3 引用路径不存在: \`${token}\``);
          }
        }
      }
    }
  }

  return { findings, scanned: files.length };
}

function main() {
  const { findings, scanned } = checkProject(ROOT);
  if (scanned < MIN_SCANNED) {
    console.error(
      `${TAG} 范围失效：仅扫到 ${scanned} 个 md（阈值 ${MIN_SCANNED}），疑似 docs 路径变更导致静默扫空`
    );
    process.exit(1);
  }
  if (findings.length) {
    console.error(`${TAG} 发现 ${findings.length} 处假账锚点：`);
    for (const f of findings) console.error('  ' + f);
    process.exit(1);
  }
  console.log(`${TAG} OK：${scanned} 个活文档，锚点全部可解析`);
}

if (process.env.LEDGER_EVIDENCE_ENTRY !== 'module') {
  main();
}
