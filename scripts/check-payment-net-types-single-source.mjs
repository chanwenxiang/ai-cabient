#!/usr/bin/env node
/**
 * 支付净额口径「单一事实源」门禁（教训 #326 第二例的防复发装置）。
 *
 * 背景：`payment_operation` 的净入账口径 = `CHARGE / ADJUST_CHARGE / PREAUTH_CAPTURE − REFUND`。
 * 这条口径在仓库里有 8 个消费方（对账、巡检报警、提示语、以及**两条会动钱的修复路径取数**），
 * 而 F-10 只修了其中 1 个副本 ⇒ `DataConsistencyService` 的 4 份内联清单和
 * `PaymentOperationMapper:116` 继续漏计 `PREAUTH_CAPTURE`，隔离栈实跑一次性产出 4 条假阳性
 * （开门柜 F1 入账就是走 `PREAUTH_CAPTURE`，压根没有 `CHARGE` 行）。
 *
 * 所以本门禁做两件事：
 *   1. ** banned 形态**：任何「同时列了 CHARGE + ADJUST_CHARGE + REFUND 却没列 PREAUTH_CAPTURE」
 *      的枚举清单（SQL `IN (...)`、Java `.in(...)`、JS 数组都算）必须显式登记理由才能存在。
 *   2. **单一事实源真的被用**：`PaymentOperationTypes` 必须存在且带全四类；
 *      `DataConsistencyService` 必须通过它取口径而不是再抄一份字面量（引用数下限钉住，
 *      低于下限说明有人把常量又还原成内联 SQL）。
 *
 * 允许存在的例外（见 ALLOWED，逐个带理由）：按 `channel` 过滤的**网关现金对账**——
 * `PREAUTH_CAPTURE` 行只挂在 `channel='BALANCE'` 上，把这三类清单加上它等于要求网关渠道
 * 出现余额冲抵流水，是另一种错。这类副本必须留在文件里、但必须被点名。
 *
 *   node scripts/check-payment-net-types-single-source.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-payment-net-types-single-source]';

const SCAN_DIRS = ['services', 'clients', 'packages', 'scripts', 'infra'];
const SOURCE_EXT = ['.java', '.xml', '.sql', '.mjs', '.ts'];
const SKIP_DIR = new Set([
  'node_modules',
  'dist',
  'target',
  'unpackage',
  '.git',
  'coverage',
  'build'
]);
/** 后台构建产物里可能含内联 SQL 片段，不是可编辑源码。 */
const SKIP_PATH_SUBSTR = ['static/admin/assets/'];

/** 扫到至少这么多「含 ADJUST_CHARGE」的文件，否则视为扫描面塌了、门禁本身失效。 */
const MIN_FILES_WITH_TYPE = 4;

/** 净额四类的必填项：一份清单里出现前三者就必须出现 PREAUTH_CAPTURE。 */
const REQUIRED_TRIO = ['CHARGE', 'ADJUST_CHARGE', 'REFUND'];
const CANONICAL_FOURTH = 'PREAUTH_CAPTURE';

/** `DataConsistencyService` 对单一事实源的最少引用数（本轮收敛后为 7，留 1 的余量）。 */
const SINGLE_SOURCE_CONSUMER =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/DataConsistencyService.java';
const MIN_SINGLE_SOURCE_REFS = 6;

/**
 * 允许保留「缺 PREAUTH_CAPTURE」清单的文件 ⇒ 必须给出的条数 + 必须出现在文件里的语境证据 + 理由。
 * 条数是精确的：多一条（新增副本）或少一条（口径已收敛）都判失败，逼着改动的人回来更新本表。
 */
const ALLOWED = new Map([
  [
    'services/trade-service/src/main/java/com/aicabinet/trade/mapper/PaymentOperationMapper.java',
    {
      count: 1,
      evidence: 'UPPER(channel) = UPPER(#{channel})',
      reason:
        'findDistinctCabinetOrderIdsBetween 是**按渠道**的网关现金对账；PREAUTH_CAPTURE 只挂 channel=BALANCE'
    }
  ],
  [
    'services/trade-service/src/test/java/com/aicabinet/trade/mapper/PaymentOperationNetCompletedFilterTest.java',
    {
      count: 1,
      evidence: 'completedOpsQuery_coversPreauthCaptureRows',
      reason:
        'containsAll(CHARGE/ADJUST_CHARGE/REFUND) 是「只加不减」断言，同文件另有用例钉 PREAUTH_CAPTURE 必须在'
    }
  ]
]);

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (SKIP_PATH_SUBSTR.some((s) => full.replace(/\\/g, '/').includes(s))) continue;
    const st = statSync(full);
    if (st.isDirectory()) {
      if (!SKIP_DIR.has(name)) walk(full, out);
    } else if (SOURCE_EXT.some((ext) => name.endsWith(ext))) {
      out.push(full);
    }
  }
  return out;
}

