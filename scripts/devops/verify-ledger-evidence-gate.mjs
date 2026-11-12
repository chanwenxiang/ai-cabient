#!/usr/bin/env node
/**
 * check-ledger-evidence 门禁的负向验证（铁律 75：门禁判据必须能红）。
 *
 * 三态（全程沙箱，真实树一个字节都不写）：
 *   ⓪ 基线：未注入的沙箱副本必须绿 ⇒ 否则「红」可能只是拷贝不完整造成的假象；
 *   ① 红：对每条判据各注入一处真违规（R1/R2/R3/范围下限）→ 门禁必须非零退出
 *      且报出对应句子（逐条单独注入，某条恒真时不会被别的注入掩盖）；
 *   ② 零污染：真实树全部目标文件 md5 前后一致、沙箱已删除。
 *
 * 用法：node scripts/devops/verify-ledger-evidence-gate.mjs
 */
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runAsync } from '../lib/async-spawn.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const GATE_REL = 'scripts/check-ledger-evidence.mjs';
const SANDBOX = resolve(ROOT, '.tmp', `ledger-evidence-negative-${process.pid}`);
const sb = (rel) => join(SANDBOX, rel);

/** 门禁读取面：沙箱只拷这些（docs 活文档树 + package.json + gate 自身 + 最小迁移面）。 */
const REAL_TARGETS = ['package.json', GATE_REL];

function hashFile(p) {
  return createHash('md5').update(readFileSync(p)).digest('hex');
}

/** 收集真实 docs 树全部文件（排除目录由 gate 自身逻辑处理，拷贝整树）。 */
function listFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) listFiles(full, out);
    else out.push(full);
  }
  return out;
}

/** 沙箱迁移/种子面：按真实树的同名空壳拷贝（gate 只按文件名校验 R3）。 */
function copyMigrationFaces() {
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
          const rel = full.slice(ROOT.length + 1);
          mkdirSync(dirname(sb(rel)), { recursive: true });
          writeFileSync(sb(rel), '-- sandbox stub\n');
        }
      }
    }
  }
}

/** 重建干净沙箱（基线与每条探针共用同一初始化）。 */
function setupSandbox() {
  if (existsSync(SANDBOX)) rmSync(SANDBOX, { recursive: true, force: true });
  mkdirSync(SANDBOX, { recursive: true });
  for (const rel of REAL_TARGETS) cpSync(join(ROOT, rel), sb(rel));
  cpSync(join(ROOT, 'docs'), sb('docs'), { recursive: true });
  // R2 路径校验需要 scripts/ 与 .cursor/ 真实存在
  if (existsSync(join(ROOT, 'scripts'))) cpSync(join(ROOT, 'scripts'), sb('scripts'), { recursive: true });
  if (existsSync(join(ROOT, '.cursor'))) cpSync(join(ROOT, '.cursor'), sb('.cursor'), { recursive: true });
  copyMigrationFaces();
}

async function runGate(cwd) {
  const r = await runAsync(process.execPath, [GATE_REL], { cwd });
  // runAsync 约定字段名是 status（永不为 null），不是 code
  return { code: r.status, out: `${r.stdout ?? ''}\n${r.stderr ?? ''}` };
}

async function main() {
  setupSandbox();

  // 快照真实树目标面（零污染断言用）
  const before = new Map();
  for (const top of ['package.json', 'scripts', '.cursor', 'docs']) {
    const base = join(ROOT, top);
    if (!existsSync(base)) continue;
    if (statSync(base).isFile()) {
      before.set(top, hashFile(base));
      continue;
    }
    for (const f of listFiles(base)) {
      const rel = f.slice(ROOT.length + 1);
      before.set(rel, hashFile(f));
    }
  }

  // ⓪ 基线必须绿
  const base = await runGate(SANDBOX);
  if (base.code !== 0) {
    console.error('[verify-ledger-evidence] ⓪ 基线沙箱不为绿（拷贝不完整或 gate 判据退化）：');
    console.error(base.out);
    process.exit(1);
  }
  console.log('[verify-ledger-evidence] ⓪ 基线绿');

  const probes = [
    {
      name: 'R1 幽灵 check 脚本名',
      inject: (docDir) => {
        const f = join(docDir, 'PROBE_LEDGER.md');
        writeFileSync(f, '# 探针台账\n\n状态：已验证（`check:ghost-gate-not-registered`）\n');
        return 'check:ghost-gate-not-registered';
      },
      cleanup: (docDir) => rmSync(join(docDir, 'PROBE_LEDGER.md'), { force: true })
    },
    {
      name: 'R2 幽灵仓库路径',
      inject: (docDir) => {
        const f = join(docDir, 'PROBE_LEDGER.md');
        writeFileSync(f, '# 探针台账\n\n证据：`scripts/definitely-missing-anchor.mjs`\n');
        return 'scripts/definitely-missing-anchor.mjs';
      },
      cleanup: (docDir) => rmSync(join(docDir, 'PROBE_LEDGER.md'), { force: true })
    },
    {
      name: 'R3 幽灵迁移文件名',
      inject: (docDir) => {
        const f = join(docDir, 'PROBE_LEDGER.md');
        writeFileSync(f, '# 探针台账\n\n证据：`V99999__ghost_migration.sql`\n');
        return 'V99999__ghost_migration.sql';
      },
      cleanup: (docDir) => rmSync(join(docDir, 'PROBE_LEDGER.md'), { force: true })
    },
    {
      name: '范围下限（静默扫空必须红）',
      inject: (docDir) => {
        // 删到只剩 1 个 md，扫描数跌破 MIN_SCANNED
        const keep = join(docDir, 'PROJECT_KNOWLEDGE.md');
        for (const f of listFiles(docDir)) if (f !== keep && f.endsWith('.md')) rmSync(f, { force: true });
        return '范围失效';
      },
      cleanup: null // 沙箱整体重建，无需逐个还原
    }
  ];

  let failed = 0;
  for (const probe of probes) {
    // 每条从干净沙箱重新开始：先还原上一次注入
    setupSandbox();

    const docDir = sb('docs');
    const needle = probe.inject(docDir);
    const r = await runGate(SANDBOX);
    const caught = r.code !== 0 && r.out.includes(needle);
    if (!caught) {
      console.error(`[verify-ledger-evidence] ① 判据「${probe.name}」未挣到红（exit=${r.code}，输出不含 ${needle}）：`);
      console.error(r.out);
      failed++;
    } else {
      console.log(`[verify-ledger-evidence] ①「${probe.name}」红 ✓`);
    }
    if (probe.cleanup) probe.cleanup(docDir);
  }
  if (failed) {
    rmSync(SANDBOX, { recursive: true, force: true });
    process.exit(1);
  }

  // ② 零污染
  let polluted = 0;
  for (const [rel, h] of before) {
    const p = join(ROOT, rel);
    if (!existsSync(p) || hashFile(p) !== h) {
      console.error(`[verify-ledger-evidence] ② 真实树被污染: ${rel}`);
      polluted++;
    }
  }
  rmSync(SANDBOX, { recursive: true, force: true });
  if (polluted) process.exit(1);
  console.log('[verify-ledger-evidence] ② 真实树 md5 零污染，沙箱已删除');
  console.log('[verify-ledger-evidence] PASS：四条判据逐条挣红 + 零污染');
}

main().catch((e) => {
  console.error('[verify-ledger-evidence] 探针异常：', e);
  process.exit(1);
});
