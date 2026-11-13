#!/usr/bin/env node
/**
 * 门禁（S7）：保留注册表完备性——「新长一张表没人管」这一族（R19-A1 同族 / R22-9 遥测
 * 55,287 行无人清理）的结构化控制。
 *
 * 依据 scripts/retention-registry.json（165 张活表全量入册），判据全部 fail-closed：
 *   ① **完备性**：迁移里 CREATE TABLE 且未被后续 DROP 的每张活表，必须已在注册表——
 *     新表不登记即红；
 *   ② **无幽灵**：注册表里的表必须仍活着（被 DROP 的表留在册=陈旧红）；
 *   ③ **策略必须真实实现**：policy=days/months 的条目，其 config 键必须在
 *     GrowthLogArchiveScheduler 源码中真实出现（声明了保留策略却没人执行=假账）；
 *   ④ **TBD 棘轮**：待业务确认的条目数 ≤ maxTbd（只许减不许增——新表要么给策略要么
 *     证明入 TBD 合理并同步抬 maxTbd，两处 diff 评审可见）；
 *   ⑤ **S5 交叉**：资金语义表（FUNDS_LEDGER_TABLES 点名 + 后缀匹配）必须 RETAIN——
 *     保留注册表与删除保护两道闸不许打架。
 *
 *   node scripts/check-retention-registry.mjs
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { compareVersions } from './check-migration-number-order.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-retention-registry]';
const REGISTRY_PATH = join('scripts', 'retention-registry.json');
const MIG_DIR = join('services', 'trade-service', 'src', 'main', 'resources', 'db', 'migration');
const SCHEDULER_REL =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/GrowthLogArchiveScheduler.java';
const ADMIN_DATA_REL =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/AdminDataManageService.java';

const CREATE_RE = /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["']?([a-z_0-9]+)["']?/gi;
const DROP_RE = /DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?["']?([a-z_0-9]+)["']?/gi;
const FUNDS_SUFFIXES = ['_ledger', '_bill', '_split', '_clawback', '_points_log', '_operation'];

/** 迁移 → {live:[], dropped:[]}（与注册表生成器同口径）。
 * 剥注释后提取：迁移头部的「-- ROLLBACK: DROP TABLE x」是回滚说明，不是真实语句，
 * 裸正则曾把它们当 DROP ⇒ 7 张真实活表被误判已删（S14 同源确认轮发现，2026-11-13 修复）。
 * 未闭合的 /*（V157/V421 历史遗留）视为到串尾的注释，防跨文件吞内容。 */
export function extractTables(sql) {
  const body = sql
    .replace(/\/\*[\s\S]*?(?:\*\/|$)/g, ' ')
    .replace(/--[^\n]*/g, ' ');
  const created = new Set();
  const dropped = new Set();
  for (const m of body.matchAll(CREATE_RE)) created.add(m[1].toLowerCase());
  for (const m of body.matchAll(DROP_RE)) dropped.add(m[1].toLowerCase());
  return {
    created: [...created],
    dropped: [...dropped],
    live: [...created].filter((t) => !dropped.has(t))
  };
}

/**
 * 纯判定。
 * @param {{live:string[], dropped:string[]}} migrationState
 * @param {{tables:[{table,policy,value?,config?,why}], maxTbd:number}} registry
 * @param {string} schedulerSrc 剥注释后的 GrowthLogArchiveScheduler 源码
 * @param {{fundsNamed:string[]}} fundsInfo S5 的 FUNDS_LEDGER_TABLES
 */
