#!/usr/bin/env node
/**
 * 「Android 端单测必须真的被 CI 跑」—— 接线门禁（静态，不需要 Android SDK）。
 *
 * 背景（2026-09-19 取证）：
 *   `edge/android-app` 是**唯一零测试资产的端**（23 个 `.kt`、`app/src/test` 目录都不存在）。
 *   长期结论是「不可构建」——因为本机既没有 `ANDROID_HOME` 也没有 `gradlew`，
 *   连 `mvn test` 都够不到它（Gradle Kotlin DSL，不在 Maven reactor 里）。
 *   实际取证后情况是：AGP 8.2.2 / Kotlin 插件 / Gradle 8.9 发行版**都在 Gradle 缓存里**，
 *   缺的只有 SDK 本体 ⇒ 装上就能跑（详见 docs/evidence 对应批次）。
 *
 *   但「跑得起来」与「有人守着」是两件事。本门禁守的是后者的四条失效路径：
 *     ① 测试文件被删/挪走，CI 那条 gradle 命令仍然 exit 0（NO-SOURCE 不是失败）；
 *     ② 任务名里的 flavor 与 `productFlavors` 分叉（flavor 改名后 gradle 会直接报任务不存在，
 *        但若有人「顺手」把 CI 改成不带 flavor 的 `test`，就变成跑了个空集）；
 *     ③ 测试运行依赖（junit）从 `testImplementation` 里被移走 —— 编译期就红，属于好的红；
 *     ④ **最危险的一条**：CI 只看 gradle 退出码。本项目已吃过这个亏（§11.16 O7）：
 *        `Tests run: 0` 与 XML 根 `tests="0"` 在 @Nested／集成测试类上都会写 0，
 *        「Failures: 0, Errors: 0, Skipped: 0」在**一条都没跑**时照样绿。
 *        ⇒ 判据必须落在**测试报告里 `<testcase>` 元素的个数**上。
 *
 * 判据全部落在真值上（不搜关键词）：
 *   · 真的去数 `app/src/test/**` 下的文件与 `@Test` 注解；
 *   · 真的从 `build.gradle.kts` 解析 `productFlavors`，再用它去核对 CI 里的任务名；
 *   · CI 侧先剥掉整行 shell 注释，再找**可执行的** gradle 命令；
 *   · 覆盖面自动跟随 flavor 改名，而不是写死 `testMockDebugUnitTest`。
 *
 *   node scripts/check-android-tests-wired.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-android-tests-wired]';

const APP_DIR = join(root, 'edge', 'android-app');
const MODULE_GRADLE = join(APP_DIR, 'app', 'build.gradle.kts');
const TEST_ROOT = join(APP_DIR, 'app', 'src', 'test');
const CI_PATH = join(root, '.github', 'workflows', 'ci.yml');
/** 测试报告的落盘根（AGP 固定路径），CI 的判据必须从这里取数。 */
const REPORT_GLOB = 'app/build/test-results';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

/** 递归收集 `*.kt`，跳过构建产物目录。 */
function collectKt(dir, base = dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (['build', '.gradle', 'node_modules'].includes(entry.name)) continue;
      collectKt(join(dir, entry.name), base, out);
    } else if (entry.name.endsWith('.kt')) {
      out.push(relative(base, join(dir, entry.name)).replace(/\\/g, '/'));
    }
  }
  return out;
}

/** 取某 job 的 YAML 文本块（顶层 job 名到下一个顶层 job 名之间）。 */
function jobBlock(yamlText, jobName) {
  const start = new RegExp(`^  ${jobName}:\\s*$`, 'm').exec(yamlText);
  if (!start) return null;
  const rest = yamlText.slice(start.index + start[0].length);
  const next = /^  [A-Za-z0-9_-]+:\s*$/m.exec(rest);
  return next ? rest.slice(0, next.index) : rest;
}

/**
 * 剥掉整行 shell 注释：注释里常写着要被校验的命令串，
 * 直接在整个文本里搜关键词会恒真（本项目已固化的「形态③」）。
 */
function stripShellLineComments(block) {
  return block
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');
}

