#!/usr/bin/env node
/**
 * 门禁（S15）：一致性巡检规则的正负夹具——「规则写了没人验、假绿无人知」家族
 * （MA-015 误报 / MA-016 待拍板 / R22-8 两侧皆 0 假 MATCHED / GrowthLogArchive 曾
 * 100% 失败无人发现）的机制框架。
 *
 * 依据 scripts/consistency-rule-registry.json（DataConsistencyService 24 条规则全量入册）：
 *   ① **完备性**：service 里新出现的 `void checkXxx()` 必须登记——新规则无夹具即红；
 *   ② **夹具锚点**：登记条目的 fixtureClass 必须存在且源码含规则名（fixtureRefs ≥1）；
 *   ③ **双夹具棘轮**：已齐夹具（fixtureRefs ≥2，正/负两用例）的规则数只许增不减
 *     （seedFixtureOk 基线）；夹具不足的规则数只许减（maxMissingFixtures）；
 *   ④ 范式锚点：`checkOrderConsistency_consistentRowNotReturnedBySql` /
 *     `checkOrderConsistency_recordsTrueMismatch`（合法空态不报红 + 真异常报红）。
 *
 * 已知边界（R22 §8.4 拍板前置）：「两侧皆 0 是否 MATCHED」影响个别规则的合法空态
 * 定义，拍板前夹具判据只到引用计数层；语义级正负样例断言随修复批分批补。
 *
 *   node scripts/check-consistency-rule-fixtures.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-consistency-rule-fixtures]';
const REGISTRY_PATH = join('scripts', 'consistency-rule-registry.json');
const SERVICE_REL =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/DataConsistencyService.java';

export function evaluate(serviceSrc, registry, existsFn, sourceOf) {
  const problems = [];
  const liveRules = [...serviceSrc.matchAll(/void (check[A-Z]\w+)\s*\(/g)].map((m) => m[1]);
  const regMap = new Map(registry.entries.map((e) => [e.rule, e]));

  // ① 完备性：新规则不登记即红
  for (const rule of liveRules) {
    if (!regMap.has(rule)) {
      problems.push(
        `巡检规则未登记：${rule}——新规则必须入 consistency-rule-registry.json 并配正负夹具` +
          `（范式：consistentRowNotReturnedBySql/recordsTrueMismatch）`
      );
    }
  }
  // ②③ 夹具锚点与双夹具棘轮
  let fixtureOk = 0;
  for (const e of registry.entries || []) {
    if (!liveRules.includes(e.rule)) {
      problems.push(`登记陈旧：${e.rule} 已不在 DataConsistencyService（规则删除要同步登记表）`);
      continue;
    }
    if (!e.fixtureClass || !existsFn(e.fixtureClass)) {
      problems.push(`${e.rule}: fixtureClass 文件不存在：${e.fixtureClass}`);
      continue;
    }
    const refs = sourceOf(e.fixtureClass).split(e.rule + '(').length - 1;
    if (refs === 0) {
      problems.push(
        `${e.rule}: 夹具文件 ${e.fixtureClass.split('/').pop()} 不再引用该规则——夹具被删或规则改名未同步`
      );
    }
    const ok = refs >= 2;
    if (ok) fixtureOk++;
    if (ok !== e.fixtureOk) {
      problems.push(
        `夹具计数漂移：${e.rule} 现引 ${refs} 处，登记 fixtureOk=${e.fixtureOk}——` +
          (ok ? '补了夹具要同步登记表（fixtureOk=true）' : '夹具被删要回登记（fixtureOk=false）')
      );
    }
  }
  const missing = (registry.entries || []).length - fixtureOk;
  const maxMissing =
    typeof registry.maxMissingFixtures === 'number' ? registry.maxMissingFixtures : missing;
  if (missing > maxMissing) {
    problems.push(
      `夹具棘轮击穿：缺夹具规则 ${missing} > maxMissingFixtures ${maxMissing}（新规则必须带齐夹具）`
    );
  }
  const seedOk = typeof registry.seedFixtureOk === 'number' ? registry.seedFixtureOk : fixtureOk;
  if (fixtureOk < seedOk) {
    problems.push(
      `夹具退化：已齐夹具规则 ${fixtureOk} < 切线基线 ${seedOk}——有规则的夹具被删（假绿通道重开）`
    );
  }
  return { problems, stats: { rules: liveRules.length, fixtureOk, missing } };
}

function main() {
  const regPath = join(ROOT, REGISTRY_PATH);
  if (!existsSync(regPath)) fail(`登记表缺失 ${REGISTRY_PATH}`);
  const registry = JSON.parse(readFileSync(regPath, 'utf8'));
  const svcPath = join(ROOT, SERVICE_REL);
  if (!existsSync(svcPath)) fail(`巡检服务缺失 ${SERVICE_REL}`);
  const serviceSrc = readFileSync(svcPath, 'utf8');

  const { problems, stats } = evaluate(
    serviceSrc,
    registry,
    (rel) => existsSync(join(ROOT, rel)),
    (rel) => readFileSync(join(ROOT, rel), 'utf8')
  );
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S15 巡检规则夹具）`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${stats.rules} 条巡检规则全登记，双夹具齐 ${stats.fixtureOk}` +
      `（缺 ${stats.missing}/${registry.maxMissingFixtures}，只许减）`
  );
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