/** 门禁自己的自检夹具含 banned 形态 ⇒ 判它等于永远红。 */
const SELF = fileURLToPath(import.meta.url);

/** 剥掉引号与空白后的字符串字面量集合；非字面量（列名、占位符）忽略。 */
function literalsOf(bracketBody) {
  const out = [];
  const re = /['"]([A-Za-z0-9_]+)['"]/g;
  let m;
  while ((m = re.exec(bracketBody)) !== null) out.push(m[1]);
  return out;
}

/**
 * 找出一个文件里所有「列了净额前三类却漏 PREAUTH_CAPTURE」的清单。
 * 括号体允许跨行（Java 的 `.in(...)` 就折行），但不吃嵌套括号 —— 嵌套内的内层清单会被单独判。
 */
function findStaleNetLists(src) {
  const hits = [];
  const re = /\(([^()]*)\)/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    const lits = literalsOf(m[1]);
    if (lits.length === 0) continue;
    const set = new Set(lits);
    if (!REQUIRED_TRIO.every((t) => set.has(t))) continue;
    if (set.has(CANONICAL_FOURTH)) continue;
    hits.push({
      line: src.slice(0, m.index).split('\n').length,
      text: m[1].replace(/\s+/g, ' ').trim()
    });
  }
  return hits;
}

/* ------------------------------------------------------------------ *
 * 自检：先证明检测器本身是对的，再拿去判别人的代码（同 check-money-yuan-prefix 的规矩）。
 * ------------------------------------------------------------------ */
function selfCheck() {
  const cases = [
    // 历史假阳性形态：SQL IN 清单缺 PREAUTH_CAPTURE ⇒ 必须命中
    [`SELECT 1 WHERE operation_type IN ('CHARGE', 'ADJUST_CHARGE', 'REFUND')`, 1],
    // Java wrapper 折行形态 ⇒ 必须命中
    [`.in(PaymentOperation::getOperationType,\n    "CHARGE", "ADJUST_CHARGE", "REFUND")`, 1],
    // 收敛后的正确清单 ⇒ 不命中
    [`IN ('CHARGE', 'ADJUST_CHARGE', 'REFUND', 'PREAUTH_CAPTURE')`, 0],
    // 入账三类（本就不含 REFUND）⇒ 不命中，别把 CHARGE 侧的合法子集也判死
    [`IN ('CHARGE', 'ADJUST_CHARGE', 'PREAUTH_CAPTURE')`, 0],
    // 现金对账的充值口径（前三类不齐）⇒ 不命中
    [`IN ('CHARGE', 'ADJUST_CHARGE', 'RECHARGE')`, 0],
    // 单一事实源自己的两行常量 ⇒ 不命中
    [`public static final String INBOUND = "'CHARGE', 'ADJUST_CHARGE', 'PREAUTH_CAPTURE'";`, 0],
    // 嵌套括号：外层缺第四类 ⇒ 命中一次即可（内层不含前三类）
    [`SUM(CASE WHEN operation_type IN ('CHARGE','ADJUST_CHARGE','REFUND') THEN 1 ELSE 0 END)`, 1]
  ];
  for (const [src, expected] of cases) {
    const got = findStaleNetLists(src).length;
    if (got !== expected) {
      console.error(
        `${TAG} FAIL: 检测器自检不通过（期望命中 ${expected}，实际 ${got}）\n  ${src.replace(/\s+/g, ' ')}`
      );
      process.exit(1);
    }
  }

  // 单一事实源常量本身必须四类齐全，否则「收敛」只是把漏口径集中到一处
  const constPath = join(
    root,
    'services/trade-service/src/main/java/com/aicabinet/trade/domain/PaymentOperationTypes.java'
  );
  if (!existsSync(constPath)) {
    console.error(`${TAG} FAIL: 单一事实源文件不存在：${constPath}`);
    process.exit(1);
  }
  const constSrc = readFileSync(constPath, 'utf8');
  for (const must of ["'CHARGE'", "'ADJUST_CHARGE'", "'PREAUTH_CAPTURE'", "'REFUND'"]) {
    if (!constSrc.includes(must)) {
      console.error(`${TAG} FAIL: PaymentOperationTypes 缺少 ${must} —— 口径本身就不全`);
      process.exit(1);
    }
  }
  if (findStaleNetLists(constSrc).length !== 0) {
    console.error(`${TAG} FAIL: PaymentOperationTypes 自己就带了缺 PREAUTH_CAPTURE 的清单`);
    process.exit(1);
  }
}
selfCheck();