/**
 * 剥掉 Kotlin 的行注释与块注释。
 *
 * 为什么必须剥（2026-09-19 本批踩到）：旧版规则 1 用 `/@Test\b/.test(file)` 在**整个文件**里搜，
 * 而本批新建的 `EdgeRuntimeConfigPrefsTest.kt` 里有一句普通注释
 *   `// 每个用例从干净 prefs 起步：Robolectric 每个 @Test 会重建 Application。`
 * —— 只靠这句注释，一个**一个用例都没有**的文件也能让门禁判「有 @Test」。
 * 这与本门禁**自己**在文件头警告过的「形态③：搜关键词 ⇒ 注释也算」是同一类错误。
 * 判据必须落在**真正会被编译的那几行**上。
 *
 * 实现按字符扫描而非正则，避免把字符串字面量里的 `//` 误当注释起点
 * （如 `assertTrue("/api/v2/orders".startsWith("/"))`）。
 */
function stripKotlinComments(src) {
  let out = '';
  let i = 0;
  let inLine = false;
  let inBlock = false;
  let inString = false;
  let inChar = false;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (inLine) {
      if (c === '\n') {
        inLine = false;
        out += c;
      }
      i += 1;
      continue;
    }
    if (inBlock) {
      if (c === '*' && n === '/') {
        inBlock = false;
        i += 2;
      } else {
        if (c === '\n') out += c;
        i += 1;
      }
      continue;
    }
    if (inString) {
      out += c;
      if (c === '\\') {
        out += n ?? '';
        i += 2;
        continue;
      }
      if (c === '"') inString = false;
      i += 1;
      continue;
    }
    if (inChar) {
      out += c;
      if (c === '\\') {
        out += n ?? '';
        i += 2;
        continue;
      }
      if (c === "'") inChar = false;
      i += 1;
      continue;
    }
    if (c === '/' && n === '/') {
      inLine = true;
      i += 2;
      continue;
    }
    if (c === '/' && n === '*') {
      inBlock = true;
      i += 2;
      continue;
    }
    if (c === '"') inString = true;
    if (c === "'") inChar = true;
    out += c;
    i += 1;
  }
  return out;
}

/** 真值判据：文件里是否真有 `@Test` **注解**（注释不算）。 */
function hasRealTestAnnotation(file) {
  return /@Test\b/.test(stripKotlinComments(readFileSync(join(TEST_ROOT, file), 'utf8')));
}

// ── 前置锚点自检：缺任何一个都说明门禁已失去判别力 ──────────────────────────────
if (!existsSync(APP_DIR)) fail('缺少 edge/android-app —— 扫描锚点已失效');
if (!existsSync(MODULE_GRADLE)) fail('缺少 edge/android-app/app/build.gradle.kts —— 锚点已失效');
if (!existsSync(CI_PATH)) fail('缺少 .github/workflows/ci.yml —— 锚点已失效');

const problems = [];

// ── 规则 1：必须真有测试源集，且真有 @Test（注解，不是注释里的字样）────────────
const testFiles = collectKt(TEST_ROOT);
const filesWithRealTests = [];
if (testFiles.length === 0) {
  problems.push('edge/android-app/app/src/test 下一个 *.kt 都没有 —— 这一端等于没有单测资产');
} else {
  for (const f of testFiles) if (hasRealTestAnnotation(f)) filesWithRealTests.push(f);
  if (filesWithRealTests.length === 0) {
    problems.push(
      `测试源集有 ${testFiles.length} 个文件，但没有一个含 @Test 注解（注释里的字样不算，等于空跑）`
    );
  }
}

