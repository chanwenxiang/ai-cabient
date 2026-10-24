/**
 * 防绕过自测：`check-migration-number-order` 必须**会红**（编号倒挂）且**不假红**（正常递增）。
 * Run: node scripts/check-migration-number-order.test.mjs
 *
 * 为什么必须有这个文件：本门禁的判据是「基线最大号 vs 本分支新增号」的数值比较，
 * 比较函数一旦写错方向（或版本号解析正则退化成只认纯数字），门禁会**静默放行**倒挂——
 * 而它自己看不出来，代价是现网下次启动直接起不来（2026-10-24 实测形态）。
 *
 * 🔴 与同族 `check-mysql-isms-in-migrations.test.mjs` 的关键区别：那些测试往**真实
 * Flyway 目录**写 `V9999__tmp_*`（号段在顶端，残留最多多一个不执行的脚本）。本门禁的
 * 红路径需要的偏偏是**低号**文件——往真实目录写一个 `V398__tmp_*.sql`，一旦被残留并
 * 被 `git add -A` 带上，就是一次真的编号倒挂注入。所以这里全程在 `.tmp` 里
 * **另建一个临时 git 仓库**跑真实 git 取数（diff / ls-tree / ls-files 三条腿都真跑），
 * 并断言真实树的迁移目录**逐字节不变**。
 */
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runAsync } from './lib/async-spawn.mjs';
import { compareVersions, judge } from './check-migration-number-order.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-migration-number-order.test]';
const GATE = 'scripts/check-migration-number-order.mjs';
const MIG = 'services/trade-service/src/main/resources/db/migration';
const SANDBOX = join(ROOT, '.tmp', `gate-selftest-migorder-${process.pid}`);

let failures = 0;
function check(name, cond, extra = '') {
  if (cond) {
    console.log(`  ok   ${name}`);
  } else {
    failures++;
    console.error(`  RED  ${name}${extra ? ` —— ${extra}` : ''}`);
  }
}

// ── 纯函数：版本比较与判定表（不碰 git） ────────────────────────────────
check('compareVersions 398 < 400', compareVersions('398', '400') < 0);
check('compareVersions 403 > 402', compareVersions('403', '402') > 0);
check('compareVersions 2.1 > 2.0.9', compareVersions('2.1', '2.0.9') > 0);
check('compareVersions 下划线=点分（2_1 ≡ 2.1）', compareVersions('2_1', '2.1') === 0);
check('同版本判 0（同版本即倒挂，不放行）', compareVersions('402', '402') === 0);

{
  const r = judge(['400', '402'], [{ path: 'V398__x.sql', version: '398', content: '-- plain' }]);
  check('judge 抓出 398 ≤ 402', r.violations.length === 1 && r.violations[0].baseMax === '402');
}
{
  const r = judge(['402'], [{ path: 'V403__x.sql', version: '403', content: '' }]);
  check('judge 放行 403 > 402（不假红）', r.violations.length === 0);
}
{
  const r = judge(['402'], [{ path: 'V399__x.sql', version: '399', content: '' }]);
  check('judge 判同目录重复号（等于基线最大号也算倒挂）', r.violations.length === 1);
}
{
  const r = judge(['402'], [{ path: 'V398__x.sql', version: '398', unparsable: true }]);
  check('judge 把解析不出版本的文件计为 unparsable（fail-closed）', r.unparsable.length === 1);
}

// ── 端到端：临时 git 仓库，真实跑三条取数腿 ─────────────────────────────
const HEADERS = {
  plain: '-- MIGRATION_KIND: schema\nSELECT 1;\n',
  ack: '-- FLYWAY_OUT_OF_ORDER: 号已发布过，改不动；受影响那次部署带一次性 env\nSELECT 1;\n'
};

async function sh(args, cwd = SANDBOX) {
  const r = await runAsync('git', args, { cwd });
  if (r.status !== 0) throw new Error(`git ${args.join(' ')} 失败：${r.stderr || r.stdout}`);
  return r.stdout;
}

function put(rel, body) {
  const abs = join(SANDBOX, MIG, rel);
  mkdirSync(dirname(abs), { recursive: true });
  writeFileSync(abs, body, 'utf8');
}

async function freshRepo() {
  rmSync(SANDBOX, { recursive: true, force: true });
  mkdirSync(join(SANDBOX, 'scripts', 'lib'), { recursive: true });
  // 门禁按自身文件位置定位 ROOT ⇒ 把脚本与它依赖的 lib 拷进沙箱，判据一行不改
  cpSync(join(ROOT, GATE), join(SANDBOX, GATE));
  cpSync(join(ROOT, 'scripts/lib/async-spawn.mjs'), join(SANDBOX, 'scripts/lib/async-spawn.mjs'));
  await sh(['init', '-q', '-b', 'main']);
  await sh(['config', 'user.email', 'selftest@example.invalid']);
  await sh(['config', 'user.name', 'selftest']);
  put('V400__base_a.sql', HEADERS.plain);
  put('V402__base_b.sql', HEADERS.plain);
  await sh(['add', '-A']);
  await sh(['commit', '-q', '-m', 'baseline: V400/V402 已发布']);
}

