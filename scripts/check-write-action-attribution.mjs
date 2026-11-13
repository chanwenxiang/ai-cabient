#!/usr/bin/env node
/**
 * 门禁（S9）：写动作归因三必达的**在位性防线**——「运营写端点整体零留痕」家族
 * （MA-082 营销域 15 端点 / MA-115 OTA / MA-116 一致性修复）的结构化控制。
 *
 * 本仓架构：admin 审计在 controller 或其注入 service 里调 `appendLog`。判定
 * = 控制器自身或注入依赖（一层可达）是否含 `appendLog(`，全量 98 个含写端点的
 * 控制器以 [write-attribution-registry.json](scripts/write-attribution-registry.json) 登记：
 *   ① **可达不退化**：登记时 auditCapable=true 的控制器（62 个），现在仍必须可达——
 *     注入的 service 被人拿掉 appendLog 即红（MA-082 家族复发）；
 *   ② **不可达必登记**：新出现的零归因写控制器必须入册（新端点无审计=红）；
 *   ③ **分类棘轮**：非可达文件必须带分类——CALLBACK / DEV_ONLY / DEVICE_FACING 为
 *     语义豁免（带理由），TBD=真缺口显式欠账（maxTbd 只许减）；
 *   ④ 已知盲区：二层可达（service→service2 的 appendLog 调用）判不到，方向是安全侧
 *     （漏判成不可达 ⇒ 多要求登记，不会放水）。夹具第 5 组里有一条断言专门钉住这个局限，
 *     别把它读成「委托链已覆盖」。
 *   ⑤ **2026-11-13 订正**：本文件此前在 ④ 里写着「误判为可达不存在（可达要求显式命中）」——
 *     **这句是错的**，实测被推翻：旧证据式 `/appendLog\s*\(/` 匹配**定义点**，而 1-hop 证据集里
 *     就有 `AdminAuditService`（appendLog 的定义类，AdminAuditService.java:42），于是注入了
 *     审计服务的控制器恒判可达。负向实证：抹掉 OpsOtaController 两处真实审计调用后，旧式沉默、
 *     现式两条红（零归因未登记 + 可达 71<基线 72）。受影响面：98 个写控制器中 17 个注入
 *     AdminAuditService。现状不是「正在裸写」——按新式重算可达 72 与基线逐字相等，
 *     所以本硬化没炸出欠账、maxTbd 不用重冻结；它补的是**防退化能力**。
 *
 *   node scripts/check-write-action-attribution.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-write-action-attribution]';
const REGISTRY_PATH = join('scripts', 'write-attribution-registry.json');
const FIELD_RE =
  /private\s+final\s+(\w+(?:Service|Repository|Mapper|Support|Client|Manager|Helper|Resolver|Store|Provider))\s+/g;
const WRITE_RE = /@(?:Post|Put|Patch|Delete)Mapping/g;

/**
 * 审计证据必须是**调用点** `xxx.appendLog(`，不能是**定义点** `void appendLog(`。
 *
 * 🔴 旧写法 `/appendLog\s*\(/` 是「证据自喂」：1-hop 证据集里含 `AdminAuditService`
 * 本身，而它正是 `appendLog` 的定义类（AdminAuditService.java:42），于是凡注入了审计服务
 * 的控制器 `auditCapable` 恒真——把真实审计调用整段删掉，判据 ①「可达不退化」也不会红。
 * 实测受影响面：84 个含写端点的控制器里 **17 个**注入了 AdminAuditService。
 * 现状并非「已经在裸写」：按新判据重算，84 个全部自己就在调 `.appendLog(`（自身 0 次的=0 个），
 * 所以本硬化上线时不会炸出新欠账、`maxTbd` 不用重冻结——它修的是**防退化能力**，不是补现漏。
 */
export const AUDIT_CALL_RE = /\.\s*appendLog\s*\(/;

/** 控制器自身或其 1-hop 注入类里是否存在真实的审计调用点。 */
export function hasAuditCallEvidence(controllerSrc, injectedSrcs = []) {
  return (
    AUDIT_CALL_RE.test(controllerSrc || '') ||
    (injectedSrcs || []).some((s) => AUDIT_CALL_RE.test(s || ''))
  );
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (['node_modules', 'target', 'static'].includes(name)) continue;
      walk(p, out);
    } else if (name.endsWith('.java')) out.push(p);
  }
  return out;
}

