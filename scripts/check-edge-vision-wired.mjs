#!/usr/bin/env node
/**
 * 「端侧识别实现必须真的被装配」—— 孤立代码门禁（静态，不需要 Android SDK）。
 *
 * 背景（2026-10-07 取证）：
 *   `edge/android-app/.../vision/` 整层 6 个文件 278 行，经全仓 grep 确认
 *   **除层内自引用外零外部调用方**：
 *     - `CabinetController`（唯一组装点）零引用 vision 包任何类；
 *     - `EdgeVisionConfig.EDGE_VISION_ENABLED` 恒 `false`；
 *     - `NcnnYoloDetector.available` 恒 `true`，而 `detect()` 在有真实模型时
 *       返回 `emptyList()`（逻辑倒置：模型越真越识别不出东西）；
 *     - `app/src/test` 下**零**测试覆盖这 6 个文件。
 *   ⇒ 它看起来「有能力」，实际一行都不会执行。而云端
 *     `VisionInternalController.edgeResults` 的注释已写明「第三方按文档接入即可直连」
 *     —— 即端侧识别（将邑）走 **HTTP 上报**，不需要设备端这套本地推理。
 *
 *   危害不是「多几百行」，而是**能力幻觉**：读代码会以为端侧识别已就绪。
 *   这正是本项目吃过亏的形态（「能力已建、链路未通，比没做更危险」）。
 *
 * 本门禁守三条失效路径：
 *   ① 重新引入一个**没有任何装配点**的识别/上报实现（孤立类复活）；
 *   ② 引入一个 `available = true` 恒真、或「有真模型就返回空」的**倒置判据**；
 *   ③ 总开关恒 false 却留着实现（看起来有能力、实际不跑）。
 *
 * 🔴 门禁自身也守自己：
 *   - `ALLOW` 条目必须有 `why`，空 ⇒ 红（防止「先加白名单，以后再说」）；
 *   - 非 `pending` 的条目会被**复核是否还被调用** —— 豁免一个已经没人用的类是
 *     「死豁免」，比不豁免更坏（真正的孤立类能靠过期条目混过门禁）；
 *   - `pending: true`（「待对接」）允许零调用方，但**必须被测试引用** ——
 *     否则「待对接」会变成「永远不接」的合法借口。
 *
 *   node scripts/check-edge-vision-wired.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-edge-vision-wired]';

const APP_MAIN = join(root, 'edge', 'android-app', 'app', 'src', 'main', 'java');
const VISION_DIR = join(APP_MAIN, 'com', 'aicabinet', 'edge', 'vision');
const TEST_ROOT = join(root, 'edge', 'android-app', 'app', 'src', 'test');

/**
 * 允许存在但**必须已装配**的实现。
 *   why     必填；空或缺失 ⇒ 门禁红。
 *   pending true ⇒「待对接」：允许零调用方，但必须有测试引用该类。
 *
 * 🔴 本表的历史教训（写豁免前必须 grep 实证，别凭印象写）：
 *   原先给 `FrameCaptureManager` 写过理由「录像编排直接调用」，实测**是编的** ——
 *   `SessionVideoRecorder` 是独立录像实现，从不引用它；且它自己的
 *   `extractKeyFrames` 对有视频/无视频都返回空帧。
 */
const ALLOW = new Map([
  [
    'SkuDeltaCalculator.kt',
    {
      why:
        '开门/关门两帧差分算增减 —— 与推理实现无关的纯逻辑，2026-10-07 已从 ' +
        'NcnnYoloDetector.Detection 解耦为自带 Detection。端侧识别改用将邑后由新实现调用。',
      pending: true
    }
  ]
]);

const problems = [];

const rel = (p) => relative(root, p).replace(/\\/g, '/');

if (!existsSync(VISION_DIR)) {
  console.log(`${TAG} OK：vision/ 目录不存在（已整体移除），无孤立实现。`);
  process.exit(0);
}
const files = readdirSync(VISION_DIR).filter((f) => f.endsWith('.kt'));
if (files.length === 0) {
  console.log(`${TAG} OK：vision/ 为空，无孤立实现。`);
  process.exit(0);
}

