#!/usr/bin/env node
/**
 * 门禁（S8③，diff 级，**本地推送前专用**）：同批改了门禁判据，就必须同批动它的负向夹具。
 *
 * 背景（结构化收口档 §八）：纯文字约定「判据类改动必须新增正向用例」靠人遵守＝没约束；
 * A26（提交声称已修、文件从未被改）与「判据改了夹具没跟上」同族——都是批次范围对账缺失。
 *
 * 判据：本批（--range 提交范围 + 工作树 + 暂存区）改动集合里出现 scripts 下的 `check-*.mjs`
 * （非 .test.mjs）⇒ 同一批里必须出现它的负向夹具改动：sibling `check-*.test.mjs`，
 * 或 `scripts/gate-fixture-registry.json` fixtureMap 指向的 devops 验证脚本。
 * **exempt 豁免不免除本条**——没有夹具的门禁改判据，红就是提醒「先补第一条夹具」。
 *
 * 为什么不进 CI 聚合链：本门禁吃「推送前 origin/dev...HEAD」这个语义，只有本地 pre-push
 * 时刻才有意义（CI 里 origin/dev 已含本次推送，diff 恒空=恒绿的无用门禁）。接线=真实出现在
 * scripts/pre-push-ci-preflight.mjs 源里（check-audit-gates-wiring 规则 2 认 preflight 锚点）。
 *
 * fail-closed：不传 --range、git 不可用、range 无效、登记表缺失——一律红，绝不静默跳过。
 *
 *   node scripts/check-gate-test-coupling.mjs --range origin/dev...HEAD
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gitAsync } from './lib/async-spawn.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-gate-test-coupling]';
const REGISTRY_PATH = join('scripts', 'gate-fixture-registry.json');
const GATE_RE = /(^|\/)check-[A-Za-z0-9_.-]+\.mjs$/;

/**
 * 纯判定：改动文件集里「改了判据的门禁」是否都有同批夹具改动。
 *
 * 两类不算违规（opts）：
 *   - selfTestGate(base)=true：夹具与判据同文件共存（compose-image-tags 形态）——
 *     改判据必然连夹具一起改，diff 级对账对它无意义；
 *   - debt[base] 存在：登记在册的存量欠账（R19/R20 批的判据改动当时只有手工负向注入），
 *     显式记账放行——但全局仍校验「debt 门禁一旦有了 sibling test ⇒ debt 条目陈旧必须删」。
 * @param {string[]} changedFiles git 相对路径（自动归一 \\ → /）
 * @param {object} registry scripts/gate-fixture-registry.json 的内容
 * @param {object} [opts] { selfTestGate(base)=>bool, debt: {base: why}, testExists(name)=>bool }
 * @returns {string[]} 违规说明（空数组=合规）
 */
