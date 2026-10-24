#!/usr/bin/env node
/**
 * 门禁：本分支**新增**的 Flyway 脚本编号，必须大于基线里已发布的最大编号。
 *
 * 背景（2026-10-24 实炸级隐患，本机复现）：一批修 P0 序列漂移的迁移被编号成
 * V398/V399/V401，而 **基线里早就存在并已发布** V400/V402。仓内没设
 * `spring.flyway.out-of-order`（=false）、`validate-on-migrate` 默认 true，于是任何
 * 「已经跑过 400/402、缺 398/399/401」的库下一次启动直接拒绝起来：
 *
 *   Validate failed: Migrations have failed validation
 *   Detected resolved migration not applied to database: 398.
 *
 * 这不是推演：把当Tip的 jar 指向 `aicabinet_fix1024`（历史里有 400、402，无 398/399/401）
 * 实测 rc=1，三条号逐个点名。空库和 ≤397 的库不受影响（按序补齐），所以 CI 全绿
 * **证明不了安全**——现网只要部署过一次含 V400/V402 的构建，下一次发版就起不来。
 *
 * 为什么另外两道门拦不住：
 *   · `check-migration-immutable` 只管「基线里已有的文件不得删/改名/改内容」，
 *     且明文豁免「本分支新增的迁移不受约束」——正好是我这个形态；
 *   · `check-migration-safety` 管同一版本号两份脚本、缺评审头、MySQL 方言等，
 *     不比较「新号 vs 基线最大号」。
 *
 * 判据（基线 = MIGRATION_BASE_REF，默认 origin/dev；与同族门禁一致）：
 *   1. 基线 = `git ls-tree` 出基线提交里 migration 目录的全部 V 号，取其最大者；
 *      目录为空 / git 不可用 / 基线 ref 不存在 ⇒ **fail-closed**（枚举不到 ≠ 没有违规）；
 *   2. 变更集 = `base...HEAD` 与 `base`（工作树/暂存区）的 A/M/R 路径 ∪ 未跟踪文件，
 *      只保留「基线里不存在、文件名形如 `V<版本>__*.sql`」的**新增**迁移；
 *   3. 任一新增号 <= 基线最大号 ⇒ 红，逐条点名「新号 / 基线最大号 / 文件」；
 *   4. 豁免必须写在迁移文件自己的头里：`-- FLYWAY_OUT_OF_ORDER: <理由>`
 *      （沿用本仓「声明进工件」的写法，与 MIGRATION_REVIEWED / COMPETITOR_REF 同族；
 *      不用环境变量，因为豁免理由应该被评审者看见、也能被 git 追）。
 *      命中豁免仍会把「谁、第几号、理由」打进日志，留痕不等于沉默。
 *
 * 版本号解析按 Flyway 语义：`.` 与 `_` 都是分隔符（`V2_1__x.sql` = 2.1），逐段数值比较；
 * 解析不出来的文件名一律 fail-closed 要求人工改名或加豁免头。
 *
 * 红了怎么办（正解优先级）：
 *   A. **整组平移保序**：把本批新号整体上移到基线最大号之上（见
 *      docs/AUDIT_DECISION_BRIEF_2026-10-16.md 的 DEV-01 先例）。前提是这批号还没被
 *      任何真实库应用过——已经应用过的库会反过来命中「applied migration not resolved
 *      locally」，所以平移前必须先查各库 flyway_schema_history。
 *   B. 已经推上去（号发布出去了）：**不要改名**——已应用旧号的库会反过来命中
 *      「applied migration not resolved locally」，等于用一个隐患换另一个；改文件内容
 *      则是 checksum 漂移。`application.yml` 已常开 `spring.flyway.out-of-order: true`
 *      （2026-10-24 任务 #40，本机用风险态库实测：无任何 FLYWAY env 启动即把
 *      398/399/401 按 `[out of order]` 补跑、应用正常起来），所以已发布的倒挂不会再卡
 *      启动；**本门禁仍然要红**，因为编号单调是给人看、给 `flyway info` 看的约定。
 *      （历史提示：早期方案是「那次部署带一次性 SPRING_FLYWAY_OUT_OF_ORDER=true」，
 *      已改为入口配置常开，不再依赖每次部署记着传 env。）
 *
 * 负向验证：`node scripts/check-migration-number-order.test.mjs`（红/绿/豁免/解析四向）。
 *
 * ⚠️ 已知盲区（诚实记账）：CI 的 push 事件里 `origin/dev` 就是被推的那个提交本身
 *   ⇒ 变更集为空、本门禁在 CI 上是恒真的。它与同族 `check-migration-immutable` 一样，
 *   真正的咬合点是**推送前预检**（那时 origin/dev 还是推送前的状态）。放在聚合链里
 *   是为了「有人直接 `git push` 时也曾在 CI 上留一份记录」，别把它当主防线。
 *
 *   node scripts/check-migration-number-order.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gitAsync } from './lib/async-spawn.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-migration-number-order]';
const MIGRATION_DIR = 'services/trade-service/src/main/resources/db/migration';
const VERSIONED = /^V([\d._]+)__.+\.sql$/;
const ACK = /^\s*--+\s*FLYWAY_OUT_OF_ORDER\s*:\s*(\S.*)$/im;

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

/** 按 Flyway 语义比较版本号：`.`/`_` 都是分隔符，逐段数值比较。 */
export function compareVersions(a, b) {
  const pa = a.split(/[._]+/).filter(Boolean).map(Number);
  const pb = b.split(/[._]+/).filter(Boolean).map(Number);
  if (pa.some(Number.isNaN) || pb.some(Number.isNaN)) return NaN;
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

/**
 * 纯判定：基线已发布号 + 本分支新增迁移 ⇒ 违规清单。与 git 取数解耦，便于自测。
 * @param {string[]} baselineVersions 基线里已存在的 V 号
 * @param {{path:string, version:string, content:string}[]} newMigrations 本分支新增的迁移
 * @returns {{violations:{path:string,version:string,baseMax:string}[],acknowledged:{path:string,version:string,reason:string}[],unparsable:string[]}}
 */
export function judge(baselineVersions, newMigrations) {
  const violations = [];
  const acknowledged = [];
  const unparsable = [];
  if (!baselineVersions.length) return { violations, acknowledged, unparsable };
  let baseMax = baselineVersions[0];
  for (const v of baselineVersions) {
    if (compareVersions(v, baseMax) > 0) baseMax = v;
  }
  for (const m of newMigrations) {
    if (m.unparsable) {
      unparsable.push(m.path);
      continue;
    }
    if (compareVersions(m.version, baseMax) > 0) continue;
    const ack = m.content ? m.content.match(ACK) : null;
    if (ack) acknowledged.push({ ...m, reason: ack[1].trim() });
    else violations.push({ path: m.path, version: m.version, baseMax });
  }
  return { violations, acknowledged, unparsable };
}

// ── 以下为 CLI：取 git 事实，喂给 judge ─────────────────────────────────
// 被自测 import 时只暴露 judge/compareVersions，绝不能在这里 process.exit ——
// 那会当场杀掉测试进程，让「跑过了」变成假绿。
const isCli =
  process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isCli) await main();

