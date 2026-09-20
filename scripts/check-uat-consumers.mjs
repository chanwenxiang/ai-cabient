#!/usr/bin/env node
/**
 * 门禁：每个 `clients/*\/tests/*-uat.mjs` 必须有一个**执行消费者**。
 *
 * 为什么需要这道门禁
 * ------------------
 * `clients/consumer-mp/tests/imp-dispute-copy-uat.mjs` **崩了半年没人发现**：它的私有登录
 * 既不关首屏隐私弹窗、也不填图形验证码，第一屏 `fillPlaceholder('请输入11位手机号')` 就
 * 被遮罩拦下（TimeoutError → exit 2），三个用例一个都没跑到。之所以能烂这么久，是因为它
 * **既不在 CI 的任何步骤里、也不在任何 package.json script 里** —— 没有任何东西会执行它，
 * 于是它的红绿状态对任何人都不产生信号，「通过」与「从没跑过」在观感上无法区分。
 *
 * 这与 `check-audit-gates-wiring.mjs` 的规则 4（`check:*` 定义了却**无人调用**）是**同一形态**：
 * 「存在」不等于「生效」。本门禁把「没人调」这件事从**靠人记得**变成**会红的判据**。
 *
 * 判据
 * ----
 * 对磁盘上每个 `*-uat.mjs`：
 *   (a) 在 `.github/workflows/*.yml` 里被 `node … <file>` 真正调用；**或**
 *   (b) 登记在 `EXEMPT` 白名单里，并附**可核对**的不接入理由。
 * 两者都没有 ⇒ 该套件是**死代码**，报红。
 *
 * 🔴 边界（必须诚实）：本门禁只保证「**有人会执行它**」，**不保证**「执行时它有数据可断言」。
 *    例如 `imp-dispute-copy-uat` 的用例是发现式的（挑不到终态争议单 → SKIP），CI 若未播种
 *    争议数据，它接入后仍可能恒 SKIP。那是**种子链路**的活（见 ci.yml 里关于
 *    `.tmp/open-dispute.json` 的已知遗留说明），不在本门禁的判据范围内 ——
 *    别把「没人调」和「调了没数据」混成一条判据，否则两边都判不准。
 *
 * 双向（防止白名单自己漂）
 * ------------------------
 * ① 磁盘套件未接入 CI 且未登记 EXEMPT ⇒ 红。
 * ② EXEMPT 条目已不在磁盘（重命名 / 删除后残留）⇒ 红（僵尸条目）。
 * ③ EXEMPT 条目**却又**在 CI 被调用 ⇒ 红（豁免过期：已接入却没删豁免，
 *    下一个人会以为它没在跑）。
 * ④ EXEMPT 的 `ciAnchor` 若在 CI 原文里消失 ⇒ 红（它豁免所依据的那段解释被删了）。
 * ⑤ CI 里调用的 `*-uat.mjs` 必须在磁盘存在 ⇒ 否则红（步骤会直接模块找不到）。
 * 🔴 同源教训：`check-uat-selectors` 的 `SUITES` 曾是手写清单、漏了 4 个套件而无人发现。
 *    **手写清单必漂** —— 所以白名单必须有反向校验，且锚点一律用**命令串/短语**，
 *    **绝不用行号**（`check-audit-gates-wiring` 里已实测行号会漂到无关内容）。
 *
 * 豁免的理由必须可核对
 * --------------------
 * `why` 不是「写起来麻烦」的免责声明，而是**指向仓库里客观事实**的说明：指向 ci.yml 里那段
 * 解释、或脚本自身的行为（如「会真的对外投递消息」）。每一条都要能被下一个人复核。
 *
 * ── CI 调用检测的口径 ──
 * 只看「`node` 后面跟一个以 `-uat.mjs` 结尾的路径」这一形式（`run: VAR=x node …` 也算）。
 * 间接调用（如某 `pnpm` script 内部再调它）**不在范围内** —— 本门禁守的是「CI 实际会跑它」。
 * 用 **basename** 匹配（`consumer-h5-uat.mjs`）：CI 步骤带 `working-directory`，
 * 相对路径的目录前缀不固定，只有文件名是稳定的。
 *
 * 用法：node scripts/check-uat-consumers.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 未接入 CI 的套件 → 不接入理由。key 用 basename（与 CI 匹配口径一致）。
 * 纪律：只放「**客观不该/不能在 CI 跑**」的套件；「接入麻烦」不构成理由 —— 那是该补的活。
 */