export function evaluateCoupling(changedFiles, registry, opts = {}) {
  const changed = new Set(changedFiles.map((f) => f.replace(/\\/g, '/')));
  const problems = [];
  const fixtureMap = registry.fixtureMap || {};
  const debt = opts.debt || {};
  const changedGates = [...changed].filter(
    (f) => /(^|\/)scripts\//.test(f) && GATE_RE.test(f) && !f.endsWith('.test.mjs')
  );
  for (const gatePath of changedGates) {
    const base = gatePath.split('/').pop();
    if (typeof opts.selfTestGate === 'function' && opts.selfTestGate(base)) continue;
    if (debt[base]) continue;
    const siblingTest = gatePath.replace(/\.mjs$/, '.test.mjs');
    const mapped = fixtureMap[base];
    const covered = changed.has(siblingTest) || (Boolean(mapped) && changed.has(mapped));
    if (!covered) {
      problems.push(
        `${gatePath}: 同批改了判据但没有同批动负向夹具（${siblingTest.split('/').pop()} 或 ` +
          `fixtureMap${mapped ? `=${mapped}` : ' 未登记'}）——判据类改动必须新增/更新正向用例（S8③）；` +
          `没有夹具的门禁改判据，先补第一条夹具`
      );
    }
  }
  // debt 卫生：欠账条目一旦补了夹具就必须销账（全局校验，不看本批改动）
  for (const [base, why] of Object.entries(debt)) {
    if (
      typeof opts.testExists === 'function' &&
      opts.testExists(base.replace(/\.mjs$/, '.test.mjs'))
    ) {
      problems.push(
        `couplingDebt 陈旧：${base} 已有 sibling .test.mjs，欠账条目必须销掉（${why}）`
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
  const args = process.argv.slice(2);
  const rangeIdx = args.indexOf('--range');
  const range = rangeIdx >= 0 ? args[rangeIdx + 1] : null;
  if (!range) {
    fail(
      '必须显式传 --range <git-range>（无范围=无判据，宁红勿跳）；' +
        '推送前标准用法：--range origin/dev...HEAD'
    );
  }
  const registryPath = join(ROOT, REGISTRY_PATH);
  if (!existsSync(registryPath)) fail(`登记表缺失 ${REGISTRY_PATH}`);
  const registry = JSON.parse(readFileSync(registryPath, 'utf8'));

  const diffs = await Promise.all([
    gitAsync(['diff', '--name-only', range], { cwd: ROOT }),
    gitAsync(['diff', '--name-only', 'HEAD'], { cwd: ROOT }),
    gitAsync(['diff', '--name-only', '--cached'], { cwd: ROOT }),
    // 🔴 未跟踪文件不算进 git diff——「同批新增的第一条夹具」恰恰常是未跟踪新文件，
    //    漏了它会把「补了夹具的本批」误判成违规（实测第一版就红在自己头上）
    gitAsync(['ls-files', '--others', '--exclude-standard'], { cwd: ROOT })
  ]);
  const labels = [`range ${range}`, '工作树 vs HEAD', '暂存区', '未跟踪新文件'];
  diffs.forEach((d, i) => {
    if (!d.ok) {
      fail(
        `git 取改动集失败（${labels[i]}）：${d.spawnError || d.out}——工具故障不得当成「没有变更」`
      );
    }
  });
  const changed = [
    ...new Set(
      diffs
        .flatMap((d) => d.out.split(/\r?\n/))
        .map((s) => s.trim())
        .filter(Boolean)
    )
  ];
  if (changed.length === 0) {
    console.log(`${TAG} OK：改动集为空（${range} 且工作树干净），无判据改动需要对账`);
    return;
  }

  // selfTest 识别与 wiring 规则 6 同款：剥注释后同时出现 selfTest( 与 --self-test
  const stripJsComments = (src) =>
    src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');
  const selfTestGate = (base) => {
    const p = join(ROOT, 'scripts', base);
    if (!existsSync(p)) return false;
    const src = stripJsComments(readFileSync(p, 'utf8'));
    return /\bselfTest\s*\(/.test(src) && src.includes('--self-test');
  };
  const debt = {};
  for (const e of registry.couplingDebt || []) debt[e.gate] = e.why;
  const debtCount = Object.keys(debt).length;
  const debtMax =
    typeof registry.couplingDebtMax === 'number' ? registry.couplingDebtMax : debtCount;
  const seedDebt = new Set(registry.couplingDebtSeed || Object.keys(debt));
  if (debtCount > debtMax) {
    fail(`couplingDebt 棘轮击穿：${debtCount} > ${debtMax}（欠账只许销不许欠新）`);
  }
  for (const base of Object.keys(debt)) {
    if (!seedDebt.has(base)) {
      fail(`couplingDebt 棘轮击穿：${base} 不在切线基线 seedDebt 里（欠账只许销不许欠新）`);
    }
  }

  const problems = evaluateCoupling(changed, registry, {
    selfTestGate,
    debt,
    testExists: (name) => existsSync(join(ROOT, 'scripts', name))
  });
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项：判据改动与夹具必须同批（S8③）`);
    process.exit(1);
  }
  const touchedGates = changed.filter(
    (f) => /(^|\/)scripts\//.test(f) && GATE_RE.test(f) && !f.endsWith('.test.mjs')
  ).length;
  console.log(
    `${TAG} OK：本批 ${changed.length} 个改动文件，其中门禁判据 ${touchedGates} 个，夹具同批对账通过`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
