#!/usr/bin/env node
/**
 * 部署前 Flyway **checksum 漂移对账**（本地跑，服务器只需导出几列）。
 *
 * 为什么要有它：`check:migration-immutable` 允许「带理由豁免」去改**已应用**的迁移，
 * 而 `deploy-prod.sh` 此前没有任何 repair 步骤 ⇒ 目标库的 `flyway_schema_history`
 * 存旧 checksum，应用启动时 `validate-on-migrate` 直接拒服（2026-11-13 dev 库实测：
 * 8 条漂移，`Migration checksum mismatch for migration version 165`，容器起不来）。
 * 新写的 `flyway-repair-precheck.mjs` 要用 JDK + 四个 jar，服务器上不具备；
 * 本脚本只要**本地 node** + 服务器一条 `psql -tAc`，且算法与 Flyway 实测对齐。
 *
 * 算法（与 Flyway 9.22 CheckSumCalculator 同构）：UTF-8 解码 → 去 BOM → 按行读
 * （readLine 丢行尾符）→ 逐行 CRC32 累加 → 结果取 int32。
 * 已用 dev/UAT 库 `flyway_schema_history` 里 **472 条真实 checksum** 反证：
 * 逐条相等（含 15 个带 BOM 的早期迁移——去 BOM 后才相等，这正是最常见的错法）。
 *
 * 用法：
 *   服务器侧（deploy-prod.sh 里做）：把 installed_rank|version|type|checksum 导成一行一条
 *   本地侧：node scripts/flyway-drift-check.mjs --applied-file <导出文件>
 *   stdin 亦可：cat applied.txt | node scripts/flyway-drift-check.mjs
 * 退出码：0 无漂移 / 1 有漂移（部署应中止）/ 2 输入或环境有问题（fail-closed，同样中止）
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[flyway-drift-check]';
const DEFAULT_DIRS = [
  'services/trade-service/src/main/resources/db/migration',
  'services/trade-service/src/main/resources/db/seed'
];

/** Flyway 的 CRC32：去 BOM、按行（丢行尾）、逐行喂 bytes，结果截成有符号 int32。 */
export function flywayChecksum(text) {
  const body = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  const lines = body.split(/\r\n|\n|\r/);
  // readLine() 不会在末尾换行后多产出一行空串
  if (lines.length && lines[lines.length - 1] === '') lines.pop();
  let crc = zlib.crc32(Buffer.alloc(0));
  for (const line of lines) crc = zlib.crc32(Buffer.from(line, 'utf8'), crc);
  return crc | 0;
}

/** 扫迁移目录，返回 version → {file, checksum, repeatable} 与脚本名索引。 */
export function scanMigrations(root, dirs) {
  const byVersion = new Map();
  const byScript = new Map();
  for (const rel of dirs) {
    const abs = join(root, rel);
    if (!existsSync(abs)) {
      // 目录缺失 = 采集面不完整，绝不能当「没有漂移」
      throw new Error(`迁移目录不存在：${rel}`);
    }
    for (const name of readdirSync(abs)) {
      if (!name.endsWith('.sql')) continue;
      const text = readFileSync(join(abs, name), 'utf8');
      const rec = { script: name, checksum: flywayChecksum(text) };
      byScript.set(name, rec);
      const v = /^V(\d+)__/.exec(name);
      if (v) byVersion.set(v[1], rec);
    }
  }
  return { byVersion, byScript };
}

/**
 * 回放 history：同一 script/version 后写的行覆盖先写的（repair 会以 type=DELETE 追写一行
 * 表示「本地已移除」），所以必须按 installed_rank 升序折叠，不能只看存在与否。
 */
export function effectiveApplied(rows) {
  const versioned = new Map();
  for (const r of rows) {
    if (!r.version) continue; // R__ 可重复迁移不参与「启动被拒」判定
    if (r.type === 'DELETE') versioned.delete(r.version);
    else versioned.set(r.version, r);
  }
  return [...versioned.values()].sort((a, b) => Number(a.version) - Number(b.version));
}

export function parseApplied(text) {
  const rows = [];
  for (const line of text.split(/\r?\n/)) {
    const s = line.trim();
    if (!s) continue;
    const parts = s.split('|');
    if (parts.length < 4)
      throw new Error(`行格式应为 installed_rank|version|type|checksum，实际：${s}`);
    const [rank, version, type, checksum] = parts.map((p) => p.trim());
    if (!/^\d+$/.test(rank)) throw new Error(`installed_rank 非数字：${s}`);
    if (checksum !== '' && !/^-?\d+$/.test(checksum)) throw new Error(`checksum 非整数：${s}`);
    rows.push({
      rank: Number(rank),
      version: version === '' || version === '\\N' ? null : version,
      type,
      checksum: checksum === '' || checksum === '\\N' ? null : Number(checksum)
    });
  }
  if (!rows.length) throw new Error('导出内容为空——空输入不等于无漂移（fail-closed）');
  return rows.sort((a, b) => a.rank - b.rank);
}

/** 纯判据：返回问题数组（空=可部署）。 */
export function evaluate(rows, scanned) {
  const problems = [];
  const applied = effectiveApplied(rows);
  for (const a of applied) {
    const local = scanned.byVersion.get(a.version);
    if (!local) {
      problems.push(
        `V${a.version} 库里已应用，但本地迁移文件缺失（${a.checksum}）—— 启动会报 applied not resolved locally`
      );
      continue;
    }
    if (a.checksum !== null && a.checksum !== local.checksum) {
      problems.push(
        `V${a.version} checksum 漂移：库里 ${a.checksum} ≠ 本地 ${local.checksum}（${local.script}）`
      );
    }
  }
  return { appliedCount: applied.length, problems };
}

function main() {
  const argv = process.argv.slice(2);
  const fileIdx = argv.indexOf('--applied-file');
  const rootIdx = argv.indexOf('--root');
  const dirIdx = argv.indexOf('--dirs');
  const readFile = () => {
    if (fileIdx >= 0 && argv[fileIdx + 1]) return readFileSync(resolve(argv[fileIdx + 1]), 'utf8');
    try {
      return readFileSync(0, 'utf8'); // stdin
    } catch {
      throw new Error('没有 --applied-file 也没有 stdin 输入');
    }
  };

  let rows, scanned;
  try {
    rows = parseApplied(readFile());
    scanned = scanMigrations(
      rootIdx >= 0 && argv[rootIdx + 1] ? resolve(argv[rootIdx + 1]) : ROOT,
      dirIdx >= 0 && argv[dirIdx + 1]
        ? argv[dirIdx + 1].split(',').map((s) => s.trim())
        : DEFAULT_DIRS
    );
  } catch (e) {
    console.error(`${TAG} 无法判定（fail-closed 中止部署）：${e.message}`);
    process.exit(2);
  }

  const { appliedCount, problems } = evaluate(rows, scanned);
  if (problems.length) {
    for (const p of problems) console.error(`${TAG} DRIFT ${p}`);
    console.error(
      `${TAG} 共 ${problems.length} 项 / 已应用版本行 ${appliedCount} 条。` +
        `正解：先对**该目标库**执行 flyway repair（见 scripts/devops/flyway-repair/README.md，` +
        `注意 locations 必须含 db/seed），确认 VALIDATE=PASS 再部署。本脚本刻意不自动 repair——repair 会掩盖漂移。`
    );
    process.exit(1);
  }
  console.log(`${TAG} OK：${appliedCount} 条已应用版本行与本地迁移逐条相等，无 checksum 漂移`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
