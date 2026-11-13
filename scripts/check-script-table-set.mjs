#!/usr/bin/env node
/**
 * 门禁（S14 ②）：迁移/脚本的**表名声明集**——「脚本里出现的表名必须存在于声明集，
 * 已 DROP 的表出现在脚本里即红」（MA-042 家族：清库脚本仍 DELETE 已 DROP 的表，
 * 单事务首条即 `relation does not exist`，清库链必失败）。
 *
 * 形态无关性（S14 族目标：空库/已有库两种形态都成立）：
 *   ① 声明集**由迁移自证**：全量扫描 db/migration 的 V*.sql，提取 CREATE/DROP，
 *      按版本序建时间线，活表集 = CREATE − DROP（与 S7 retention-registry 同口径，
 *      并交叉对账——两处声明不一致即红，防其中一处漂）；
 *   ② 迁移脚本：DML 引用（INSERT/UPDATE/DELETE/ALTER/TRUNCATE）必须 ∈
 *      （当时存活 ∪ 本脚本自建）——同脚本自建自用合法；已 DROP 的表在后续脚本
 *      被引用即红；从未 CREATE 的表名 fail-closed；
 *   ③ 清库脚本（scripts/*.ps1|sh|sql）：DELETE/TRUNCATE 引用必须 ∈ 最终活表集——
 *      新增清库脚本自动纳入扫描（形态无关，不靠人记清单）；
 *   ④ 豁免必须写在脚本自己的头里：`-- SCRIPT_TABLE_SET_EXEMPT: <理由>`
 *      （沿用本仓「声明进工件」写法；命中豁免仍留痕，不等于沉默）。
 *
 * FROM/JOIN 引用只查「已 DROP 复用」——SQL 函数上下文（EXTRACT(YEAR FROM ts) 等）
 * 会产生同名噪声，未知 FROM 表名不罚（拼错的 SELECT 运行时 Flyway 会 fail-loud，
 * 不是本门禁的静默跳过形态）。
 *
 * 为什么不做「无 writer 即红」式全表名单硬扫：声明集由迁移自证 + 注册表对账，
 * 两处都漂才会漏（概率极低且交叉对账兜底）——登记制让每个引用点显式可见。
 *
 *   node scripts/check-script-table-set.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { compareVersions } from './check-migration-number-order.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-script-table-set]';
const MIG_DIR = join(
  'services',
  'trade-service',
  'src',
  'main',
  'resources',
  'db',
  'migration'
);
const REGISTRY_PATH = join('scripts', 'retention-registry.json');
const VERSIONED = /^V([\d._]+)__.+\.sql$/;
const ACK_RE = /^\s*--+\s*SCRIPT_TABLE_SET_EXEMPT\s*:\s*(\S.*)$/im;

const CREATE_RE = /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["']?([a-z][a-z_0-9]*)["']?/gi;
const DROP_RE = /DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?["']?([a-z][a-z_0-9]*)["']?/gi;
/** DML 硬引用：表名位置无歧义，未知即红 */
const HARD_REFS = [
  /INSERT\s+INTO\s+["']?([a-z][a-z_0-9]*)["']?/gi,
  /UPDATE\s+["']?([a-z][a-z_0-9]*)["']?\s+SET/gi,
  /DELETE\s+FROM\s+["']?([a-z][a-z_0-9]*)["']?/gi,
  /ALTER\s+TABLE\s+["']?([a-z][a-z_0-9]*)["']?/gi,
  /TRUNCATE(?:\s+TABLE)?\s+["']?([a-z][a-z_0-9]*)["']?/gi,
];
/** 软引用：只查「已 DROP 复用」，未知不罚（避开 EXTRACT/SUBSTRING 函数上下文） */
const SOFT_REFS = [/(?:FROM|JOIN)\s+["']?([a-z][a-z_0-9]*)["']?/gi];

function stripComments(sql) {
  // 未闭合的 /*（如 V157/V421 的历史遗留）视为到串尾的注释，
  // 否则非贪婪正则跨文件吞掉后续内容（2026-11-13 夹具实证）
  return sql
    .replace(/\/\*[\s\S]*?(?:\*\/|$)/g, ' ')
    .replace(/--[^\n]*/g, ' ');
}

/** 提取一张 SQL 的：created / dropped / 硬引用 / 软引用（均去注释、小写） */
export function extractSql(sql) {
  const body = stripComments(sql);
  const created = new Set();
  const dropped = new Set();
  for (const m of body.matchAll(CREATE_RE)) created.add(m[1]);
  for (const m of body.matchAll(DROP_RE)) dropped.add(m[1]);
  const hard = [];
  for (const re of HARD_REFS) for (const m of body.matchAll(re)) hard.push(m[1]);
  const soft = [];
  for (const re of SOFT_REFS) for (const m of body.matchAll(re)) soft.push(m[1]);
  return { created, dropped, hard, soft };
}

/**
 * 纯判定：迁移时间线 + 清库脚本引用 ⇒ 违规清单。
 * @param {{file:string,version:string,created:string[],dropped:string[],hard:string[],soft:string[],content?:string}[]} migrations 已按版本序
 * @param {{file:string,hard:string[],content?:string}[]} cleanupScripts
 * @param {Set<string>} liveSet 最终活表集
 * @param {string[]} registryTables retention-registry 的表名清单
 * @returns {{problems:string[],acknowledged:{file:string,reason:string}[],stats:object}}
 */
export function judge(migrations, cleanupScripts, liveSet, registryTables) {
  const problems = [];
  const acknowledged = [];

  // ③ 交叉对账：声明集（迁移自证）与 retention-registry 必须一致
  const regSet = new Set(registryTables);
  for (const t of liveSet) {
    if (!regSet.has(t)) problems.push(`活表「${t}」在迁移里存活，但 retention-registry 未登记——两处声明集漂移`);
  }
  for (const t of registryTables) {
    if (!liveSet.has(t)) problems.push(`retention-registry 登记的「${t}」在迁移时间线里不存活——两处声明集漂移`);
  }

  const createdAll = new Set();
  const droppedAll = new Set();
  // 逐表状态机：最后一次事件定生死（CREATE=活 / DROP=死），支持 DROP→重建序列
  // （集合差 created−dropped 会把「掉了又重建」的表误判为已删——2026-11-13 P3-1 复核实证）
  const state = new Map();

  for (const m of migrations) {
    for (const t of m.created) createdAll.add(t);
    for (const t of m.dropped) droppedAll.add(t);

    const aliveBefore = [...state.entries()]
      .filter(([, v]) => v === 'alive')
      .map(([k]) => k);
    const valid = new Set([...aliveBefore, ...m.created]);

    const checkRef = (name, ctx, soft) => {
      if (valid.has(name)) return;
      const ack = m.content ? m.content.match(ACK_RE) : null;
      if (ack) {
        acknowledged.push({ file: m.file, reason: ack[1].trim() });
        return;
      }
      if (state.get(name) === 'dead' || droppedAll.has(name)) {
        problems.push(
          `${m.file}：${ctx}引用已 DROP 的表「${name}」——MA-042 家族：已删表不得再出现在脚本（清库链首条即 relation does not exist）`
        );
      } else if (!createdAll.has(name)) {
        problems.push(`${m.file}：${ctx}引用未知表「${name}」——声明集里不存在（从未 CREATE，fail-closed）`);
      } else {
        problems.push(`${m.file}：${ctx}引用表「${name}」——该表在本脚本之前已被 DROP`);
      }
    };

    for (const name of m.hard) checkRef(name, 'DML', false);
    for (const name of m.soft) {
      // 软引用只查「已 DROP 复用」；未知/存活不罚
      if (valid.has(name)) continue;
      if (state.get(name) === 'dead' || droppedAll.has(name)) checkRef(name, 'FROM/JOIN', true);
    }

    // 本文件事件定终态：DROP 覆盖同文件 CREATE（保守，DROP TABLE 后表不存在）
    for (const t of m.created) state.set(t, 'alive');
    for (const t of m.dropped) state.set(t, 'dead');
  }

  // ② 清库脚本：引用必须 ∈ 最终活表集
  for (const c of cleanupScripts) {
    const check = (name) => {
      if (liveSet.has(name)) return;
      const ack = c.content ? c.content.match(ACK_RE) : null;
      if (ack) {
        acknowledged.push({ file: c.file, reason: ack[1].trim() });
        return;
      }
      if (droppedAll.has(name)) {
        problems.push(
          `${c.file}：清库脚本引用已 DROP 的表「${name}」——MA-042 家族（单事务首条即 relation does not exist）`
        );
      } else {
        problems.push(`${c.file}：清库脚本引用未知表「${name}」——不在活表声明集（fail-closed）`);
      }
    };
    for (const name of c.hard) check(name);
  }

  return {
    problems,
    acknowledged,
    stats: {
      migrations: migrations.length,
      cleanupScripts: cleanupScripts.length,
      live: liveSet.size,
      registry: registryTables.length,
    },
  };
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function main() {
  // ① 迁移：解析 + 版本排序
  const migDir = join(ROOT, MIG_DIR);
  if (!existsSync(migDir)) fail(`迁移目录缺失 ${MIG_DIR}`);
  const files = readdirSync(migDir).filter((f) => VERSIONED.test(f));
  if (files.length === 0) fail('迁移目录扫空（判据退化=假绿）');

  const migrations = [];
  for (const f of files) {
    const m = f.match(VERSIONED);
    const sql = readFileSync(join(migDir, f), 'utf8');
    const { created, dropped, hard, soft } = extractSql(sql);
    migrations.push({
      file: `${MIG_DIR}/${f}`.replace(/\\/g, '/'),
      version: m[1],
      created: [...created],
      dropped: [...dropped],
      hard,
      soft,
      content: sql,
    });
  }
  migrations.sort((a, b) => compareVersions(a.version, b.version));

  const state = new Map();
  for (const m of migrations) {
    for (const t of m.created) state.set(t, 'alive');
    for (const t of m.dropped) state.set(t, 'dead');
  }
  const liveSet = new Set(
    [...state.entries()].filter(([, v]) => v === 'alive').map(([k]) => k)
  );
  if (liveSet.size === 0) fail('活表提取数=0（提取器坏了，零命中=假绿）');

  // ② 清库脚本：scripts/ 下的 ps1/sh/sql
  const scriptsDir = join(ROOT, 'scripts');
  const cleanupScripts = [];
  for (const f of readdirSync(scriptsDir)) {
    if (!/\.(ps1|sh|sql)$/i.test(f)) continue;
    const sql = readFileSync(join(scriptsDir, f), 'utf8');
    const { hard } = extractSql(sql);
    if (hard.length === 0) continue; // 不碰表的脚本不入扫
    cleanupScripts.push({ file: `scripts/${f}`, hard, content: sql });
  }

  // 注册表对账
  const regPath = join(ROOT, REGISTRY_PATH);
  if (!existsSync(regPath)) fail(`注册表缺失 ${REGISTRY_PATH}`);
  const registry = JSON.parse(readFileSync(regPath, 'utf8'));
  const registryTables = (registry.tables || []).map((e) => e.table);

  const { problems, acknowledged, stats } = judge(migrations, cleanupScripts, liveSet, registryTables);

  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S14 迁移/脚本表名声明集）`);
    process.exit(1);
  }
  for (const a of acknowledged) {
    console.log(`${TAG} 豁免留痕：${a.file} —— 理由：${a.reason}`);
  }
  console.log(
    `${TAG} OK：迁移 ${stats.migrations} 个 + 清库脚本 ${stats.cleanupScripts} 个，表名引用全部命中声明集` +
      `（活表 ${stats.live} 张，与 retention-registry ${stats.registry} 对账一致；S14 ② 表名声明集）`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