/** 纯判定：当前能力表 × 登记表。 */
export function evaluate(capability, registry) {
  const problems = [];
  const regMap = new Map(registry.entries.map((e) => [e.file, e]));
  const capSet = new Set(capability.filter((c) => c.auditCapable).map((c) => c.file));

  for (const c of capability) {
    const e = regMap.get(c.file);
    if (c.auditCapable) {
      if (e && e.cls !== undefined && e.auditCapable === false) {
        problems.push(
          `归因退化：${c.file.split('/').pop()} 登记时零归因，现在可达了——把条目从登记表删除（棘轮收紧）`
        );
      }
      // 可达的无需登记；登记表里没有是正常态
      continue;
    }
    if (!e) {
      problems.push(
        `写控制器零归因未登记：${c.file.split('/').pop()}（${c.writes} 个写端点）——` +
          `MA-082 家族：运营写动作必须权限码+审计留痕+前后值三必达，新文件先登记分类`
      );
    }
  }
  for (const [file, e] of regMap) {
    const c = capability.find((x) => x.file === file);
    if (!c) {
      problems.push(
        `登记陈旧：${file.split('/').pop()} 已无写端点（文件删除或端点全下线），条目应删除`
      );
      continue;
    }
    if (c.auditCapable && e.cls === 'TBD') {
      problems.push(
        `欠账销账：${file.split('/').pop()} 现在归因可达（补了留痕），TBD 条目必须删除`
      );
    }
  }
  const tbd = registry.entries.filter((e) => e.cls === 'TBD').length;
  const maxTbd = typeof registry.maxTbd === 'number' ? registry.maxTbd : tbd;
  if (tbd > maxTbd) {
    problems.push(
      `TBD 棘轮击穿：${tbd} > maxTbd ${maxTbd}（新零归因文件须证明语义豁免分类，或销一条旧 TBD）`
    );
  }
  if (typeof registry.capableCount === 'number' && capSet.size < registry.capableCount) {
    problems.push(
      `可达数下降：现在 ${capSet.size} < 切线基线 ${registry.capableCount}——有控制器的审计留痕被拆（MA-082 复发），逐文件核对 capability`
    );
  }
  return { problems, stats: { capable: capSet.size, total: capability.length, tbd } };
}

function main() {
  const regPath = join(ROOT, REGISTRY_PATH);
  if (!existsSync(regPath)) fail(`登记表缺失 ${REGISTRY_PATH}`);
  const registry = JSON.parse(readFileSync(regPath, 'utf8'));

  const allJava = walk(join(ROOT, 'services')).filter((p) => /src[\\/]main[\\/]/.test(p));
  const byClass = new Map();
  const srcOf = new Map();
  for (const f of allJava) {
    const src = readFileSync(f, 'utf8');
    const cls = (src.match(/public\s+(?:final\s+|abstract\s+)?(?:class|record)\s+(\w+)/) || [])[1];
    if (cls) {
      if (!byClass.has(cls)) byClass.set(cls, f);
      srcOf.set(f, src);
    }
  }
  const capability = [];
  for (const f of allJava) {
    const src = srcOf.get(f);
    WRITE_RE.lastIndex = 0;
    if (!WRITE_RE.test(src)) continue;
    WRITE_RE.lastIndex = 0;
    const rel = relative(ROOT, f).replace(/\\/g, '/');
    const writes = (src.match(WRITE_RE) || []).length;
    const injectedSrcs = [];
    for (const m of src.matchAll(FIELD_RE)) {
      const svcFile = byClass.get(m[1]);
      if (svcFile) injectedSrcs.push(srcOf.get(svcFile));
    }
    capability.push({ file: rel, writes, auditCapable: hasAuditCallEvidence(src, injectedSrcs) });
  }
  if (capability.length === 0) fail('写控制器提取数=0（提取器坏了，零命中=假绿）');

  const { problems, stats } = evaluate(capability, registry);
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S9 写动作归因）`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${stats.total} 个写控制器，归因可达 ${stats.capable}（基线 ${registry.capableCount}），` +
      `登记 ${registry.entries.length}（TBD ${stats.tbd}/${registry.maxTbd}）`
  );
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