async function commit(name) {
  await sh(['add', '-A']);
  await sh(['commit', '-q', '-m', name]);
}

async function runGate(env = {}) {
  return runAsync(process.execPath, [GATE], { cwd: SANDBOX, env: { ...env } });
}

const realMigrationDirBefore = readdirSync(join(ROOT, MIG)).sort().join('|');

try {
  // ① 已提交的低号 ⇒ 必须红
  await freshRepo();
  put('V398__late.sql', HEADERS.plain);
  await commit('add V398 倒挂');
  let r = await runGate({ MIGRATION_BASE_REF: 'HEAD~1' });
  check('红：已提交 V398（基线最大 402）必须 rc=1', r.status === 1, `实际 rc=${r.status}`);
  check(
    '红：点名新号与基线最大号',
    /V398/.test(r.stderr) && /≤ V402/.test(r.stderr),
    (r.stderr || r.stdout).slice(0, 200)
  );
  check(
    '红：给出后果与正解（不许只说「不行」）',
    /not applied to database/.test(r.stderr) && /spring\.flyway\.out-of-order/.test(r.stderr)
  );

  // ② 未提交（工作树）的低号 ⇒ 也要红（推送前预检看的就是这个状态）
  await freshRepo();
  put('V397__uncommitted.sql', HEADERS.plain);
  r = await runGate({ MIGRATION_BASE_REF: 'HEAD' });
  check(
    '红：未跟踪的 V397 也算（ls-files --others 这条腿真接线）',
    r.status === 1,
    `rc=${r.status}`
  );

  // ③ 正常递增 ⇒ 必须绿
  await freshRepo();
  put('V403__next.sql', HEADERS.plain);
  await commit('add V403');
  r = await runGate({ MIGRATION_BASE_REF: 'HEAD~1' });
  check('绿：V403 > 基线最大 402 必须 rc=0', r.status === 0, (r.stderr || '').slice(0, 200));

  // ④ 豁免头 ⇒ 绿且留痕
  await freshRepo();
  put('V399__ack.sql', HEADERS.ack);
  await commit('add V399 with ack');
  r = await runGate({ MIGRATION_BASE_REF: 'HEAD~1' });
  check('绿：带 FLYWAY_OUT_OF_ORDER 头的低号放行', r.status === 0, (r.stderr || '').slice(0, 200));
  check('留痕：豁免仍然逐条打日志（放行≠沉默）', /豁免留痕/.test(r.stdout));

  // ⑤ 基线 ref 取不到 ⇒ fail-closed，绝不等于「没有违规」
  await freshRepo();
  put('V403__next.sql', HEADERS.plain);
  r = await runGate({ MIGRATION_BASE_REF: 'no-such-ref-xyz' });
  check('红：基线 ref 不存在一律 fail-closed', r.status === 1 && /找不到基线/.test(r.stderr));

  // ⑥ 只认 src 目录：构建副本里的低号不该被判（基线 ls-tree 也只取 src 目录）
  await freshRepo();
  const buildCopy = join(SANDBOX, 'services/trade-service/target/classes/db/migration');
  mkdirSync(buildCopy, { recursive: true });
  writeFileSync(join(buildCopy, 'V398__copy.sql'), HEADERS.plain, 'utf8');
  await commit('build 副本里的低号不该被判');
  r = await runGate({ MIGRATION_BASE_REF: 'HEAD~1' });
  check('绿：target/classes 的构建副本不参与判序', r.status === 0, (r.stderr || '').slice(0, 200));
} catch (err) {
  failures++;
  console.error(`  RED  自测脚手架抛异常：${err && err.message}`);
} finally {
  // 清扫：删不掉必须显式失败（沙箱里躺着一份门禁脚本副本，不影响真实树）
  let removed = false;
  for (let i = 0; i < 20 && !removed; i++) {
    try {
      rmSync(SANDBOX, { recursive: true, force: true });
      removed = !existsSync(SANDBOX);
    } catch {
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 150);
    }
  }
  if (!removed) {
    failures++;
    console.error(`  RED  沙箱未清干净，请手工删除 ${SANDBOX}`);
  }
}

// 真实树迁移目录必须一个字节都没被动过
const realMigrationDirAfter = readdirSync(join(ROOT, MIG)).sort().join('|');
check(
  '真实 Flyway 目录零改动（本测试不往真目录写低号文件）',
  realMigrationDirBefore === realMigrationDirAfter,
  `before=${realMigrationDirBefore.length} after=${realMigrationDirAfter.length}`
);

if (failures) {
  console.error(`${TAG} RED：${failures} 条断言失败`);
  process.exit(1);
}
console.log(`${TAG} OK：红/绿/豁免/fail-closed/取数边界全部按预期`);
