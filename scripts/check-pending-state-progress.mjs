#!/usr/bin/env node
/**
 * 门禁（S2）：非终态注册表——「有中间态、没人收口」这一族（R18-1/R20-1/R20-8/R22-1/R22-6
 * 五次资金/状态类 P1/P2 共同的形状）的结构化控制。
 *
 * 判据（依据 scripts/pending-state-registry.json，全部锚点 fail-closed）：
 *   ① 每条状态线的 host 文件必须真实存在；
 *   ② 每个推进器：类文件存在 + anchor 方法名在**剥注释后**的源码里可寻（`\banchor\s*\(`）；
 *      alert code（如登记）必须以字符串字面量形态存在于 services 主源码任意处；
 *   ③ **枚举完备性**：enum 形态状态线（SessionState 等）的每个常量，必须被
 *      terminal / advancers.from / advancers.to / gaps[].state / uncertain[].state 覆盖——
 *      新加一个状态常量不登记即红；
 *   ④ **宿主完备性**：domain/ 下含 `String *status|state|stage|outcome*` 字段的实体类，
 *      必须登记为 stateLine 或 nonLifecycle（新长一条状态线不登记即红）——
 *      宿主是有限集、机械可枚举，这正是「不做散落字面量全扫」的中间形态；
 *   ⑤ 棘轮：stateLines 宿主数不许低于 seedStateLineCount；gaps 销账后允许减少。
 *
 * gaps/uncertain 是显式记账（缺口=债务），不阻塞门禁——登记本身即 S2 的价值：
 * 「每轮都在重新发现同一族漏网」变成一张可审计的欠条。
 *
 *   node scripts/check-pending-state-progress.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-pending-state-progress]';
const REGISTRY_PATH = join('scripts', 'pending-state-registry.json');

export const stripComments = (src) =>
  src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');

function walkJava(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (['node_modules', 'target', 'static'].includes(name)) continue;
      walkJava(full, out);
    } else if (name.endsWith('.java')) out.push(full);
  }
  return out;
}

/**
 * 枚举常量抽取（剥注释后）：enum 声明体到第一个「深度 0 的 `;`」之间的顶层大写标识符。
 * 本仓 5 个业务状态 enum 常量均为无参形态（NAME, / NAME;），带参常量是已知边界。
 */
