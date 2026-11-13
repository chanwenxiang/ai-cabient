#!/usr/bin/env node
/**
 * R20 P3-1 门禁（2026-11-13）：状态列 CHECK 值域与源码/字典**自动对齐**。
 *
 * 🔴 判据设计（铁律 84 / 47 / 38 / 88④）：
 *   ① 扫 Java/SQL 源码前 maskComments() 抹注释为等长空格（迁移头注释本身单独用 raw 读）；
 *   ② 跨语言口径**自动对齐**：CHECK 字面量集合 ←→ SysDictBootstrap 字典族 ←→ shared-dict
 *      同名族，常量引用走本地常量 / CabinetConstants 解析，禁止人工枚举白名单；
 *   ③ 判据贴真实责任边界：只解析 ADD CONSTRAINT ... CHECK (col IN (...)) 的真谓词；
 *   ④ 迁移形态 fail-closed：每条 ADD CONSTRAINT 必须 NOT VALID（不扫存量行、不持全表锁），
 *      VALIDATE 故意解耦为部署前脏值探针后的运维动作；
 *   ⑤ 欠账登记制：87 列缺 CHECK − 已覆盖列数 = maxPendingColumns，只减不增。
 *
 * 用法：
 *   node scripts/check-status-enum-parity.mjs             # 正向门禁
 *   node scripts/check-status-enum-parity.mjs --self-test # 沙箱化负向变异 + md5 零残留
 *   node scripts/check-status-enum-parity.mjs --emit      # 打印自动提取的值域清单
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const P = {
  bootstrap: 'services/trade-service/src/main/java/com/aicabinet/trade/service/SysDictBootstrap.java',
  constants: 'services/common/common-core/src/main/java/com/aicabinet/common/constants/CabinetConstants.java',
  dictTs: 'packages/shared-dict/src/index.ts',
  bench: 'docs/COMPETITOR_BENCHMARK.md',
  inv: 'scripts/status-check-inventory.json',
  sql: 'services/trade-service/src/main/resources/db/migration/V464__status_check_batch1.sql'
};

const maskJava = (s) => s.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
const maskSql = (s) => s.replace(/--[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
const eq = (a, b) => a.length === b.length && [...a].sort().join('|') === [...b].sort().join('|');

function balanced(src, openIdx) {
  let depth = 1, q = null;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (q) { if (c === '\\') i++; else if (c === q) q = null; continue; }
    if (c === '"' || c === "'") { q = c; continue; }
    if (c === '(') depth++;
    else if (c === ')' && --depth === 0) return src.slice(openIdx, i);
  }
  return '';
}

function splitTop(s) {
  const out = []; let cur = '', q = null;
  for (const c of s) {
    if (q) { cur += c; if (c === q) q = null; continue; }
    if (c === '"' || c === "'") { q = c; cur += c; continue; }
    if (c === ',') { out.push(cur.trim()); cur = ''; continue; }
    cur += c;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

function collectConstants(src) {
  const m = {};
  const re = /(?:public|private)\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"\s*;/g;
  let x;
  while ((x = re.exec(src))) m[x[1]] = x[2];
  return m;
}

function resolveToken(tok, local, global) {
  const lit = /^"([^"]*)"$/.exec(tok);
  if (lit) return lit[1];
  const q = /^CabinetConstants\.(\w+)$/.exec(tok);
  if (q) return global[q[1]] ?? null;
  if (/^\w+$/.test(tok)) return local[tok] ?? null;
  return null;
}

/** 从 map.put("<family>", t(..., m(k1,label1,...))) 自动提取 key 集合。 */
function dictFamily(src, family, local, global) {
  const i = src.indexOf(`map.put("${family}",`);
  if (i < 0) return null;
  const mIdx = src.indexOf('m(', i);
  if (mIdx < 0) return null;
  const args = splitTop(balanced(src, mIdx + 2));
  const keys = [];
  for (let k = 0; k < args.length; k += 2) {
    const v = resolveToken(args[k], local, global);
    if (!v) return null;
    keys.push(v);
  }
  return keys;
}

/** shared-dict 同名族的键集合（TS 对象字面量，键为大写枚举）。 */
function sharedFamily(ts, family) {
  const m = new RegExp(`\\b${family}\\s*:\\s*\\{([\\s\\S]*?)\\}`).exec(ts);
  if (!m) return null;
  return [...m[1].matchAll(/([A-Z][A-Z0-9_]*)\s*:/g)].map((x) => x[1]);
}

/** 解析迁移里每条 ADD CONSTRAINT 的 CHECK 真谓词（不 grep 注释）。 */
function extractChecks(sql) {
  const out = [];
  const re = /ALTER\s+TABLE\s+(\w+)\s+ADD\s+CONSTRAINT\s+(\w+)\s+CHECK\s*\(\s*(\w+)\s+IN\s*\(([^)]*)\)\s*\)\s*(NOT\s+VALID)?/gi;
  let m;
  while ((m = re.exec(sql))) {
    out.push({
      table: m[1], constraint: m[2], column: m[3],
      values: [...m[4].matchAll(/'([^']*)'/g)].map((x) => x[1]),
      notValid: !!m[5]
    });
  }
  return out;
}

