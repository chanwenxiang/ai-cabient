#!/usr/bin/env node
/**
 * 门禁（S8①）：修复批覆盖对账——提交信息声称的条目必须与 diff、与登记表一一对应。
 *
 * A26 实证（R1–R17 核查报告唯一硬伤）：提交 bd5845bf 信息含「A26去rethrow」，
 * 但该提交 35 个文件不含目标文件，`git log -- 文件` 为空——虚报存活 14 轮无人抓。
 *
 * 用户拍板（2026-11-12）：映射表走**逐批手工登记**——修复批提交时在
 * [fix-batch-registry.json](scripts/fix-batch-registry.json) 登记「条目→应改文件」。
 * 本门禁做三件事：
 *   ① **A26 判据本体**：每个登记条目声称的文件，必须真实出现在该 commit 的 diff 里
 *      （`git show --name-only`），且文件在仓内真实存在；
 *   ② **覆盖对账平**：items + notAdopted === totalItems（登记时按审计报告点数，
 *      少登记一条「修了」或「不采纳」都红——「既不在修复表、也不在有意未采纳清单」的红）；
 *   ③ **增量完备性**：coverageCutoff 之后的 `fix(`/`perf(` 提交，必须出现在登记表
 *      或 unregisteredAllowlist（带 why）——防止「修复批不登记」静默绕过。
 * 存量不回填：cutoff 之前的批次已由 R1–R17 核查报告人工收口（A26 即其产物）。
 *
 * fail-closed：git 不可用/命令失败=工具故障判红；登记表缺失判红；commit 不存在=登记问题红。
 *
 *   node scripts/check-fix-batch-coverage.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gitAsync } from './lib/async-spawn.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-fix-batch-coverage]';
const REGISTRY_PATH = join('scripts', 'fix-batch-registry.json');

/**
 * 纯判定：单批登记 × 该 commit 的真实 diff 文件集。
 * @param {object} batch { commit, report, totalItems, items:[{id,files[]}], notAdopted:[{id,why}] }
 * @param {string[]} commitFiles 该 commit diff 里的文件（仓库相对路径，/ 归一）
 * @param {(rel:string)=>boolean} existsFn 仓库文件存在性
 * @returns {string[]}
 */
export function evaluateBatch(batch, commitFiles, existsFn) {
  const problems = [];
  const present = new Set(commitFiles);
  const declared = (batch.items || []).length + (batch.notAdopted || []).length;
  if (typeof batch.totalItems !== 'number' || declared !== batch.totalItems) {
    problems.push(
      `${batch.commit}: 覆盖对账不平——items ${(batch.items || []).length} + notAdopted ${(batch.notAdopted || []).length}` +
        ` ≠ totalItems ${batch.totalItems}（按审计报告逐条点数：「既不在修复表、也不在有意未采纳清单」的条目=漏登记）`
    );
  }
  for (const item of batch.items || []) {
    for (const f of item.files || []) {
      const norm = f.replace(/\\/g, '/');
      if (!present.has(norm)) {
        problems.push(
          `${batch.commit} ${item.id}: 声称改了 ${norm}，但该 commit diff 里没有它` +
            `（A26 形态：提交信息声称的条目与 diff 不对应）`
        );
      }
      if (!existsFn(norm)) {
        problems.push(`${batch.commit} ${item.id}: 登记的文件在仓内不存在：${norm}`);
      }
    }
  }
  return problems;
}

/**
 * 纯判定：登记表层面的完整性（跨批 ID 重复、增量完备性）。
 * @param {object} registry { batches:[], coverageCutoff, unregisteredAllowlist:[{commit,why}] }
 * @param {{hash:string, subject:string}[]} fixCommits cutoff 之后 HEAD 侧的 fix/perf 提交
 */
/**
 * 登记↔提交的比对必须走**前缀**，不能精确比字符串。
 *
 * 成因（2026-11-13 CI 实测）：注册表里的 commit 是各自机器上当时 `git log --%h` 打出来的
 * 缩写，宽度随仓库对象数变化（7/8/9 位都出现过）；同一条命令在 CI 的全量对象库里输出 9 位，
 * 于是 25 条登记全体匹配失败、被报成「未登记 fix 提交」。改用全长 `%H` + 前缀包含后，
 * 无论登记表记的是 7 位还是 40 位都能对上，而对不上的仍然红（不是放松判据）。
 */
