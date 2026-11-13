#!/usr/bin/env node
/**
 * R20 P3-1 门禁（2026-11-13，第 6~8 批扩展：8 个迁移 / 30 列）：状态列 CHECK 值域与源码/字典**自动对齐**。
 *
 * 🔴 判据设计（铁律 84 / 47 / 38 / 88④）：
 *   ① 扫 Java/SQL 源码前 maskComments() 抹注释为等长空格（迁移头注释本身单独用 raw 读）；
 *   ② 跨语言口径**自动对齐**：CHECK 字面量集合 ←→ SysDictBootstrap 字典族 ←→ shared-dict
 *      同名族，常量引用走本地常量 / CabinetConstants 解析，禁止人工枚举白名单；
 *   ③ 判据贴真实责任边界：只解析 ADD CONSTRAINT ... CHECK (col IN (...)) 的真谓词；
 *   ④ 迁移形态 fail-closed：每条 ADD CONSTRAINT 必须 NOT VALID（不扫存量行、不持全表锁），
 *      VALIDATE 故意解耦为部署前脏值探针后的运维动作；
 *   ⑤ 欠账登记制：baselineMissingCheck(87) − 已覆盖列数 = maxPendingColumns，只减不增；
 *   ⑥ DDL DEFAULT 必须 ∈ CHECK 值域（否则建表默认插入即被拒 —— 静态可判、100% 可靠）；
 *   ⑦ 带实体类名锚点的源码写入值 set(Cls::getStatus, X) 必须 ⊆ CHECK 值域。
 *      🔴 只认类名锚点，不做「变量名→类型」推断：后者跨方法作用域会把别的表写入值串进来，
 *      实证会误报 balance_refund_request=PENDING/PROCESSING、replenishment_task=PENDING_REVIEW
 *      （真身分别在通用常量与 LineWithdrawService/MerchantWithdrawService）。本判据是**单向、
 *      不完备**的必要检查：抓得到的一律不许越界，抓不到的靠部署前逐列脏值探针兜底。
 *
 * 用法：
 *   node scripts/check-status-enum-parity.mjs             # 正向门禁
 *   node scripts/check-status-enum-parity.mjs --self-test # 沙箱化负向变异 + md5 零残留
 *   node scripts/check-status-enum-parity.mjs --emit      # 打印自动提取的值域清单
 */
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const MIG_DIR = 'services/trade-service/src/main/resources/db/migration';
const JAVA_SCAN_DIR = 'services';
const MIGRATIONS = [
  'V464__status_check_batch1.sql',
  'V465__status_check_batch2_fund.sql',
  'V466__status_check_batch3_order.sql',
  'V467__status_check_batch4_dispute.sql',
  'V468__status_check_batch5_recon.sql',
  'V469__status_check_batch6_device.sql',
  'V470__status_check_batch7_masterdata.sql',
  'V471__status_check_batch8_content.sql',
  'V472__status_check_batch9_sysdict_media.sql'
].map((f) => `${MIG_DIR}/${f}`);

const P = {
  bootstrap: 'services/trade-service/src/main/java/com/aicabinet/trade/service/SysDictBootstrap.java',
  constants: 'services/common/common-core/src/main/java/com/aicabinet/common/constants/CabinetConstants.java',
  dictTs: 'packages/shared-dict/src/index.ts',
  bench: 'docs/COMPETITOR_BENCHMARK.md',
  inv: 'scripts/status-check-inventory.json'
};