function evaluate(read) {
  const problems = [];
  const bad = (msg) => problems.push(msg);
  const bootRaw = read(P.bootstrap);
  const boot = maskJava(bootRaw);
  const constantSrc = maskJava(read(P.constants));
  const ts = maskJava(read(P.dictTs));
  const bench = read(P.bench);
  const inv = JSON.parse(read(P.inv));
  const sqlRaw = read(P.sql);
  const sql = maskSql(sqlRaw);

  const local = collectConstants(boot);
  const global = collectConstants(constantSrc);
  const checks = extractChecks(sql);
  const byConstraint = new Map(checks.map((c) => [c.constraint, c]));

  // ── R1 登记制：三要素齐备，且表名/列名/约束名一一对应 ──
  if (!Array.isArray(inv.tables) || !inv.tables.length) bad('清单里没有任何已对齐表');
  for (const t of inv.tables || []) {
    if (!byConstraint.has(t.constraint)) bad(`迁移里找不到约束 ${t.constraint}（清单登记了 ${t.table}.${t.column}）`);
    const c = byConstraint.get(t.constraint);
    if (c && (c.table !== t.table || c.column !== t.column))
      bad(`${t.constraint} 的表/列与清单不一致（迁移=${c.table}.${c.column}，清单=${t.table}.${t.column}）`);
  }

  // ── R2 自动对齐：CHECK 字面量 ↔ 字典族 ↔ shared-dict 跨端集合，三者双向相等 ──
  const emit = [];
  for (const t of inv.tables || []) {
    const fromDict = dictFamily(boot, t.dictFamily, local, global);
    const fromTs = sharedFamily(ts, t.dictFamily);
    const c = byConstraint.get(t.constraint);
    if (fromDict === null) { bad(`字典族 ${t.dictFamily} 无法从 SysDictBootstrap 自动提取（常量解析失败）`); continue; }
    if (fromTs === null) { bad(`shared-dict 找不到同名族 ${t.dictFamily}`); continue; }
    if (!eq(fromDict, fromTs))
      bad(`${t.dictFamily} 跨端不同源：SysDictBootstrap=[${fromDict}] vs shared-dict=[${fromTs}]`);
    if (c && !eq(c.values, fromDict))
      bad(`${t.constraint} 的 CHECK 值域与字典族不同源：CHECK=[${c.values}] vs ${t.dictFamily}=[${fromDict}]`);
    emit.push({ ...t, dictValues: fromDict.sort(), sharedValues: fromTs.sort(), checkValues: c ? [...c.values].sort() : null });
  }

  // ── R3 迁移形态：每条 ADD CONSTRAINT 必须 NOT VALID ──
  const addCount = (sql.match(/\bADD\s+CONSTRAINT\b/gi) || []).length;
  if (!addCount) bad('迁移里没有任何 ADD CONSTRAINT（判据失效）');
  if (addCount !== checks.length) bad('存在无法解析成 CHECK (col IN (...)) 的 ADD CONSTRAINT');
  for (const c of checks)
    if (!c.notValid) bad(`${c.constraint} 缺 NOT VALID（会全表扫并持 ACCESS EXCLUSIVE，核心老表禁止）`);
  if (/\bVALIDATE\s+CONSTRAINT\b/i.test(sql))
    bad('迁移里出现 VALIDATE CONSTRAINT（大表全扫 + 存量脏值会让 Flyway 失败＝部署失败，必须解耦为运维动作）');

  // ── R4 迁移头：COMPETITOR_REF 必须引用台账真实条目；KIND=schema ──
  const ref = /^--\s*COMPETITOR_REF\s*:\s*(.+)$/im.exec(sqlRaw);
  if (!ref) bad('迁移头缺 COMPETITOR_REF（文件内容含业务语义词，N/A 一律判红）');
  else for (const id of ref[1].split(',').map((x) => x.trim())) {
    if (!/^CB-\d{3}$/.test(id)) bad(`COMPETITOR_REF 引用了非法编号「${id}」`);
    else if (!bench.includes(`### ${id}`)) bad(`COMPETITOR_REF 引用了台账里不存在的 ${id}`);
  }
  if (!/^--\s*MIGRATION_KIND\s*:\s*schema\s*$/im.test(sqlRaw)) bad('迁移头 MIGRATION_KIND 必须是 schema（本迁移无 DML）');

  // ── R5 欠账棘轮：87 缺 CHECK − 已覆盖 = maxPendingColumns，只减不增 ──
  const derived = 87 - (inv.tables || []).length;
  if (inv.maxPendingColumns !== derived)
    bad(`maxPendingColumns=${inv.maxPendingColumns} 与派生值 ${derived} 不符（新增表必须同步减小欠账，禁止改大）`);

  return { problems, emit };
}