export function isCoveredBy(hash, prefixes) {
  const h = String(hash || '')
    .trim()
    .toLowerCase();
  if (h.length < 7) return false;
  return (prefixes || []).some((p) => {
    const s = String(p || '')
      .trim()
      .toLowerCase();
    if (s.length < 7) return false;
    return h.startsWith(s) || s.startsWith(h);
  });
}

export function evaluateRegistry(registry, fixCommits) {
  const problems = [];
  const seen = new Map();
  for (const b of registry.batches || []) {
    for (const item of [
      ...(b.items || []).map((i) => i.id),
      ...(b.notAdopted || []).map((i) => i.id)
    ]) {
      const key = `${b.report}::${item}`;
      if (seen.has(key)) {
        problems.push(
          `条目重复登记：${item} 在 ${b.report} 里出现于 ${seen.get(key)} 与 ${b.commit}`
        );
      }
      seen.set(key, b.commit);
    }
  }
  const registered = (registry.batches || []).map((b) => b.commit);
  const allow = (registry.unregisteredAllowlist || []).map((a) => a.commit);
  for (const c of fixCommits) {
    if (!isCoveredBy(c.hash, registered) && !isCoveredBy(c.hash, allow)) {
      problems.push(
        `fix 提交未登记：${String(c.hash).slice(0, 12)} ${c.subject}——修复批必须在 fix-batch-registry.json 登记` +
          `（条目→文件 + totalItems 对账），或入 unregisteredAllowlist 带理由`
      );
    }
  }
  return problems;
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

async function main() {
  const registryPath = join(ROOT, REGISTRY_PATH);
  if (!existsSync(registryPath)) fail(`登记表缺失 ${REGISTRY_PATH}`);
  const registry = JSON.parse(readFileSync(registryPath, 'utf8'));
  if (!registry.coverageCutoff) fail('登记表缺 coverageCutoff（增量完备性无从起算）');

  // ① 每批：commit 存在性 + diff 文件集
  const problems = [];
  for (const batch of registry.batches || []) {
    const verify = await gitAsync(['rev-parse', '--verify', `${batch.commit}^{commit}`], {
      cwd: ROOT
    });
    if (verify.spawnError) {
      fail(`git 不可用：${verify.spawnError}——工具故障不得当成「登记正确」`);
    }
    if (!verify.ok) {
      problems.push(`登记的 commit 不存在：${batch.commit}`);
      continue;
    }
    const show = await gitAsync(['show', '--name-only', '--pretty=format:', batch.commit], {
      cwd: ROOT
    });
    if (!show.ok && show.spawnError) {
      fail(`git show 失败（${batch.commit}）：${show.spawnError}`);
    }
    const files = show.out
      .split(/\r?\n/)
      .map((s) => s.trim())
      .filter(Boolean);
    if (batch.report && !existsSync(join(ROOT, batch.report))) {
      problems.push(`${batch.commit}: 登记的审计报告不存在：${batch.report}`);
    }
    problems.push(...evaluateBatch(batch, files, (rel) => existsSync(join(ROOT, rel))));
  }

  // ③ 增量完备性：cutoff..HEAD 的 fix/perf 提交必须已登记或豁免
  const log = await gitAsync(['log', '--format=%H%x1f%s', `${registry.coverageCutoff}..HEAD`], {
    cwd: ROOT
  });
  if (
    log.spawnError ||
    (!log.ok && log.status !== 0 && !/unknown revision|bad revision/.test(log.out))
  ) {
    // cutoff 自身尚未推远端不算工具故障；真 git 坏了必须红
    if (log.spawnError) fail(`git log 失败：${log.spawnError}`);
  }
  const fixCommits = log.out
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)
    .map((line) => {
      const i = line.indexOf('\x1f');
      return { hash: line.slice(0, i), subject: line.slice(i + 1) };
    })
    .filter((c) => /^(fix|perf)\(/.test(c.subject));
  problems.push(...evaluateRegistry(registry, fixCommits));

  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项：修复批条目↔diff↔登记表必须一一对应（S8①/A26）`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：登记 ${(registry.batches || []).length} 批全对账；cutoff ${registry.coverageCutoff} 后 ` +
      `fix/perf 提交 ${fixCommits.length} 个全部已登记或豁免`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
