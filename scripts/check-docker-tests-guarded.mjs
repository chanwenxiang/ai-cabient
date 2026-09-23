#!/usr/bin/env node
/**
 * 「需要 Docker 的测试必须真跑」—— CI 守卫的**接线**门禁（静态，不需要构建产物）。
 *
 * 背景（2026-09-19 取证）：
 *   带 `@Testcontainers(disabledWithoutDocker = true)` 的测试类，在**没有 Docker** 的环境里
 *   会被 JUnit **整个类禁用**：surefire 照旧写出一份 **0 条用例**的报告，`mvn` 照样
 *   SUCCESS —— 这正是 O7 那类「假绿」的温床。CI 里靠两个 `no silent skip` 守卫把它变成红：
 *     · `build` job        —— surefire 侧，管 `*Test.java`
 *     · `integration` job  —— failsafe 侧，管 `*IT.java`
 *
 *   两个守卫**都按源码自动发现**（不写死名单）。所以本门禁要守的不是「名单是否漏了谁」，
 *   而是**「那套自动发现还在不在」**：只要有人把扫描逻辑删成白名单、或删掉整个守卫步骤，
 *   新增的 @Testcontainers 类就会**静默不被任何守卫覆盖** —— 无 Docker 时 0 条用例、全绿。
 *
 *   实测教训：升级前 `build` job 的守卫是**手写名单**（只列 3 个 E2E + 1 个权限漂移类），
 *   而仓库里带 `@Testcontainers` 的 `*Test.java` 有 **5** 个 ⇒
 *   `ReconciliationIntegrationTest`、`WeChatNotifyIntegrationTest` 从来没被覆盖过。
 *   白名单的失效形态是①「没人调」的变体：新加一个类，没有任何东西会提醒你。
 *
 * 三条规则：
 *   1. 每个 @Testcontainers 类都必须在 GUARDED 清单里**显式登记**，写明它由哪个 job 守、
 *      凭什么会被真跑。源码里新增而清单里没有 ⇒ 红。
 *   2. 清单里登记的类若在源码里已找不到 ⇒ 红（清单会随重构腐烂成漂亮的谎话）。
 *   3. 清单引用的每个 job，其**真正会执行的命令文本**（剥掉 shell 注释后）必须仍含对应的
 *      自动发现锚点 ⇒ 否则红。⚠️ 必须剥注释：build 守卫的注释里就写着 `@Testcontainers`，
 *      在整份文件里搜关键词是恒真的（与 §11.6 形态③同源）。
 *
 *   node scripts/check-docker-tests-guarded.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-docker-tests-guarded]';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

// ── 扫描源码：行首（允许缩进）的 @Testcontainers 才算真的启用了 Testcontainers ──────
// 只认 `^\s*@Testcontainers` —— 注释里提到这个词不算（否则「注释里举个例子」就能骗过判据）。
const JAVA_ANNOTATION = /^[ \t]*@Testcontainers\b/m;

function walkJava(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'target' || entry.name === 'build') continue;
      walkJava(full, out);
    } else if (entry.name.endsWith('.java')) {
      out.push(full);
    }
  }
  return out;
}

/** 只保留 `<module>/src/test/java` 下的测试源码。 */
function testSources() {
  const roots = ['services', 'edge'].map((d) => join(root, d)).filter(existsSync);
  const all = roots.flatMap((d) => walkJava(d));
  return all.filter((f) => relative(root, f).replace(/\\/g, '/').includes('/src/test/java/'));
}