async function main() {
  const candidates = [process.env.MIGRATION_BASE_REF, 'origin/dev', 'origin/main', 'dev'].filter(
    Boolean
  );
  let base = null;
  for (const ref of candidates) {
    const r = await gitAsync(['rev-parse', '--verify', '--quiet', `${ref}^{commit}`], {
      cwd: ROOT
    });
    if (r.ok) {
      base = ref;
      break;
    }
  }
  if (!base) {
    fail(
      `找不到基线 ref（试过 ${candidates.join(', ')}）——无法确定哪些迁移已经发布。` +
        '不要当成「没有倒挂」；请先 fetch 或显式设置 MIGRATION_BASE_REF。'
    );
  }

  async function git(args) {
    const r = await gitAsync(args, { cwd: ROOT });
    if (!r.ok) fail(`git ${args.join(' ')} 失败（${r.spawnError || 'non-zero exit'}）：${r.out}`);
    return (r.out || '').split(/\r?\n/).filter(Boolean);
  }

  // ① 基线已发布的号：只看基线**提交里**的目录，天然排除 target/classes 的构建副本
  const baselineFiles = await git(['ls-tree', '-r', '--name-only', base, '--', MIGRATION_DIR]);
  const baselineVersions = baselineFiles
    .map((p) => p.split('/').pop().match(VERSIONED))
    .filter(Boolean)
    .map((m) => m[1]);
  if (!baselineVersions.length) {
    fail(
      `基线 ${base} 里 ${MIGRATION_DIR} 一个 V*.sql 都没枚举到——这是取数失败，不是「没有迁移」。`
    );
  }

  // ② 变更集里「基线没有的同目录 V 文件」= 本分支新增迁移
  const baselineSet = new Set(baselineFiles);
  const changeset = new Set([
    ...(await git([
      'diff',
      '--name-only',
      '--diff-filter=AMR',
      `${base}...HEAD`,
      '--',
      MIGRATION_DIR
    ])),
    ...(await git(['diff', '--name-only', '--diff-filter=AMR', base, '--', MIGRATION_DIR])),
    ...(await git(['ls-files', '--others', '--exclude-standard', '--', MIGRATION_DIR]))
  ]);

  const newMigrations = [];
  for (const rel of changeset) {
    if (baselineSet.has(rel)) continue;
    const name = rel.split('/').pop();
    const m = name.match(VERSIONED);
    if (!m) {
      // 目录里出现「基线没有的 .sql 但文件名解析不出版本号」⇒ 判不了序，fail-closed
      if (/\.sql$/.test(name)) newMigrations.push({ path: rel, unparsable: true });
      continue;
    }
    let content = '';
    if (existsSync(join(ROOT, rel))) {
      try {
        content = readFileSync(join(ROOT, rel), 'utf8').slice(0, 8000);
      } catch {
        content = '';
      }
    } else {
      const shown = await gitAsync(['show', `HEAD:${rel}`], { cwd: ROOT });
      if (shown.ok) content = (shown.out || '').slice(0, 8000);
    }
    newMigrations.push({ path: rel, version: m[1], content });
  }

  const { violations, acknowledged, unparsable } = judge(baselineVersions, newMigrations);
  let baseMax = baselineVersions[0];
  for (const v of baselineVersions) if (compareVersions(v, baseMax) > 0) baseMax = v;

  if (unparsable.length) {
    fail(
      `以下新增迁移文件名解析不出版本号，无法判序（fail-closed）：\n  - ` +
        unparsable.join('\n  - ') +
        `\n正解：命名成 V<数字>__描述.sql，或按基线最大号 ${baseMax} 之上重排。`
    );
  }

  if (violations.length) {
    fail(
      `新增迁移编号倒挂：基线 ${base} 已发布到 V${baseMax}，而这批新迁移号 <= 它：\n  - ` +
        violations.map((v) => `${v.path}  (V${v.version} ≤ V${v.baseMax})`).join('\n  - ') +
        `\n后果：任何已经应用过 V${baseMax} 的库，在 out-of-order 关闭时会以 "Detected resolved migration not applied to database" 拒绝启动（2026-10-24 本机实测 rc=1）。` +
        `\n正解：整组平移保序到 V${baseMax} 之上（平移前先查各库 flyway_schema_history，已应用过旧号的库改名会变成「applied not resolved locally」）；` +
        `号已发布出去就不要再改名/改内容（checksum 漂移），application.yml 的 spring.flyway.out-of-order: true 会让下次启动按 [out of order] 补跑——那是兜底，不是放过倒挂的理由。`
    );
  }

  if (acknowledged.length) {
    for (const a of acknowledged) {
      console.log(
        `${TAG} 豁免留痕：${a.path} V${a.version} ≤ 基线最大 V${baseMax} —— 理由：${a.reason}`
      );
    }
  }

  console.log(
    `${TAG} OK：基线 ${base} 最大 V${baseMax}，新增迁移 ${newMigrations.length} 个，无编号倒挂`
  );
}
