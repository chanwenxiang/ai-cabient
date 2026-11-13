#!/usr/bin/env node
/**
 * 门禁：已合入基线的 Flyway 脚本**不可删、不可改名、不可改内容**。
 *
 * 背景（2026-10-23 实测）：删除已应用的 `R__seed_consumer_ad_banner.sql` 让
 * trade-service 启动崩溃、栈环 4 分钟。正解是「墓碑文件」（保留旧文件、内容改为
 * 幂等清理/空操作），不是删文件。Flyway 以 version+checksum 记账：已应用脚本一旦
 * 消失或内容变化，下一次 validate 直接失败；repair 会掩盖问题，反而把「库与仓库
 * 不一致」变成永久状态。
 *
 * 另一形态（lessons #280/#298）：同一版本号两份脚本、或把已发布迁移改名重排——
 * 前者由 check-migration-safety 的 duplicate 扫描兜住，本门禁只负责「已有文件不得动」。
 *
 * 判据（基线 = MIGRATION_BASE_REF，默认 origin/dev）：
 *   1. `git diff --name-status --diff-filter=MDR <base>...HEAD` —— 已提交但动了基线文件；
 *   2. `git diff --name-status --diff-filter=MDR <base>` —— 工作树/暂存区动了基线文件
 *      （只看基线里已存在的路径；本分支新增的迁移在推送前仍可自由修改）。
 *
 * 🔴 本分支新增、尚未合入基线的迁移**不**受本门禁约束——它们还没被任何库应用。
 * 🔴 git 不可用 / 基线 ref 不存在一律 fail-closed：枚举不到 ≠ 没有违规。
 *
 * 🔴 **修复批豁免通道（2026-11-13，S14 落地时启用）**：已合入基线的迁移**内容修改**
 *   （M）可带文件头 `-- MIGRATION_IMMUTABLE_EXEMPT: <理由>` 声明豁免——命中豁免的
 *   修改留痕（文件+理由打进日志），不静默。**仅 M 开放**：D（删除）是 2026-10-23
 *   崩溃事故的形态、R（改名）会破坏 version 记账，两者保持硬红不豁免。豁免用于
 *   「已应用迁移的历史遗留修正」（如 S14 抓到的 47 处引用已 DROP 表的死语句清理），
 *   须以测试库重建/flyway repair 消化 checksum 变化为前提（生产库不适用）。
 *
 *
 * 负向验证：`git mv V400__x.sql V401__x.sql` 或改一行已提交迁移的注释 => 本门禁必须红；
 * 新增一个 V401__*.sql 文件（基线里没有）=> 仍绿。
 *
 *   node scripts/check-migration-immutable.mjs
 */
import { gitAsync } from './lib/async-spawn.mjs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { existsSync, readFileSync } from 'node:fs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-migration-immutable]';
const MIGRATION_PATHS = ['services'];
const ACK_RE = /^\s*--+\s*MIGRATION_IMMUTABLE_EXEMPT\s*:\s*(\S.*)$/im;

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const candidates = [process.env.MIGRATION_BASE_REF, 'origin/dev', 'origin/main', 'dev'].filter(
  Boolean
);
let base = null;
for (const ref of candidates) {
  const r = await gitAsync(['rev-parse', '--verify', '--quiet', `${ref}^{commit}`], { cwd: ROOT });
  if (r.ok) {
    base = ref;
    break;
  }
}
if (!base) {
  fail(
    '找不到基线 ref（试过 ' +
      candidates.join(', ') +
      '）——无法判定哪些迁移已经合入。不要当成「没有违规」；请先 fetch 或显式设置 MIGRATION_BASE_REF。'
  );
}

/** 解析 git diff --name-status 输出，仅保留 migration 目录下的 .sql */
function parseNameStatus(out) {
  const hits = [];
  for (const line of out.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const parts = line.split('\t');
    const status = parts[0];
    // R100\told\tnew / D\tpath / M\tpath
    const paths = status.startsWith('R') ? parts.slice(1, 3) : parts.slice(1, 2);
    if (paths.some((p) => /\/db\/migration\/.*\.sql$/.test(p))) {
      hits.push({ status, paths });
    }
  }
  return hits;
}

const checks = [
  [
    '已提交变更（base...HEAD）',
    ['diff', '--name-status', '--diff-filter=MDR', `${base}...HEAD`, '--', ...MIGRATION_PATHS]
  ],
  [
    '工作树/暂存区变更（vs base）',
    ['diff', '--name-status', '--diff-filter=MDR', base, '--', ...MIGRATION_PATHS]
  ]
];

const violations = [];
for (const [label, args] of checks) {
  const r = await gitAsync(args, { cwd: ROOT });
  if (!r.ok) {
    fail(`git ${args.join(' ')} 失败（${r.spawnError || 'non-zero exit'}）：${r.out}`);
  }
  for (const hit of parseNameStatus(r.out)) {
    violations.push({ label, ...hit });
  }
}

if (violations.length > 0) {
  const hard = [];
  const acknowledged = [];
  for (const v of violations) {
    if (v.status !== 'M') {
      hard.push(v);
      continue;
    }
    // M：读取文件内容查豁免头（工作树优先，其次 HEAD）
    const rel = v.paths[0];
    let content = '';
    if (existsSync(join(ROOT, rel))) {
      try {
        content = readFileSync(join(ROOT, rel), 'utf8');
      } catch {
        content = '';
      }
    } else {
      const shown = await gitAsync(['show', `HEAD:${rel}`], { cwd: ROOT });
      if (shown.ok) content = shown.out || '';
    }
    const ack = content.match(ACK_RE);
    if (ack) {
      acknowledged.push({ ...v, reason: ack[1].trim() });
    } else {
      hard.push(v);
    }
  }
  if (hard.length > 0) {
    const detail = hard.map((v) => `  - [${v.label}] ${v.status} ${v.paths.join(' -> ')}`);
    fail(
      `已合入基线的 Flyway 脚本被删除/改名/修改（基线 ${base}）：\n${detail.join('\n')}\n` +
        '正解：保留旧文件不动，新增一条迁移；要下线种子数据时把旧文件改成幂等墓碑（不要删文件）。' +
        '内容修改须带 `-- MIGRATION_IMMUTABLE_EXEMPT: <理由>` 头（仅 M 开放，D/R 不豁免）。'
    );
  }
  for (const a of acknowledged) {
    console.log(`${TAG} 豁免留痕：${a.paths[0]}（${a.label}）—— 理由：${a.reason}`);
  }
}

console.log(`${TAG} OK：基线 ${base} 里已存在的迁移文件均未被删除/改名/改内容（新增迁移不受限）`);