const diskRead = (p) => readFileSync(join(ROOT, p), 'utf8');
const md5 = (s) => createHash('md5').update(s).digest('hex');

function selfTest() {
  const paths = Object.values(P);
  const baseline = new Map(paths.map((p) => [p, diskRead(p)]));
  const before = new Map(paths.map((p) => [p, md5(baseline.get(p))]));
  const mutations = [
    ['CHECK 漏一个合法状态', P.sql, (s) => s.replace("'DISPUTED',", ''), 'CHECK 值域与字典族不同源'],
    ['字典族新增状态但没同步约束', P.bootstrap,
      (s) => s.replace('"DISPUTED", "争议中", "REFUNDED", "已退款", "PARTIAL_REFUNDED", "部分退款",',
                       '"DISPUTED", "争议中", "ARCHIVED", "已归档", "REFUNDED", "已退款", "PARTIAL_REFUNDED", "部分退款",'),
      'CHECK 值域与字典族不同源'],
    ['跨端 shared-dict 删一个键', P.dictTs,
      (s) => s.replace("  order_status: {\n    PENDING: '待支付',", "  order_status: {\n"),
      '跨端不同源'],
    ['去掉 NOT VALID', P.sql, (s) => s.replace('NOT VALID;', ';'), '缺 NOT VALID'],
    ['迁移里偷偷加 VALIDATE', P.sql, (s) => s + '\nALTER TABLE cabinet_order VALIDATE CONSTRAINT chk_cabinet_order_status;\n', 'VALIDATE CONSTRAINT'],
    ['删迁移头 COMPETITOR_REF', P.sql, (s) => s.replace(/^--\s*COMPETITOR_REF.*$/m, ''), '缺 COMPETITOR_REF'],
    ['COMPETITOR_REF 引用不存在编号', P.sql, (s) => s.replace('COMPETITOR_REF: CB-036', 'COMPETITOR_REF: CB-999'), '台账里不存在'],
    ['清单改错 dictFamily', P.inv, (s) => s.replace('"order_status"', '"order_status_v2"'), '无法从 SysDictBootstrap 自动提取'],
    ['欠账棘轮被改大', P.inv, (s) => s.replace('"maxPendingColumns": 84', '"maxPendingColumns": 90'), '欠账'],
    ['清单与迁移约束名对不上', P.inv, (s) => s.replace('chk_cabinet_order_status', 'chk_cabinet_order_status_v2'), '找不到约束']
  ];
  let passed = 0;
  for (const [name, path, mutate, expect] of mutations) {
    const copy = new Map(baseline);
    const mutated = mutate(baseline.get(path));
    if (mutated === baseline.get(path)) { console.error(`[self-test] FAIL 变异未生效：${name}`); process.exit(1); }
    copy.set(path, mutated);
    const { problems } = evaluate((p) => copy.get(p));
    if (!problems.length) { console.error(`[self-test] FAIL 变异未被咬住：${name}`); process.exit(1); }
    if (!problems.some((x) => x.includes(expect))) {
      console.error(`[self-test] FAIL 命中的不是预期判据「${expect}」：${name}`, problems); process.exit(1);
    }
    passed++;
  }
  // 反向：注释里复述旧写法不得假红（迁移头注释里本来就写了 CHECK 示例）
  const withComment = new Map(baseline);
  withComment.set(P.sql, baseline.get(P.sql) + "\n-- 旧写法：ALTER TABLE cabinet_order ADD CONSTRAINT bad CHECK (status IN ('BOGUS'));\n");
  const { problems: cp } = evaluate((p) => withComment.get(p));
  if (cp.length) { console.error('[self-test] FAIL 注释里的旧写法造成假红：', cp); process.exit(1); }
  passed++;
  for (const p of paths) if (md5(diskRead(p)) !== before.get(p)) {
    console.error(`[self-test] FAIL 磁盘被改动：${p}`); process.exit(1);
  }
  console.log(`[check-status-enum-parity] self-test OK：${passed} 个变异/防假红断言全部符合，md5 零残留`);
  process.exit(0);
}

if (process.argv.includes('--self-test')) selfTest();
else {
  const { problems, emit } = evaluate(diskRead);
  if (process.argv.includes('--emit')) {
    console.log(JSON.stringify(emit, null, 2));
    process.exit(problems.length ? 1 : 0);
  }
  if (problems.length) {
    console.error('[check-status-enum-parity] FAIL');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(`[check-status-enum-parity] OK：${emit.length} 张表值域三方自动对齐 + 全部 NOT VALID + 欠账棘轮，6 组判据全绿`);
}