const maskJava = (s) => s.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
const maskSql = (s) => s.replace(/--[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
const eq = (a, b) => a.length === b.length && [...a].sort().join('|') === [...b].sort().join('|');

function balanced(src, openIdx, open = '(', close = ')') {
  let depth = 1, q = null;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (q) { if (c === '\\') i++; else if (c === q) q = null; continue; }
    if (c === '"' || c === "'") { q = c; continue; }
    if (c === open) depth++;
    else if (c === close && --depth === 0) return src.slice(openIdx, i);
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

/** shared-dict 同名族的键集合（花括号平衡，兼容单行/多行对象）。 */
function sharedFamily(ts, family) {
  const m = new RegExp(`\\b${family}\\s*:\\s*\\{`).exec(ts);
  if (!m) return null;
  const body = balanced(ts, m[0].length + m.index, '{', '}');
  return [...body.matchAll(/([A-Z][A-Z0-9_]*)\s*:/g)].map((x) => x[1]);
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

/** R6：从全部 V*.sql 的 CREATE TABLE 块自动提取 table.column -> DEFAULT 字面量。 */
function collectDdlDefaults(sqlFiles) {
  const map = new Map();
  for (const [, raw] of sqlFiles) {
    const s = maskSql(raw);
    const re = /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\(/gi;
    let m;
    while ((m = re.exec(s))) {
      const table = m[1];
      const body = balanced(s, m.index + m[0].length);
      for (const part of splitTop(body)) {
        const cm = /^(\w+)\s+(?:VARCHAR|CHARACTER\s+VARYING|TEXT|SMALLINT|INT|INTEGER|BIGINT|NUMERIC|BOOLEAN)\b/i.exec(part);
        if (!cm) continue;
        const dm = /DEFAULT\s+'([^']*)'/i.exec(part);
        map.set(`${table}.${cm[1]}`, dm ? dm[1] : null);
      }
    }
  }
  return map;
}

/** R7：@TableName -> 实体类；带类名锚点的状态写入值（字面量 / 唯一常量 / CabinetConstants）。 */
function collectWriterValues(javaFiles, global) {
  const t2c = new Map();
  for (const [p, raw] of javaFiles) {
    const m = /@TableName\("(\w+)"\)/.exec(raw);
    if (m) t2c.set(m[1], p.split('/').pop().replace(/\.java$/, ''));
  }
  const consts = new Map();
  for (const [, raw] of javaFiles) {
    for (const m of maskJava(raw).matchAll(/static\s+final\s+String\s+(\w+)\s*=\s*"([A-Z][A-Z0-9_]*)"\s*;/g)) {
      if (!consts.has(m[1])) consts.set(m[1], new Set());
      consts.get(m[1]).add(m[2]);
    }
  }
  const byClass = new Map();
  for (const [, raw] of javaFiles) {
    const s = maskJava(raw);
    const re = /set\s*\(\s*([A-Z]\w*)::get(?:Status|[A-Za-z]*Status)\s*,\s*("[^"]*"|CabinetConstants\.\w+|[A-Z][A-Z0-9_]*)\s*\)/g;
    for (const m of s.matchAll(re)) {
      const cls = m[1], tok = m[2];
      let vals = [];
      if (tok.startsWith('"')) vals = [tok.slice(1, -1)];
      else if (tok.startsWith('CabinetConstants.')) {
        const v = global[tok.split('.')[1]];
        if (v) vals = [v];
      } else {
        const set = consts.get(tok);
        // 同名常量映射到多个值时放弃（歧义 ⇒ 不判，避免假红）
        if (set && set.size === 1) vals = [...set];
      }
      if (!byClass.has(cls)) byClass.set(cls, new Set());
      for (const v of vals) byClass.get(cls).add(v);
    }
  }
  return { t2c, byClass };
}

function evaluate(input) {
  const { read, sqlFiles, javaFiles } = input;
  const problems = [];
  const bad = (msg) => problems.push(msg);
  const boot = maskJava(read(P.bootstrap));
  const global = collectConstants(maskJava(read(P.constants)));
  const local = collectConstants(boot);
  const ts = maskJava(read(P.dictTs));
  const bench = read(P.bench);
  const inv = JSON.parse(read(P.inv));

  // ── 汇总全部登记迁移的 CHECK 谓词，并记录每条约束属于哪个迁移 ──
  const byConstraint = new Map();
  const constraintFile = new Map();
  for (const path of MIGRATIONS) {
    const raw = read(path);
    const sql = maskSql(raw);
    const checks = extractChecks(sql);
    const addCount = (sql.match(/\bADD\s+CONSTRAINT\b/gi) || []).length;
    if (!addCount) bad(`${path}: 没有任何 ADD CONSTRAINT（R3 判据失效）`);
    if (addCount !== checks.length) bad(`${path}: 存在无法解析成 CHECK (col IN (...)) 的 ADD CONSTRAINT`);
    for (const c of checks) {
      if (byConstraint.has(c.constraint)) bad(`约束名重复：${c.constraint}（${constraintFile.get(c.constraint)} 与 ${path}）`);
      byConstraint.set(c.constraint, c);
      constraintFile.set(c.constraint, path);
      if (!c.notValid) bad(`${c.constraint} 缺 NOT VALID（会全表扫并持 ACCESS EXCLUSIVE，核心老表禁止）`);
    }
    if (/\bVALIDATE\s+CONSTRAINT\b/i.test(sql))
      bad(`${path}: 出现 VALIDATE CONSTRAINT（大表全扫 + 存量脏值会让 Flyway 失败＝部署失败）`);

    // ── R4 迁移头：COMPETITOR_REF 必须引用台账真实条目；KIND=schema ──
    const ref = /^--\s*COMPETITOR_REF\s*:\s*(.+)$/im.exec(raw);
    if (!ref) bad(`${path}: 迁移头缺 COMPETITOR_REF（文件内容含业务语义词，N/A 一律判红）`);
    else for (const id of ref[1].split(',').map((x) => x.trim())) {
      if (!/^CB-\d{3}$/.test(id)) bad(`${path}: COMPETITOR_REF 引用了非法编号「${id}」`);
      else if (!bench.includes(`### ${id}`)) bad(`COMPETITOR_REF 引用了台账里不存在的 ${id}`);
    }
    if (!/^--\s*MIGRATION_KIND\s*:\s*schema\s*$/im.test(raw))
      bad(`${path}: 迁移头 MIGRATION_KIND 必须是 schema（本迁移无 DML）`);
  }

  const ddlDefaults = collectDdlDefaults(sqlFiles);
  const { t2c, byClass } = collectWriterValues(javaFiles, global);

  // ── R1 登记制：表名/列名/约束名/迁移文件四要素一一对应 ──
  if (!Array.isArray(inv.tables) || !inv.tables.length) bad('清单里没有任何已对齐表');
  for (const t of inv.tables || []) {
    const c = byConstraint.get(t.constraint);
    if (!c) { bad(`迁移里找不到约束 ${t.constraint}（清单登记了 ${t.table}.${t.column}）`); continue; }
    if (c.table !== t.table || c.column !== t.column)
      bad(`${t.constraint} 的表/列与清单不一致（迁移=${c.table}.${c.column}，清单=${t.table}.${t.column}）`);
    const expected = `${MIG_DIR}/${t.migration}`;
    if (!MIGRATIONS.includes(expected))
      bad(`${t.constraint}: 清单 migration 字段「${t.migration}」不在已登记迁移列表里`);
    else if (constraintFile.get(t.constraint) !== expected)
      bad(`${t.constraint}: 实际在 ${constraintFile.get(t.constraint)}，清单却登记 ${expected}`);
  }

  // ── R2 自动对齐 + R6 DEFAULT + R7 源码锚点写入值 ──
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

    // R6：DDL DEFAULT ⊆ CHECK 值域
    const key = `${t.table}.${t.column}`;
    if (!ddlDefaults.has(key)) bad(`DDL 里找不到列 ${key}，无法校验 DEFAULT（R6 判据失效）`);
    else {
      const dv = ddlDefaults.get(key);
      if (dv !== null && c && !c.values.includes(dv))
        bad(`${key} 的 DDL DEFAULT '${dv}' 不在 CHECK 值域（默认插入会被拒）`);
    }

    // R7：带类名锚点的源码写入值 ⊆ CHECK 值域
    const cls = t2c.get(t.table);
    const writers = cls ? [...(byClass.get(cls) || [])].sort() : [];
    if (cls && c) for (const v of writers)
      if (!c.values.includes(v))
        bad(`${key} 源码锚点写入值 '${v}' 不在 CHECK 值域（上线写入会失败）`);

    emit.push({
      ...t, entity: cls ?? null,
      ddlDefault: ddlDefaults.get(key) ?? null,
      writerValues: writers,
      dictValues: fromDict.sort(), sharedValues: fromTs.sort(),
      checkValues: c ? [...c.values].sort() : null
    });
  }

  // ── R5 欠账棘轮：baselineMissingCheck − 已覆盖 = maxPendingColumns，只减不增 ──
  const base = Number(inv.baselineMissingCheck);
  if (!Number.isInteger(base) || base !== 87)
    bad(`baselineMissingCheck=${inv.baselineMissingCheck} 必须是 dev 实测的 87（禁止改口径做小欠账）`);
  const derived = base - (inv.tables || []).length;
  if (inv.maxPendingColumns !== derived)
    bad(`maxPendingColumns=${inv.maxPendingColumns} 与派生值 ${derived} 不符（新增表必须同步减小欠账，禁止改大）`);

  return { problems, emit };
}

const diskRead = (p) => readFileSync(join(ROOT, p), 'utf8');
const md5 = (s) => createHash('md5').update(s).digest('hex');

function diskScan(dir, re) {
  const out = [];
  const walk = (rel) => {
    let entries;
    try { entries = readdirSync(join(ROOT, rel), { withFileTypes: true }); }
    catch { return; }
    for (const e of entries) {
      const p = `${rel}/${e.name}`;
      if (e.isDirectory()) walk(p);
      else if (re.test(e.name)) out.push([p, diskRead(p)]);
    }
  };
  walk(dir);
  return out;
}

function baselineInput() {
  const sqlFiles = diskScan(MIG_DIR, /\.sql$/);
  const javaFiles = diskScan(JAVA_SCAN_DIR, /\.java$/);
  const staticPaths = [...Object.values(P), ...MIGRATIONS];
  const all = new Map();
  for (const p of staticPaths) if (!all.has(p)) all.set(p, diskRead(p));
  for (const [p, c] of [...sqlFiles, ...javaFiles]) if (!all.has(p)) all.set(p, c);
  return { sqlFiles, javaFiles, paths: [...all.keys()], all };
}

function selfTest() {
  const base = baselineInput();
  const before = new Map(base.paths.map((p) => [p, md5(base.all.get(p))]));
  const V464 = MIGRATIONS[0], V465 = MIGRATIONS[1], V466 = MIGRATIONS[2];
  const DDL_WITHDRAW = `${MIG_DIR}/V139__merchant_withdraw.sql`;
  const MAPPER_WITHDRAW = 'services/trade-service/src/main/java/com/aicabinet/trade/mapper/MerchantWithdrawRequestMapper.java';

  const mutations = [
    ['CHECK 漏一个合法状态', V464, (s) => s.replace("'DISPUTED',", ''), 'CHECK 值域与字典族不同源'],
    ['字典族新增状态但没同步约束', P.bootstrap,
      (s) => s.replace('"DISPUTED", "争议中", "REFUNDED", "已退款", "PARTIAL_REFUNDED", "部分退款",',
                       '"DISPUTED", "争议中", "ARCHIVED", "已归档", "REFUNDED", "已退款", "PARTIAL_REFUNDED", "部分退款",'),
      'CHECK 值域与字典族不同源'],
    ['跨端 shared-dict 删一个键', P.dictTs,
      (s) => s.replace("  order_status: {\n    PENDING: '待支付',", "  order_status: {\n"),
      '跨端不同源'],
    ['去掉 NOT VALID', V464, (s) => s.replace('NOT VALID;', ';'), '缺 NOT VALID'],
    ['迁移里偷偷加 VALIDATE', V466, (s) => s + '\nALTER TABLE purchase_order VALIDATE CONSTRAINT chk_purchase_order_status;\n', 'VALIDATE CONSTRAINT'],
    ['删迁移头 COMPETITOR_REF', V465, (s) => s.replace(/^--\s*COMPETITOR_REF.*$/m, ''), '缺 COMPETITOR_REF'],
    ['COMPETITOR_REF 引用不存在编号', V465, (s) => s.replace('COMPETITOR_REF: CB-036', 'COMPETITOR_REF: CB-999'), '台账里不存在'],
    ['MIGRATION_KIND 被改掉', V466, (s) => s.replace('MIGRATION_KIND: schema', 'MIGRATION_KIND: backfill'), 'MIGRATION_KIND'],
    ['清单改错 dictFamily', P.inv, (s) => s.replace('"order_status"', '"order_status_v2"'), '无法从 SysDictBootstrap 自动提取'],
    ['欠账棘轮被改大', P.inv, (s) => s.replace(/"maxPendingColumns": \d+/, '"maxPendingColumns": 90'), '欠账'],
    ['基线口径被改小', P.inv, (s) => s.replace('"baselineMissingCheck": 87', '"baselineMissingCheck": 60'), 'baselineMissingCheck'],
    ['清单与迁移约束名对不上', P.inv, (s) => s.replace('chk_cabinet_order_status', 'chk_cabinet_order_status_v2'), '找不到约束'],
    ['清单 migration 字段指错文件', P.inv,
      (s) => s.replace('"migration": "V467__status_check_batch4_dispute.sql"', '"migration": "V465__status_check_batch2_fund.sql"'),
      '实际在'],
    ['新迁移删掉一条约束', MIGRATIONS[3],
      (s) => s.replace(/ALTER TABLE repair_ticket[\s\S]*?NOT VALID;\n/, ''), '找不到约束'],
    ['DDL DEFAULT 改成族外值', DDL_WITHDRAW,
      (s) => s.replace("DEFAULT 'PENDING_REVIEW'", "DEFAULT 'TOTALLY_BOGUS'"), 'DDL DEFAULT'],
    ['源码锚点写入越界值', MAPPER_WITHDRAW,
      (s) => s.replace('set(MerchantWithdrawRequest::getStatus, "PAYING")',
                       'set(MerchantWithdrawRequest::getStatus, "TOTALLY_BOGUS")'), '源码锚点写入值'],
    ['新链迁移删掉一条约束', MIGRATIONS[6],
      (s) => s.replace(/ALTER TABLE merchant[\s\S]*?NOT VALID;\n/, ''), '找不到约束'],
    ['新链迁移去掉 NOT VALID', MIGRATIONS[7],
      (s) => s.replace('NOT VALID;', ';'), '缺 NOT VALID'],
    ['新链 CHECK 漏一个合法状态', MIGRATIONS[6],
      (s) => s.replace(",'PENDING'", ''), 'CHECK 值域与字典族不同源'],
    ['V472 迁移删掉一条约束', MIGRATIONS[8],
      (s) => s.replace(/ALTER TABLE sys_dict_data[\s\S]*?NOT VALID;\n/, ''), '找不到约束'],
    ['V472 迁移去掉 NOT VALID', MIGRATIONS[8],
      (s) => s.replace('NOT VALID;', ';'), '缺 NOT VALID'],
    ['V472 CHECK 漏一个合法状态', MIGRATIONS[8],
      (s) => s.replace(/CHECK \(status IN \('ACTIVE','INACTIVE'\)\)/, "CHECK (status IN ('ACTIVE'))"), 'CHECK 值域与字典族不同源'],
    ['设备在线列 DDL DEFAULT 越界', `${MIG_DIR}/V1__init_schema.sql`,
      (s) => s.replace("online_status     VARCHAR(16)  NOT NULL DEFAULT 'OFFLINE'",
                       "online_status     VARCHAR(16)  NOT NULL DEFAULT 'TOTALLY_BOGUS'"), 'DDL DEFAULT']
  ];
  let passed = 0;
  for (const [name, path, mutate, expect] of mutations) {
    const copy = new Map(base.all);
    const src = base.all.get(path);
    if (!src) { console.error(`[self-test] FAIL 基线缺少文件：${path}`); process.exit(1); }
    const mutated = mutate(src);
    if (mutated === src) { console.error(`[self-test] FAIL 变异未生效：${name}`); process.exit(1); }
    copy.set(path, mutated);
    const input = {
      read: (p) => copy.get(p),
      sqlFiles: base.sqlFiles.map(([p, c]) => [p, copy.get(p) ?? c]),
      javaFiles: base.javaFiles.map(([p, c]) => [p, copy.get(p) ?? c])
    };
    const { problems } = evaluate(input);
    if (!problems.length) { console.error(`[self-test] FAIL 变异未被咬住：${name}`); process.exit(1); }
    if (!problems.some((x) => x.includes(expect))) {
      console.error(`[self-test] FAIL 命中的不是预期判据「${expect}」：${name}`, problems); process.exit(1);
    }
    passed++;
  }
  // 反向：注释里复述旧写法不得假红（迁移头注释里本来就写了 CHECK/VALIDATE 示例）
  const withComment = new Map(base.all);
  withComment.set(V465, base.all.get(V465) + "\n-- 旧写法：ALTER TABLE x ADD CONSTRAINT bad CHECK (status IN ('BOGUS'));\n-- ALTER TABLE x VALIDATE CONSTRAINT bad;\n");
  const cp = evaluate({
    read: (p) => withComment.get(p),
    sqlFiles: base.sqlFiles.map(([p, c]) => [p, withComment.get(p) ?? c]),
    javaFiles: base.javaFiles.map(([p, c]) => [p, withComment.get(p) ?? c])
  }).problems;
  if (cp.length) { console.error('[self-test] FAIL 注释里的旧写法造成假红：', cp); process.exit(1); }
  passed++;
  for (const p of base.paths) if (md5(diskRead(p)) !== before.get(p)) {
    console.error(`[self-test] FAIL 磁盘被改动：${p}`); process.exit(1);
  }
  console.log(`[check-status-enum-parity] self-test OK：${passed} 个变异/防假红断言全部符合，md5 零残留`);
  process.exit(0);
}

if (process.argv.includes('--self-test')) selfTest();
else {
  const base = baselineInput();
  const { problems, emit } = evaluate({ read: diskRead, sqlFiles: base.sqlFiles, javaFiles: base.javaFiles });
  if (process.argv.includes('--emit')) {
    console.log(JSON.stringify(emit, null, 2));
    process.exit(problems.length ? 1 : 0);
  }
  if (problems.length) {
    console.error('[check-status-enum-parity] FAIL');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(`[check-status-enum-parity] OK：${emit.length} 列三方自动对齐 + ${MIGRATIONS.length} 个迁移全 NOT VALID + DDL DEFAULT/源码锚点单向包含 + 欠账棘轮=${base.all.get(P.inv) ? JSON.parse(base.all.get(P.inv)).maxPendingColumns : '?'}，7 组判据全绿`);
}