function filesUnder(dir, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) filesUnder(full, acc);
    else if (entry.endsWith('.kt')) acc.push(full);
  }
  return acc;
}
const allKt = filesUnder(APP_MAIN);
const testKt = filesUnder(TEST_ROOT);

/** 找出 className 在 vision/ 外的引用方（生产 + 测试分开）。 */
function refsOutside(file, pool) {
  return pool.filter(
    (f) =>
      f !== file && !f.startsWith(VISION_DIR) && readFileSync(f, 'utf8').includes(classNameOf(file))
  );
}
const classNameOf = (file) => file.replace(/\\/g, '/').split('/').pop().replace(/\.kt$/, '');

for (const name of files) {
  const full = join(VISION_DIR, name);
  const className = classNameOf(full);

  const allow = ALLOW.get(name);
  if (allow) {
    if (!allow.why || !String(allow.why).trim()) {
      problems.push(
        `${rel(full)}：在 ALLOW 白名单里但 why 为空 —— 「以后再说」不算理由，请写清为什么可以存在`
      );
      continue;
    }
    const prodRefs = refsOutside(full, allKt);
    const testRefs = refsOutside(full, testKt);
    if (allow.pending) {
      // 「待对接」：允许零生产调用，但必须有测试钉住行为
      if (prodRefs.length === 0 && testRefs.length === 0) {
        problems.push(
          `${rel(full)}：标为 pending（待对接）却**既无装配点也无测试** —— ` +
            `「待对接」不能变成「永远不接」的借口，要么补测试钉住行为，要么删掉`
        );
      }
    } else if (prodRefs.length === 0) {
      problems.push(
        `${rel(full)}：在 ALLOW 白名单里，但已查不到任何生产调用方 —— ` +
          `白名单已过期，请删除该条目或改成 pending 并补测试`
      );
    }
    continue;
  }

  // ---- 判据①：必须有外部调用方 ----
  const prodRefs = refsOutside(full, allKt);
  if (prodRefs.length === 0) {
    problems.push(
      `${rel(full)}：${className} 在 vision/ 外**零调用方**（仅层内自引用）—— ` +
        `它不会执行，却让人以为端侧识别已就绪。要么装配，要么删除（端侧识别走后端 ` +
        `VisionInternalController.edge-results 直报，不需要设备端本地推理）`
    );
    continue;
  }

  // ---- 判据②：available 不得恒真、detect 不得逻辑倒置 ----
  const src = readFileSync(full, 'utf8');
  if (/val\s+available\s*:\s*Boolean\s*=\s*true/.test(src)) {
    problems.push(
      `${rel(full)}：${className}.available 恒为 true —— 恒真的「就绪」判据等于没有判据（会被上层当已就绪）`
    );
  }
  if (/if\s*\(\s*!\s*\w*(useMock|mock)\w*\s*\)\s*return\s+emptyList\(\)/.test(src)) {
    problems.push(
      `${rel(full)}：${className}.detect 逻辑倒置 —— 有真实模型时返回 emptyList()，` +
        `即「模型越真越识别不出东西」。这类实现应删除而不是修`
    );
  }
}

// ---- 判据③：总开关恒false 时，不该还留着推理实现 ----
const configPath = join(VISION_DIR, 'EdgeVisionConfig.kt');
if (existsSync(configPath)) {
  const cfg = readFileSync(configPath, 'utf8');
  const enabled = cfg.match(/const\s+val\s+EDGE_VISION_ENABLED\s*=\s*(\w+)/);
  const others = files.filter((f) => f !== 'EdgeVisionConfig.kt' && !ALLOW.has(f));
  if (enabled && enabled[1] === 'false' && others.length > 0) {
    problems.push(
      `${rel(configPath)}：EDGE_VISION_ENABLED 恒 false，但 vision/ 下仍有 ` +
        `${others.length} 个未豁免的类（${others.join(', ')}）—— 总开关关着却留着实现，` +
        `等于「看起来有能力、实际不跑」`
    );
  }
}

if (problems.length > 0) {
  console.error(`${TAG} FAIL：发现 ${problems.length} 个问题：`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(
  `${TAG} OK：vision/ ${files.length} 个文件全部有装配点或有测试钉住的 pending 豁免，` +
    `判据无恒真/倒置，总开关与实现一致。`
);