// ── 清单：每个 @Testcontainers 类登记「谁守它、凭什么它会被真跑」 ─────────────────
// key   = 相对仓库根的路径（正斜杠）
// value = { job: 'build' | 'integration', why: 人话依据 }
const GUARDED = new Map([
  [
    'services/device-service/src/test/java/com/aicabinet/device/mqtt/EdgeCloudMqttIntegrationIT.java',
    {
      job: 'integration',
      why: 'failsafe 跑（*IT.java）；integration job 守卫按源码自动扫全部 *IT.java，逐份断 <testcase> ≥ 1'
    }
  ],
  [
    'services/device-service/src/test/java/com/aicabinet/device/mqtt/EmqxSharedSubscriptionIT.java',
    {
      job: 'integration',
      why: '同左：failsafe + integration job 守卫的自动扫（EMQX $share 组只投递一次那条契约）'
    }
  ],
  [
    'services/trade-service/src/test/java/com/aicabinet/trade/e2e/AdminE2ETest.java',
    {
      job: 'build',
      why: 'surefire 跑；build job 守卫按源码自动扫全部带 @Testcontainers 的 *Test.java'
    }
  ],
  [
    'services/trade-service/src/test/java/com/aicabinet/trade/e2e/ConsumerE2ETest.java',
    { job: 'build', why: '同左：surefire + build job 守卫的自动扫' }
  ],
  [
    'services/trade-service/src/test/java/com/aicabinet/trade/e2e/MerchantE2ETest.java',
    { job: 'build', why: '同左：surefire + build job 守卫的自动扫' }
  ],
  [
    'services/trade-service/src/test/java/com/aicabinet/trade/integration/ReconciliationIntegrationTest.java',
    {
      job: 'build',
      why: 'surefire 跑（`*Test.java`，failsafe 只抓 `*IT.java`）；2026-09-19 前**不在任何守卫名单里**，本门禁的由来'
    }
  ],
  [
    'services/trade-service/src/test/java/com/aicabinet/trade/integration/WeChatNotifyIntegrationTest.java',
    { job: 'build', why: '同左：surefire + build job 守卫的自动扫' }
  ],
  [
    'services/trade-service/src/test/java/com/aicabinet/trade/integration/AdminDataManageBindingTest.java',
    {
      job: 'build',
      why:
        'surefire 跑（`*Test.java`）；守通用数据管理（ops/admin/data）的**参数类型绑定**：' +
        'URL 里的 id 与 JSON 里的值都来自 Java String，未按列类型 CAST 时 `bigint = varchar` ⇒ 42883 ⇒ 500'
    }
  ],
  [
    'services/trade-service/src/test/java/com/aicabinet/trade/integration/AdminDataManageColumnContractTest.java',
    {
      job: 'build',
      why:
        '同左：surefire + build job 守卫的自动扫；守通用数据管理的**列名/键名契约**：' +
        '编辑框预填若用接口 DTO 驼峰名，写入侧（只认 information_schema 的 snake_case）必判「未知列」⇒ 保存必 400'
    }
  ]
]);

// ── 规则 3 的锚点：必须在**会执行的命令**里出现，不在注释里 ───────────────────────
// 锚点选 `find … -name '<模式>'` 这个形式，理由（都是实测踩出来的）：
//   · 用**短关键词**（如 `@Testcontainers`）会被**同 job 的 echo 文案**命中 —— 守卫里有
//     `echo "…带 @Testcontainers 的 *Test.java 都没扫到…"`，删掉真正的扫描行之后它照样匹配
//     ⇒ 锚点看似在、判据其实恒真（A/B 里"漂移③"第一版就是这样假绿的）。
//   · `-name '…'` 是 shell 语法，只可能出现在真命令里，文案不会写它。
// `surefire-reports` / `failsafe-reports` 作为第二锚点，钉住"确实去读了产物报告"。
// 另一个理由：注释里也**恰好**会出现这些模式名，所以下面仍必须剥注释（防御性）。
const JOB_ANCHORS = {
  build: {
    label: 'build job（surefire / *Test.java）',
    anchors: ["-name '*Test.java'", 'surefire-reports'],
    hint: 'build job 的守卫必须仍按源码扫 `*Test.java` 并读 surefire 报告'
  },
  integration: {
    label: 'integration job（failsafe / *IT.java）',
    anchors: ["-name '*IT.java'", 'failsafe-reports'],
    hint: 'integration job 的守卫必须仍按源码扫 `*IT.java` 并读 failsafe 报告'
  }
};

/** 取某 job 的 YAML 文本块（顶层 job 名到下一个顶层 job 名之间）。 */
function jobBlock(yamlText, jobName) {
  const start = new RegExp(`^  ${jobName}:\\s*$`, 'm').exec(yamlText);
  if (!start) return null;
  const rest = yamlText.slice(start.index + start[0].length);
  const next = /^  [A-Za-z0-9_-]+:\s*$/m.exec(rest);
  return next ? rest.slice(0, next.index) : rest;
}