const EXEMPT = new Map([
  [
    'admin-uat.mjs',
    {
      why: '依赖 CI 未播种的采购单/订单等业务数据；ci.yml 的注释写明「待其种子脚本补齐后按同样方式接入」',
      ciAnchor: '三个 admin 侧脚本（admin-uat / role-regression / batch-imp）依赖采购单等业务数据'
    }
  ],
  [
    'role-regression-uat.mjs',
    {
      why: '同上：需要采购/财务/拣货等业务数据（CI 未播种），断言的是角色权限矩阵',
      ciAnchor: '三个 admin 侧脚本（admin-uat / role-regression / batch-imp）依赖采购单等业务数据'
    }
  ],
  [
    'batch-imp-uat.mjs',
    {
      why: '同上：需要采购单等业务数据（CI 未播种）',
      ciAnchor: '三个 admin 侧脚本（admin-uat / role-regression / batch-imp）依赖采购单等业务数据'
    }
  ],
  [
    'admin-alert-channel-uat.mjs',
    {
      why:
        '会**真的向已配置的告警渠道（飞书等）投递一条测试消息**（脚本 :186 的注释明说），' +
        '并需要真实 webhook 凭据 —— 外部副作用，不适合被 CI 的每个 PR 触发'
    }
  ]
]);

/** 发现磁盘上的 `clients/*\/tests/*-uat.mjs`（发现式，避免手写清单悄悄落后于磁盘）。 */
function discoverSuites() {
  const out = [];
  const clientsDir = path.join(ROOT, 'clients');
  if (!fs.existsSync(clientsDir)) return out;
  for (const client of fs.readdirSync(clientsDir, { withFileTypes: true })) {
    if (!client.isDirectory()) continue;
    const testsDir = path.join(clientsDir, client.name, 'tests');
    if (!fs.existsSync(testsDir)) continue;
    for (const f of fs.readdirSync(testsDir)) {
      if (/-uat\.mjs$/.test(f)) out.push({ rel: `clients/${client.name}/tests/${f}`, base: f });
    }
  }
  return out.sort((a, b) => a.rel.localeCompare(b.rel));
}

/** 从 CI 工作流里提取「被 node 执行的 *-uat.mjs」：basename → 出现位置列表。 */
function collectCiInvocations() {
  const dir = path.join(ROOT, '.github', 'workflows');
  const invocations = new Map();
  let raw = '';
  if (!fs.existsSync(dir)) return { invocations, raw };
  const files = fs
    .readdirSync(dir)
    .filter((x) => /\.ya?ml$/.test(x))
    .sort();
  for (const f of files) {
    const text = fs.readFileSync(path.join(dir, f), 'utf8');
    raw += text + '\n';
    // 🔴 `split(/\r?\n/)`：CRLF 工作区里按 `\n` 切会留下行尾 `\r`，使带 `$` 锚定的判据整片空转。
    text.split(/\r?\n/).forEach((ln, i) => {
      for (const m of ln.matchAll(/\bnode\s+(\S*-uat\.mjs)/g)) {
        const base = path.basename(m[1]);
        if (!invocations.has(base)) invocations.set(base, []);
        invocations.get(base).push({ file: `.github/workflows/${f}`, line: i + 1, cmd: ln.trim() });
      }
    });
  }
  return { invocations, raw };
}

let failed = false;
const suites = discoverSuites();
const diskBases = new Set(suites.map((s) => s.base));
const { invocations, raw } = collectCiInvocations();

