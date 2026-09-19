#!/usr/bin/env node
/**
 * 防回归门禁自身的「接线」门禁 —— 守着看门人。
 *
 * 背景（第十轮续，CI 实测红）：`check:audit-gates` 是一串 `pnpm a && pnpm b && …`。
 * 把新门禁**只写进聚合链、忘了在 package.json 里定义它自己的 script** 时：
 *
 *   ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL  Command "check:xxl-job-wiring" not found
 *   ELIFECYCLE  Command failed with exit code 254.
 *
 * 两个后果：① CI 直接红；② 断点**之后**的门禁在 CI 里从未执行过 ——
 * 「已接入 CI」只停留在纸面（与 R1「新门禁没接 CI = 等于没有」同源，只是断点位置不同）。
 * 本机因为 `pnpm` 包装器坏、只能逐个 `node scripts/xxx.mjs` 跑，反而看不出聚合链是断的，
 * 所以必须静态校验。
 *
 * 四条规则：
 *   1. 聚合链里出现的每个 `pnpm <name>` 都必须在 package.json scripts 里定义。
 *   2. 每个 scripts/check-*.mjs 都必须被接线：出现在某条 script 命令里，或被 CI 工作流直接调用。
 *   3. CI 工作流里 `node scripts/xxx.mjs` 引用的文件必须真实存在。
 *   4. 每个 `check:*` 脚本都必须**可达**（聚合链 / CI / 被可达脚本传递引用）。
 *      规则 2 只证明「文件名被某条 script 命令提到过」——而定义 `check:foo` 的那条命令
 *      自己就提到了它，所以规则 2 对「定义了却无人调用」是恒真的（与规则 1 同源）。
 *      第九轮的 P0 就是这一形态的高阶版：门禁写进了聚合链，但聚合链在 CI 里断了，
 *      后续门禁「已接入」只停留在纸面。规则 4 用可达性闭包把它钉死。
 *
 *   node scripts/check-audit-gates-wiring.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-audit-gates-wiring]';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const pkgPath = join(root, 'package.json');
if (!existsSync(pkgPath)) fail('找不到 package.json');
const pkgRaw = readFileSync(pkgPath, 'utf8');
const pkg = JSON.parse(pkgRaw);
const scripts = pkg.scripts || {};

const AGGREGATE = 'check:audit-gates';
if (!scripts[AGGREGATE]) fail(`package.json 里没有 ${AGGREGATE} 脚本`);
const aggregate = scripts[AGGREGATE];

const workflowDir = join(root, '.github', 'workflows');
const workflows = existsSync(workflowDir)
  ? readdirSync(workflowDir).filter((f) => /\.ya?ml$/.test(f))
  : [];
if (workflows.length === 0) fail('.github/workflows 下没有工作流文件，门禁已失效');
const workflowRaw = workflows.map((f) => readFileSync(join(workflowDir, f), 'utf8')).join('\n');

const problems = [];

// ── 规则 1：聚合链引用的脚本必须存在 ───────────────────────────────────────
const refs = [...aggregate.matchAll(/pnpm\s+([A-Za-z0-9:_-]+)/g)].map((m) => m[1]);
if (refs.length < 5) {
  fail(`从 ${AGGREGATE} 只解析出 ${refs.length} 个 pnpm 引用，锚点可能已被重写`);
}
const undefinedRefs = refs.filter((name) => !scripts[name]);
if (undefinedRefs.length) {
  problems.push(
    `聚合链引用了 package.json 里不存在的 script（CI 会 ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL，` +
      `且断点之后的门禁在 CI 从未执行）：${undefinedRefs.join(', ')}`
  );
}

// ── 规则 2：每个 check-*.mjs 必须被接线 ────────────────────────────────────
const scriptsDir = join(root, 'scripts');
const gateFiles = readdirSync(scriptsDir).filter((f) => /^check-.*\.mjs$/.test(f));
if (gateFiles.length === 0) fail('scripts/ 下没有 check-*.mjs，门禁已失效');

const pkgCommands = Object.values(scripts).join('\n');
const unwired = gateFiles.filter((f) => !pkgCommands.includes(f) && !workflowRaw.includes(f));
if (unwired.length) {
  problems.push(
    `门禁脚本没有任何接线（既不在 package.json 命令里，也不被 CI 调用）= 等于没有：\n    - ` +
      unwired.join('\n    - ')
  );
}

// ── 规则 4：每个 check:* 脚本必须可达（「定义了」≠「有人调」） ──────────────
// 豁免名单 = **本地/手工入口**，其等价步骤已由 CI 用内联步骤覆盖。
// 豁免本身是有代价的：「CI 里到底还有没有那一步」如果没人管，某天 CI 删掉它、
// 这里照样豁免 ⇒ 豁免退化成假绿。所以豁免必须**自带可验证锚点**：
// ciAnchors 必须是 CI 工作流原文里真实存在的命令串，由规则 5 逐条校验。
// 🔴 锚点一律用**命令串**，不用行号 —— 早期版本写成 `ci.yml:297` 这类硬编码行号，
// 只要在它之前插入任何一行就会悄悄过期并指向无关内容（实测已漂到 147 行之外），
// 那是「信号在骗读者」，比没有锚点更坏。
const LOCAL_ONLY = new Map([
  // pnpm 包装器在本机坏（corepack 路径丢失），本地只能用这个 runner 逐脚本跑。
  [
    'check:audit-gates:local',
    {
      why: '本地聚合 runner；CI 用 pnpm check:audit-gates',
      ciAnchors: ['pnpm check:audit-gates']
    }
  ],
  // 本地「一把梭」别名；CI 把它的三个组成部分拆成独立步骤，未跑这个别名本身。
  [
    'check:shared',
    {
      why: '本地别名；CI 已内联其三个步骤',
      ciAnchors: [
        'pnpm build:packages',
        'pnpm --filter @aicabinet/shared-rbac test',
        'pnpm check:nav-perms'
      ]
    }
  ]
]);

const checkScriptNames = Object.keys(scripts).filter((k) => k.startsWith('check:'));
if (checkScriptNames.length < 5) {
  fail(`package.json 里只有 ${checkScriptNames.length} 个 check:* 脚本，锚点可能已被重写`);
}

// 入口 1：聚合链显式引用
const reached = new Set(refs.filter((n) => checkScriptNames.includes(n)));
// 入口 2：CI 里 `pnpm [run] check:xxx`
for (const m of workflowRaw.matchAll(/pnpm\s+(?:run\s+)?(check:[A-Za-z0-9:_-]+)/g)) {
  if (checkScriptNames.includes(m[1])) reached.add(m[1]);
}
// 入口 3：CI 里 `node scripts/check-xxx.mjs`（不经过 pnpm 的直接调用）
for (const m of workflowRaw.matchAll(/scripts\/(check-[A-Za-z0-9_.-]+\.mjs)/g)) {
  for (const name of checkScriptNames) {
    if ((scripts[name] || '').includes(m[1])) reached.add(name);
  }
}
// 传递闭包：被可达脚本内部 `pnpm <name>` 引用的脚本同样算已接线
for (let guard = 0; guard < 20; guard++) {
  const before = reached.size;
  for (const name of [...reached]) {
    for (const m of (scripts[name] || '').matchAll(/pnpm\s+(?:run\s+)?(check:[A-Za-z0-9:_-]+)/g)) {
      if (checkScriptNames.includes(m[1])) reached.add(m[1]);
    }
  }
  if (reached.size === before) break;
}

const unreachable = checkScriptNames.filter((n) => !reached.has(n) && !LOCAL_ONLY.has(n));
if (unreachable.length) {
  problems.push(
    `check:* 脚本已定义但**无人调用**（聚合链、CI、以及被调用脚本里都找不到）= 等于没有：\n    - ` +
      unreachable.map((n) => `${n}  (${scripts[n]})`).join('\n    - ')
  );
}

// ── 规则 5：豁免条目的 CI 锚点必须真实存在 ────────────────────────────────
// 豁免 = 「本地专用，等价步骤已在 CI 覆盖」。锚点若在 CI 里消失，该脚本就既没人调、
// 也无 CI 替代 —— 豁免退化成假绿。逐条在**工作流原文**里查命令串（不是查行号）。
for (const [name, { why, ciAnchors }] of LOCAL_ONLY) {
  const gone = ciAnchors.filter((a) => !workflowRaw.includes(a));
  if (gone.length) {
    problems.push(
      `豁免 ${name}（${why}）的 CI 替代步骤已不存在（豁免变假绿：要么把步骤补回 CI，要么去掉豁免）：\n    - ` +
        gone.join('\n    - ')
    );
  }
}

// ── 规则 3：CI 直接调用的脚本文件必须存在 ─────────────────────────────────
const missingFiles = new Set();
for (const m of workflowRaw.matchAll(/scripts\/([A-Za-z0-9_.-]+\.(?:mjs|ps1|py|sh))/g)) {
  const rel = join('scripts', m[1]);
  if (!existsSync(join(root, rel.replace(/\\/g, '/')))) {
    missingFiles.add(rel);
  }
}
if (missingFiles.size) {
  problems.push(
    `CI 工作流引用了不存在的脚本文件（步骤会直接报 ModulNotFound）：\n    - ` +
      [...missingFiles].join('\n    - ')
  );
}

if (problems.length) {
  fail(`\n  ${problems.join('\n  ')}\n`);
}

console.log(
  `${TAG} OK：聚合链 ${refs.length} 个引用全部有定义，${gateFiles.length} 个 check-*.mjs 全部已接线，` +
    `CI 引用的脚本文件均存在，${checkScriptNames.length} 个 check:* 脚本中 ${reached.size} 个可达` +
    `${LOCAL_ONLY.size ? `，另 ${LOCAL_ONLY.size} 个豁免本地专用（${[...LOCAL_ONLY.keys()].join(', ')}，CI 锚点均已存在）` : ''}`
);