/** 剥掉**整行** shell 注释 —— 守卫的说明文字里就含有被校验的关键词。 */
function stripShellLineComments(block) {
  return block
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');
}

// ── 执行 ────────────────────────────────────────────────────────────────────
const ciPath = join(root, '.github', 'workflows', 'ci.yml');
if (!existsSync(ciPath)) fail('缺少 .github/workflows/ci.yml —— 门禁锚点已失效');
const ci = readFileSync(ciPath, 'utf8');

const sources = testSources();
if (sources.length < 10) {
  fail(`只解析出 ${sources.length} 个测试源文件（预期远大于此）—— 扫描路径已失效，请同步本脚本`);
}

const discovered = sources
  .filter((f) => JAVA_ANNOTATION.test(readFileSync(f, 'utf8')))
  .map((f) => relative(root, f).replace(/\\/g, '/'))
  .sort();

// 自身护栏：两类扫描都必须非空，否则「零发现 ⇒ 全绿」是恒真的。
const its = discovered.filter((p) => p.endsWith('IT.java'));
const tests = discovered.filter((p) => !p.endsWith('IT.java'));
if (its.length < 1 || tests.length < 1) {
  fail(
    `@Testcontainers 发现数异常（*IT.java=${its.length}, *Test.java=${tests.length}）—— ` +
      `仓库里两类明明都有；多半是扫描锚点被改坏，本门禁已失去判别力`
  );
}

const problems = [];

// 规则 1：源码有、清单无 ⇒ 新类没被任何守卫覆盖。
const unregistered = discovered.filter((p) => !GUARDED.has(p));
for (const p of unregistered) {
  problems.push(
    `${p}：带 @Testcontainers 但未在 GUARDED 清单登记。\n` +
      `      它会不会真跑？缺 Docker 时它会被静默跳过（0 条用例、mvn 仍绿）。\n` +
      `      请确认它落到哪个 job（${p.endsWith('IT.java') ? '`*IT.java` ⇒ integration/failsafe' : '`*Test.java` ⇒ build/surefire'}），` +
      `然后在 GUARDED 里补一条并写明依据`
  );
}

// 规则 2：清单有、源码无 ⇒ 清单腐烂。
for (const p of GUARDED.keys()) {
  if (!existsSync(join(root, p))) {
    problems.push(
      `${p}：GUARDED 清单里有、源码里已找不到 —— 该条目已失效（改名/搬走/删除）。请同步清理`
    );
  }
}

// 规则 3：清单引用的 job，其可执行命令里必须仍有自动发现锚点。
const usedJobs = [...new Set([...GUARDED.values()].map((v) => v.job))].sort();
for (const job of usedJobs) {
  const spec = JOB_ANCHORS[job];
  if (!spec) {
    problems.push(`GUARDED 引用了未定义的 job '${job}'（JOB_ANCHORS 里没有）—— 请同步本脚本`);
    continue;
  }
  const raw = jobBlock(ci, job);
  if (raw === null) {
    problems.push(`ci.yml 里找不到 job '${job}' —— 守卫已消失，清单里引用它的条目全部失效`);
    continue;
  }
  const code = stripShellLineComments(raw);
  const gone = spec.anchors.filter((a) => !code.includes(a));
  if (gone.length) {
    problems.push(
      `${spec.label} 的自动发现锚点已消失：\n    - ` +
        gone.join('\n    - ') +
        `\n      ${spec.hint}；缺了它，新增的 @Testcontainers 类不会被任何守卫覆盖`
    );
  }
}

if (problems.length) {
  fail(`需要 Docker 的测试没有被守卫覆盖：\n  - ${problems.join('\n  - ')}`);
}

console.log(
  `${TAG} OK（@Testcontainers 类 ${discovered.length} 个：*IT.java ${its.length} / *Test.java ${tests.length}；` +
    `自动发现锚点在位：${usedJobs.map((j) => JOB_ANCHORS[j].label).join('、')}）`
);
for (const [p, v] of GUARDED) console.log(`  · [${v.job}] ${p}`);