export function evaluate(migrationState, registry, schedulerSrc, fundsNamed) {
  const problems = [];
  const regMap = new Map((registry.tables || []).map((e) => [e.table, e]));
  const liveSet = new Set(migrationState.live);

  // ① 完备性：新表不登记即红
  for (const t of migrationState.live) {
    if (!regMap.has(t)) {
      problems.push(
        `活表未登记：${t}（迁移里有 CREATE TABLE，注册表没有）——新表必须在 retention-registry.json 给保留策略或显式 RETAIN`
      );
    }
  }
  // ② 无幽灵：被 DROP 的表留在册=陈旧
  for (const t of registry.tables || []) {
    if (migrationState.dropped.includes(t.table)) {
      problems.push(`注册表陈旧：${t.table} 已被迁移 DROP，条目应删除`);
    }
  }
  // ③ 策略必须真实实现
  for (const e of registry.tables || []) {
    if (e.policy === 'days' || e.policy === 'months') {
      if (!e.config) {
        problems.push(`${e.table}: policy=${e.policy} 但未声明 config 键（清理由谁执行？）`);
      } else if (!schedulerSrc.includes(e.config)) {
        problems.push(
          `${e.table}: 声明保留策略 config=${e.config}，但 GrowthLogArchiveScheduler 源码中无此键——策略未实现=假账`
        );
      }
    }
  }
  // ④ TBD 棘轮
  const tbd = (registry.tables || []).filter((e) => e.policy === 'TBD');
  const maxTbd = typeof registry.maxTbd === 'number' ? registry.maxTbd : tbd.length;
  if (tbd.length > maxTbd) {
    problems.push(
      `TBD 棘轮击穿：待确认条目 ${tbd.length} > maxTbd ${maxTbd}（新表必须给策略；确需 TBD 先改 maxTbd 并评审）`
    );
  }
  // ⑤ S5 交叉：资金语义表必须 RETAIN
  for (const t of migrationState.live) {
    if (fundsNamed.includes(t) || FUNDS_SUFFIXES.some((s) => t.endsWith(s))) {
      const e = regMap.get(t);
      if (e && e.policy !== 'RETAIN') {
        problems.push(
          `${t}: 资金语义表 policy=${e.policy} ≠ RETAIN——与 S5 删除保护冲突（资金凭证不可清理）`
        );
      }
    }
    for (const f of fundsNamed) {
      if (t === f) {
        const e = regMap.get(t);
        if (!e) problems.push(`S5 点名资金表未登记：${t}`);
      }
    }
  }
  return {
    problems,
    stats: { live: migrationState.live.length, registered: regMap.size, tbd: tbd.length }
  };
}

function main() {
  const regPath = join(ROOT, REGISTRY_PATH);
  if (!existsSync(regPath)) fail(`注册表缺失 ${REGISTRY_PATH}`);
  const registry = JSON.parse(readFileSync(regPath, 'utf8'));
  const migDir = join(ROOT, MIG_DIR);
  if (!existsSync(migDir)) fail(`迁移目录缺失 ${MIG_DIR}`);

  const files = readdirSync(migDir)
    .filter((f) => /^V\d+__.*\.sql$/.test(f))
    .sort((a, b) => compareVersions(a.match(/^V([\d._]+)__/)[1], b.match(/^V([\d._]+)__/)[1]));
  if (files.length === 0) fail('迁移目录扫空（判据退化=假绿）');
  // 逐表状态机（按版本序）：DROP→重建序列的终态由最后一次事件决定——
  // 集合差 created−dropped 会把「掉了又重建」的表误判为已删（2026-11-13 P3-1 复核实证）
  const stateMap = new Map();
  for (const f of files) {
    const s = extractTables(readFileSync(join(migDir, f), 'utf8'));
    for (const t of s.created) stateMap.set(t, 'alive');
    for (const t of s.dropped) stateMap.set(t, 'dead');
  }
  const state = {
    created: [...stateMap.keys()],
    dropped: [...stateMap.entries()].filter(([, v]) => v === 'dead').map(([k]) => k),
    live: [...stateMap.entries()].filter(([, v]) => v === 'alive').map(([k]) => k).sort(),
  };
  if (state.live.length === 0) fail('活表提取数=0（提取器坏了，零命中=假绿）');

  const seed = typeof registry.seedLiveTableCount === 'number' ? registry.seedLiveTableCount : 0;
  if (state.live.length < seed) {
    fail(
      `活表 ${state.live.length} < 切线基线 ${seed}（表只增不减；确需清理 DROP 后同步删注册表条目并降 seed）`
    );
  }

  const schedulerSrc = readFileSync(join(ROOT, SCHEDULER_REL), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');
  const adminSrc = readFileSync(join(ROOT, ADMIN_DATA_REL), 'utf8');
  const fundsM = adminSrc.match(/FUNDS_LEDGER_TABLES\s*=\s*Set\.of\(([^)]*)\)/);
  const fundsNamed = fundsM
    ? fundsM[1]
        .split(',')
        .map((s) => (s.match(/"([^"]+)"/) || [])[1])
        .filter(Boolean)
    : [];

  const { problems, stats } = evaluate(state, registry, schedulerSrc, fundsNamed);
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S7 保留注册表完备性）`);
    process.exit(1);
  }
  const retainCount = (registry.tables || []).filter((e) => e.policy === 'RETAIN').length;
  const implementedCount = (registry.tables || []).filter(
    (e) => e.policy === 'days' || e.policy === 'months'
  ).length;
  console.log(
    `${TAG} OK：${stats.live} 张活表全登记（资金交叉校验 ${fundsNamed.length} 表通过）——` +
      `RETAIN ${retainCount} / 已实现清理 ${implementedCount} / TBD ${stats.tbd}/${maxTbdOf(registry)}`
  );
}

function maxTbdOf(registry) {
  return typeof registry.maxTbd === 'number'
    ? registry.maxTbd
    : (registry.tables || []).filter((e) => e.policy === 'TBD').length;
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