// ── 规则 2：测试运行时依赖必须声明在 testImplementation ────────────────────────
//
// ⚠️ 必须跑在**剥掉 Kotlin 注释**的文本上（与规则 1 同理）。
//    2026-09-19 由漂移用例 G3 抓出：旧实现直接在原文里搜 `testImplementation(`，
//    于是把这行「注释掉」——`// testImplementation("junit:junit:4.13.2")` ——
//    字符串仍在文本里，规则照样通过（形态③：判据恒真）。
//    `build.gradle.kts` 本来就是 Kotlin，故复用同一个剥注释器。
const moduleGradle = readFileSync(MODULE_GRADLE, 'utf8');
const gradleCode = stripKotlinComments(moduleGradle);
if (!/testImplementation\s*\(/.test(gradleCode)) {
  problems.push(
    'app/build.gradle.kts 里没有 testImplementation(...) —— 测试源码无法编译（缺 junit 等运行依赖）'
  );
}

// ── 规则 2b：用了 Robolectric 就必须声明它的编译期依赖 ─────────────────────────
// Robolectric 不是「加个依赖就能用」，前提是 `testImplementation("org.robolectric:robolectric:…")`。
// 漏了是编译期红（好的红），但只在**真的编译到测试源**时才红 —— 而 NO-SOURCE 的路径
// （任务名写错、源集被挪走）根本编译不到，所以这条静态规则仍有价值。
//
// ⚠️ 曾经还有一条「必须配 `unitTests.isIncludeAndroidResources = true`」，**已删除**：
//    2026-09-19 实测（去掉该行后重跑）**89 例仍然全绿** —— 本仓的测试只用
//    Application Context 与 SharedPreferences，不读资源/manifest，Robolectric 无需该开关。
//    要求一个「不配也能过」的东西，就是在能跑通的配置上报红（假红），与假绿同害。
//    将来若真加了读资源的用例，那时再按当时的取证决定是否引入该开关。
const usesRobolectric = filesWithRealTests.some((f) =>
  /RobolectricTestRunner|ApplicationProvider/.test(
    stripKotlinComments(readFileSync(join(TEST_ROOT, f), 'utf8'))
  )
);
if (usesRobolectric && !/["']org\.robolectric:robolectric:/.test(gradleCode)) {
  problems.push(
    '测试源码用了 Robolectric（RobolectricTestRunner / ApplicationProvider），' +
      '但 app/build.gradle.kts 的 testImplementation 里没有 org.robolectric:robolectric 依赖'
  );
}

// ── 规则 3：解析 productFlavors，作为 CI 任务名的核对依据（自动跟随改名）────────
const flavorBlock = /productFlavors\s*\{([\s\S]*?)\n\s{4}\}/.exec(gradleCode);
if (!flavorBlock) {
  problems.push(
    'app/build.gradle.kts 里解析不到 productFlavors 块 —— 无法核对 CI 的任务名是否与 flavor 一致'
  );
}
const flavors = flavorBlock
  ? [...flavorBlock[1].matchAll(/create\(\s*"([A-Za-z0-9_]+)"\s*\)/g)].map((m) => m[1])
  : [];
if (flavorBlock && flavors.length === 0) {
  problems.push('productFlavors 块存在但解析不出任何 create("…") —— 解析锚点已漂');
}

// ── 规则 4：CI 必须真的跑 Android 单元测试任务 ────────────────────────────────
const ci = readFileSync(CI_PATH, 'utf8');
const job = jobBlock(ci, 'edge-android');
if (job === null) {
  problems.push(
    "ci.yml 里找不到 job 'edge-android' —— Android 单测没有宿主 job（失效形态①：没人调）"
  );
}
const jobCode = job === null ? '' : stripShellLineComments(job);

/** 抓出所有可执行的 gradle 调用行。不绑定具体写法，只要求「能被执行」。 */
const gradleCmdLines = jobCode
  .split('\n')
  .map((l) => l.trim())
  .filter((l) => /(^|\s)(\.\/gradlew|gradlew\b|gradle)\s/.test(l));

if (job !== null && gradleCmdLines.length === 0) {
  problems.push('edge-android job 里没有任何可执行的 gradle 命令（注释不算）—— 测试写了但没人跑');
}

/** 从任务名里认出 flavor：AGP 命名为 test<Flavor><BuildType>UnitTest。 */
function taskMatchesFlavor(task, flavor) {
  const f = flavor.charAt(0).toUpperCase() + flavor.slice(1);
  return new RegExp(`^:?app:test${f}(Debug|Release)UnitTest$`).test(task);
}

const unitTestTasks = [];
for (const line of gradleCmdLines) {
  for (const m of line.matchAll(/(:?app:test[A-Za-z0-9]*UnitTest|test[A-Za-z0-9]*UnitTest)\b/g)) {
    unitTestTasks.push(m[1].replace(/^app:/, ':app:'));
  }
}

if (job !== null && gradleCmdLines.length > 0 && unitTestTasks.length === 0) {
  problems.push(
    'edge-android job 跑了 gradle，但没有 `…UnitTest` 任务 —— 跑的是别的目标（测试并未执行）'
  );
}

for (const task of unitTestTasks) {
  if (flavors.length === 0) break;
  if (!flavors.some((fl) => taskMatchesFlavor(task, fl))) {
    problems.push(
      `CI 里的任务 \`${task}\` 与 build.gradle.kts 的 flavor ${JSON.stringify(flavors)} 对不上 —— ` +
        'flavor 改名后这条命令会直接失败，或者（更糟）被改成跑了一个空集'
    );
  }
}

// ── 规则 5：CI 必须有「测试真的跑了」的判据，且落在 <testcase> 上 ──────────────
// 判据是**结构**而非句子：既要从测试报告目录取数，又要**真的去数元素**。
// 只看 gradle 退出码会让 `Tests run: 0` 静默通过（§11.16 O7）。
//
// ⚠️ 计数判据必须锚在**会真正执行的命令**上，不能只要求文本里出现 "testcase" ——
//    本门禁第一版就是那么写的，结果被自己后面的 `echo "… testcases=$total"` 文案救活：
//    把 `grep -o '<testcase '` 换成 `grep -o 'Tests run'`（即真判据被拆掉）**仍然全绿**。
//    漂移用例 G6 抓到了这一点，故收紧为「必须存在数 `<testcase ` 的 grep 调用」。
const countsTestcase = /grep\s+-o\s+['"]<testcase\s/.test(jobCode);

if (job !== null) {
  const usesReportDir = jobCode.includes(REPORT_GLOB) || jobCode.includes('test-results');
  if (!usesReportDir && !countsTestcase) {
    problems.push(
      'edge-android job 没有任何「测试真跑了」的判据：既没读 `' +
        REPORT_GLOB +
        '`，也没对 <testcase> 元素计数 —— ' +
        'gradle 在 NO-SOURCE 时同样 exit 0，等于没测也能绿'
    );
  } else if (!countsTestcase) {
    problems.push(
      "edge-android job 读了测试报告目录，但没有对 <testcase> 元素计数（缺少 `grep -o '<testcase '` 这类调用）" +
        ' —— 只断言 Failures/Errors 为 0 在「一条都没跑」时照样绿'
    );
  } else if (!usesReportDir) {
    problems.push(
      'edge-android job 提到了 testcase，但没有从 `' +
        REPORT_GLOB +
        '` 取数 —— 判据悬空，无法确认读的是本次运行的报告'
    );
  }
}

// ── 规则 6：CI 调 gradle 的方式必须与仓库**实际具备的入口**一致 ────────────────
// 取证（2026-09-19）：本仓**不含 gradle wrapper** —— `edge/android-app/gradlew` 与
// `gradle/wrapper/gradle-wrapper.jar` 都不存在，`git ls-files` 也查不到。
// 所以 CI 里写 `./gradlew …` 只会得到 `No such file or directory`；而本批之前没有任何
// job 编译过 android-app，这条命令**从来没被执行过**，才得以留在文件里。
// 两个方向都要守：有 wrapper 则允许（不强制）用 gradlew；没有就**禁止**，且必须有一个
// `uses:` 引入 Gradle 的步骤（裸 `gradle` 在 GitHub runner 上同样不存在）。
const providesGradleStep = jobCode
  .split('\n')
  .map((l) => l.trim())
  .some((l) => l.startsWith('uses:') && /gradle/i.test(l));

if (!existsSync(join(APP_DIR, 'gradlew'))) {
  const usesWrapper = gradleCmdLines.some((l) => /(^|\s)(\.\/gradlew|gradlew)\s/.test(l));
  if (usesWrapper) {
    problems.push(
      'edge/android-app 下没有 gradlew（本仓不含 gradle wrapper），但 CI 调用了 `./gradlew` —— ' +
        'runner 上会直接 No such file or directory；应改由 setup-gradle 提供 `gradle`'
    );
  }
  if (gradleCmdLines.length > 0 && !providesGradleStep) {
    problems.push(
      'edge-android job 用裸 `gradle` 命令，但 job 里没有任何 `uses:` 引入 Gradle 的步骤 —— ' +
        'GitHub runner 默认不带 gradle，且本仓无 wrapper ⇒ 这条命令必然失败'
    );
  }
}

if (problems.length) {
  fail(`Android 单测接线不完整：\n  - ${problems.join('\n  - ')}`);
}

console.log(
  `${TAG} OK（测试文件 ${testFiles.length} 个／含**真** @Test ${filesWithRealTests.length} 个` +
    `${usesRobolectric ? '／Robolectric 前提已配' : ''}；` +
    `flavor ${JSON.stringify(flavors)} 与 CI 任务名一致；CI 有 <testcase> 计数判据）`
);
for (const f of testFiles) {
  console.log(
    `  · app/src/test/${f}${hasRealTestAnnotation(f) ? '' : '   ⚠️ 无 @Test（注释里的字样不算）'}`
  );
}