export function extractEnumConstants(maskedSrc, enumName) {
  const decl = maskedSrc.indexOf(`enum ${enumName}`);
  if (decl < 0) return null;
  const bodyStart = maskedSrc.indexOf('{', decl);
  if (bodyStart < 0) return null;
  let depth = 0;
  let end = -1;
  for (let i = bodyStart; i < maskedSrc.length; i++) {
    const c = maskedSrc[i];
    if (c === '(' || c === '{') depth++;
    else if (c === ')' || c === '}') {
      depth--;
      if (c === '}' && depth === 0) {
        end = i;
        break;
      }
    } else if (c === ';' && depth === 0) {
      end = i;
      break;
    }
  }
  if (end < 0) return null;
  const header = maskedSrc.slice(bodyStart + 1, end);
  return header
    .split(',')
    .map((s) => (s.match(/^\s*([A-Z][A-Z0-9_]*)\s*(\(|$|\s)/) || [])[1])
    .filter(Boolean);
}

/** 纯判定：单条状态线的锚点与枚举完备性。servicesIndex=全仓剥注释源码拼接（alert 字面量寻址）。 */
export function evaluateStateLine(line, servicesIndex) {
  const problems = [];
  if (!existsSync(join(ROOT, line.host))) {
    problems.push(`${line.entity}: host 文件不存在：${line.host}`);
    return problems;
  }
  const covered = new Set([
    ...(line.terminal || []),
    ...(line.gaps || []).map((g) => g.state),
    ...(line.uncertain || []).map((u) => u.state)
  ]);
  for (const adv of line.advancers || []) {
    if (!existsSync(join(ROOT, adv.class))) {
      problems.push(`${line.entity}: 推进器类不存在：${adv.class}`);
      continue;
    }
    const masked = stripComments(readFileSync(join(ROOT, adv.class), 'utf8'));
    if (!new RegExp(`\\b${adv.anchor}\\s*\\(`).test(masked)) {
      problems.push(
        `${line.entity}: 推进器锚点不可寻：${adv.class} 里找不到 ${adv.anchor}(（剥注释后）——方法改名要同步登记表`
      );
    }
    if (adv.alert && !servicesIndex.includes(`"${adv.alert}"`)) {
      problems.push(
        `${line.entity}: 告警 code 字面量全仓不可寻："${adv.alert}"（登记的兜底告警必须真实存在于源码）`
      );
    }
    for (const f of adv.from || []) covered.add(f);
    if (adv.to) covered.add(adv.to);
  }
  if (line.enum) {
    const constants = extractEnumConstants(
      stripComments(readFileSync(join(ROOT, line.host), 'utf8')),
      line.enum
    );
    if (constants == null) {
      problems.push(`${line.entity}: 枚举 ${line.enum} 常量抽取失败（声明形态变化？）`);
    } else {
      for (const c of constants) {
        if (!covered.has(c)) {
          problems.push(
            `${line.entity}: 枚举常量 ${c} 未登记（terminal/advancers/gaps/uncertain 都没有它）——` +
              `新状态不登记即红（S2 本体）`
          );
        }
      }
    }
  }
  const seen = new Set();
  for (const g of line.gaps || []) {
    const key = `${line.entity}::${g.state}`;
    if (seen.has(key)) problems.push(`${line.entity}: gaps 重复登记 ${g.state}`);
    seen.add(key);
  }
  return problems;
}

/** 纯判定：宿主完备性——domain 实体的 String 状态字段 ⇒ 必须登记。 */
export function evaluateHostCoverage(domainFiles, registry) {
  const registered = new Set([
    ...(registry.stateLines || []).map((l) => l.entity),
    ...(registry.nonLifecycle || []).map((l) => l.entity)
  ]);
  const problems = [];
  const fieldRe =
    /\b(?:private|protected)\s+String\s+(\w*(?:status|state|stage|outcome)\w*)\s*[;=]/i;
  const classRe = /public\s+(?:final\s+|abstract\s+)?class\s+(\w+)/;
  for (const rel of domainFiles) {
    const masked = stripComments(readFileSync(join(ROOT, rel), 'utf8'));
    if (!fieldRe.test(masked)) continue;
    const cls = (masked.match(classRe) || [])[1];
    if (!cls) continue;
    if (!registered.has(cls)) {
      problems.push(
        `状态线宿主未登记：${rel}（${cls}）含 String 状态字段——新增状态线必须在 ` +
          `pending-state-registry.json 登记 stateLine，或以非生命周期理由入 nonLifecycle`
      );
    }
  }
  return problems;
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function main() {
  const registryPath = join(ROOT, REGISTRY_PATH);
  if (!existsSync(registryPath)) fail(`登记表缺失 ${REGISTRY_PATH}`);
  const registry = JSON.parse(readFileSync(registryPath, 'utf8'));
  const stateLines = registry.stateLines || [];
  if (!Array.isArray(stateLines) || stateLines.length === 0) {
    fail('stateLines 为空（登记表失效，零命中=假绿）');
  }
  const seed = typeof registry.seedStateLineCount === 'number' ? registry.seedStateLineCount : 0;
  if (stateLines.length < seed) {
    fail(
      `stateLines ${stateLines.length} < 切线基线 ${seed}（宿主只许增不许减；确需合并先改 seed 并留痕）`
    );
  }

  const servicesDir = join(ROOT, 'services');
  const allJava = walkJava(servicesDir).filter((p) => /src[\\/]main[\\/]java/.test(p));
  if (allJava.length === 0) fail('services 主源码扫空（判据退化=假绿）');
  const servicesIndex = allJava.map((p) => stripComments(readFileSync(p, 'utf8'))).join('\n');

  const problems = [];
  for (const line of stateLines) problems.push(...evaluateStateLine(line, servicesIndex));

  const domainFiles = walkJava(servicesDir)
    .filter((p) => /(^|[\\/])domain[\\/]/.test(p))
    .map((p) => relative(ROOT, p).replace(/\\/g, '/'));
  problems.push(...evaluateHostCoverage(domainFiles, registry));

  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S2 非终态注册表）`);
    process.exit(1);
  }
  const gapCount = stateLines.reduce((n, l) => n + (l.gaps || []).length, 0);
  const uncertainCount = stateLines.reduce((n, l) => n + (l.uncertain || []).length, 0);
  console.log(
    `${TAG} OK：${stateLines.length} 条状态线全对账（推进器锚点/告警字面量/枚举完备性/宿主完备性），` +
      `显式缺口 ${gapCount} 条 + 存疑 ${uncertainCount} 条在册；nonLifecycle ${`(${(registry.nonLifecycle || []).length})`} 豁免`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