/* ------------------------------------------------------------------ *
 * 扫描
 * ------------------------------------------------------------------ */
const files = SCAN_DIRS.flatMap((d) => walk(join(root, d))).filter((f) => resolve(f) !== SELF);
const consumers = files.filter((f) => readFileSync(f, 'utf8').includes('ADJUST_CHARGE'));
if (consumers.length < MIN_FILES_WITH_TYPE) {
  console.error(
    `${TAG} FAIL: 只扫到 ${consumers.length} 个含 ADJUST_CHARGE 的源文件（< ${MIN_FILES_WITH_TYPE}），` +
      '扫描目录/后缀可能变了，门禁已失效'
  );
  process.exit(1);
}

const violations = [];
const staleCountByFile = new Map();
for (const file of consumers) {
  const rel = relative(root, file).replace(/\\/g, '/');
  const hits = findStaleNetLists(readFileSync(file, 'utf8'));
  if (hits.length === 0) continue;
  staleCountByFile.set(rel, hits.length);
  const allowance = ALLOWED.get(rel);
  if (allowance) {
    const src = readFileSync(file, 'utf8');
    if (!src.includes(allowance.evidence)) {
      violations.push(
        `${rel}: 登记的理由证据「${allowance.evidence}」已不在文件里，例外失效（请删掉本条登记或恢复语境）`
      );
    } else if (hits.length !== allowance.count) {
      violations.push(
        `${rel}: 允许 ${allowance.count} 条按渠道的例外清单，实际 ${hits.length} 条 ` +
          `（新增/收敛后请同步 ALLOWED 登记）\n    首处 L${hits[0].line}: ${hits[0].text}`
      );
    }
    continue;
  }
  for (const h of hits) {
    violations.push(
      `${rel}:${h.line}: 净额清单缺 ${CANONICAL_FOURTH} ⇒ 改取 PaymentOperationTypes\n    ${h.text}`
    );
  }
}

const consumerAbs = join(root, SINGLE_SOURCE_CONSUMER);
if (existsSync(consumerAbs)) {
  const refs = (readFileSync(consumerAbs, 'utf8').match(/PaymentOperationTypes\./g) || []).length;
  if (refs < MIN_SINGLE_SOURCE_REFS) {
    violations.push(
      `${SINGLE_SOURCE_CONSUMER}: 对单一事实源的引用只有 ${refs} 处（< ${MIN_SINGLE_SOURCE_REFS}）` +
        '—— 口径又被还原成内联字面量了'
    );
  }
} else {
  violations.push(`${SINGLE_SOURCE_CONSUMER} 不存在 —— 巡检判据搬家了，请同步本门禁路径`);
}

if (violations.length > 0) {
  console.error(
    `${TAG} FAIL: 净入账口径（入账 CHARGE/ADJUST_CHARGE/PREAUTH_CAPTURE − REFUND）有 ${violations.length} 处漂移，` +
      `扫描面 ${consumers.length} 文件`
  );
  for (const v of violations) console.error('  - ' + v);
  console.error(
    '\n  正解：Java 侧拼 SQL 用 PaymentOperationTypes.netCaseSql(prefix) / netTypesFilterSql(prefix)；\n' +
      '  确有不同口径（按渠道的现金对账）→ 在 ALLOWED 里登记条数 + 语境证据 + 理由，别默默抄一份。'
  );
  process.exit(1);
}

const allowedTotal = [...staleCountByFile.entries()]
  .filter(([rel]) => ALLOWED.has(rel))
  .reduce((n, [, c]) => n + c, 0);
console.log(
  `${TAG} OK: ${consumers.length} 个含支付类型的源文件全部走单一事实源；` +
    `${allowedTotal} 处例外已在 ALLOWED 登记（条数+证据+理由）`
);