// 锚点自检：解析不到任何 CI 调用 ⇒ 提取口径可能已被重写，本门禁当前是**空转**的（假绿）。
if (invocations.size === 0) {
  failed = true;
  console.error(
    '✗ 在 .github/workflows 里没有解析到任何 `node … *-uat.mjs` 调用。\n' +
      '    锚点可能已被重写（CI 步骤形式变了），本门禁当前是**空转**的（会假绿）。请先修提取口径。'
  );
}

const ciCalled = [];
const exempted = [];

// ① 磁盘每个套件必须「接入 CI」或「登记豁免」
for (const s of suites) {
  const inv = invocations.get(s.base);
  const exempt = EXEMPT.get(s.base);

  if (inv && exempt) {
    failed = true;
    console.error(
      `✗ ${s.rel}\n` +
        `    既在 CI 里被调用（${inv.map((x) => `${x.file}:${x.line}`).join('、')}），又登记在 EXEMPT。\n` +
        `    ⇒ 豁免已过期：已接入却没删豁免，下一个人会误以为它没在跑。请删掉该 EXEMPT 条目。`
    );
    continue;
  }
  if (inv) {
    ciCalled.push(`${s.rel} ← ${inv[0].file}:${inv[0].line}`);
    continue;
  }
  if (exempt) {
    if (exempt.ciAnchor && !raw.includes(exempt.ciAnchor)) {
      failed = true;
      console.error(
        `✗ ${s.rel}\n` +
          `    EXEMPT 的 CI 锚点已不存在于工作流原文：「${exempt.ciAnchor}」\n` +
          `    豁免理由（${exempt.why}）所依据的那段解释已被删除 ⇒ 要么把说明补回 CI，要么把这个套件接进 CI。`
      );
      continue;
    }
    exempted.push(`${s.rel} ← ${exempt.why}`);
    continue;
  }
  failed = true;
  console.error(
    `✗ ${s.rel}\n` +
      `    **没有任何东西执行它**：既不在 .github/workflows 里被 node 调用，也不在 EXEMPT 白名单。\n` +
      `    ⇒ 它是死代码：失败不会被任何人看到，于是「通过」与「从没跑过」观感上无法区分\n` +
      `      （imp-dispute-copy-uat 就这样烂了半年）。\n` +
      `    请把它接进 CI 的相应步骤；确实不该进 CI（外部副作用 / 依赖未播种数据）则登记 EXEMPT 并写明可核对理由。`
  );
}

// ② EXEMPT 僵尸条目
for (const base of EXEMPT.keys()) {
  if (!diskBases.has(base)) {
    failed = true;
    console.error(`✗ EXEMPT 条目 ${base} 已不在磁盘（重命名/删除后请同步白名单，别留僵尸条目）。`);
  }
}

// ⑤ CI 里调用的 *-uat.mjs 必须真实存在（防 typo：步骤会直接 ModuleNotFound）
for (const [base, list] of invocations) {
  if (!diskBases.has(base)) {
    failed = true;
    console.error(
      `✗ CI 调用了不存在的套件 ${base}（${list
        .map((x) => `${x.file}:${x.line}`)
        .join('、')}）—— 该步骤会直接报模块找不到。`
    );
  }
}

if (failed) {
  console.error(
    '\n::error::存在没有执行消费者的 UAT 套件（或豁免清单已漂）。' +
      '没人调的套件 = 死代码：它的失败不会被任何人看到，也就等于没有被验证' +
      '（对照 check-audit-gates-wiring 规则 4：`check:*` 定义了却无人调用）。' +
      '请接入 CI，或登记 EXEMPT 并写明可核对理由。'
  );
  process.exit(1);
}
console.log(
  `\nPASS check-uat-consumers：${suites.length} 个 UAT 套件全部有执行消费者` +
    `（CI 调用 ${ciCalled.length} 个、豁免 ${exempted.length} 个且均附可核对理由）。`
);
