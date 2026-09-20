#!/usr/bin/env node
/**
 * 门禁：`testdata/` 下**声明为「浏览器可播」**的样例录像，必须真的能被浏览器解码。
 *
 * 为什么需要它（2026-09-20 新增）
 * ------------------------------
 * `scripts/generate-demo-shopping-video.mjs` 是**手动工具**：只在有人要重做样例时跑一次，
 * 跑它需要 Chromium + 30 秒实时录制。把它塞进 CI 只会让**已提交的二进制产物**被反复改写
 * （非确定性、污染 diff），所以它**不应该**接线。但它产出并提交进仓库的
 * `testdata/sample-shopping.mp4` 带着一条**契约**：「浏览器可解的 H.264」——
 * 而这条契约在 CI 里**无人校验**：
 *   · 真跑它的用例（`T-C03`/`T-M03`/`M-10v`）在 CI 无 MinIO ⇒ 探测落空 ⇒ 恒为 SKIP；
 *   · `check-uat-selectors` 只查选择器／placeholder，不碰二进制。
 * ⇒ 这份产物被换成不可解编码时：CI 全绿，只有本机在「视频能不能播」上红，
 *    而本机红出来的样子**像产品缺陷**（这正是 2026-09-20 实测踩过的一次：设备模拟器录像
 *    编码是 `mp4v`，容器合法、有 `ftyp`，但 Chromium 解不了）。
 *
 * 所以这里接线的是**产物契约**，不是生成器本身。
 *
 * 判据（判**契约**，不判「文件在不在」）
 * ------------------------------------
 * 1. `CONTRACTS` 非空 —— 空表直接红（否则「一条都没扫到」会假绿）。
 * 2. 每条契约的 `declaredBy` 锚点必须仍然成立（谁声明了「它必须能播」）：
 *    锚点文件存在、且仍提到该文件名。有人重命名产物却不改契约 ⇒ 这里红，
 *    而不是让条目悄悄过期、永远绿着。
 * 3. 产物存在且 `size >= minBytes`：**大小下限是刻意立的覆盖契约** ——
 *    22 KB 的样例「够验证播放管线，但覆盖不了大文件/分片/超时」（见
 *    docs/evidence/2026-09-20-three-end-uat-ratchet/README.md §11.3）。
 * 4. 第 1 个 box 必须是 `ftyp`，brand 为可打印 4 字节（只做轻量健全性检查，
 *    不维护 brand 白名单 —— 白名单会误伤合法新 brand）。
 * 5. 必须含浏览器可解的编码 fourcc；🔴 必须**不含** `mp4v`（MPEG-4 Part 2）。
 *
 * ⚠️ 判据的已知边界：编码 fourcc 是**整文件字节搜索**（与生成脚本的自检同口径）。
 * 它能抓住「换成了另一种编码」，不解析 `stsd` 结构 —— 本门禁防的是**产物被换错**，
 * 不是做 MP4 规范校验器。
 *
 * 归类纪律：`testdata/**\/*.mp4` 里的每个文件都必须**显式归类**为 CONTRACT 或 EXEMPT。
 * 未归类的文件直接红 —— 否则新加的样例会静默逃过全部校验（而 `testdata/` 里
 * **本来就同时存在** `mp4v` 的识别流水线输入，只有人能判断哪一类是哪一类）。
 *
 *   node scripts/check-demo-video-format.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-demo-video-format]';

/** 浏览器可解的编码 fourcc（H.264 / H.265 / VP8 / VP9 / AV1）。 */
const DECODABLE = ['avc1', 'avc3', 'hvc1', 'hev1', 'vp08', 'vp09', 'av01'];
/** 明确不可解、且历史上真出现过：MPEG-4 Part 2。 */
const UNDECODABLE = ['mp4v'];

/**
 * 「声明为浏览器可播」的产物契约。
 * `declaredBy` ＝**谁声明了它必须能播**，锚点是自校验用的（见判据 2）。
 */
const CONTRACTS = [
  {
    file: 'testdata/sample-shopping.mp4',
    minBytes: 1024 * 1024,
    why: 'H5 UAT 的「购物录像能播」用例靠它；scripts/seed-demo-shopping-video.ps1 把它传成 MinIO 对象 cabinet-videos/demo/sample-shopping.mp4',
    declaredBy: [
      {
        file: 'scripts/generate-demo-shopping-video.mjs',
        mustMention: 'sample-shopping.mp4',
        role: '产出方（默认 --out）'
      },
      {
        file: 'scripts/seed-demo-shopping-video.ps1',
        mustMention: 'sample-shopping.mp4',
        role: '消费方（上传到 MinIO）'
      }
    ]
  }
];

/**
 * 明确豁免：只要求容器合法，**不要求浏览器可解** —— 它们是识别/视觉流水线的输入素材，
 * 由设备模拟器风格录制产生（`mp4v` 合法）。豁免必须写清理由，否则会退化成「什么都放行」。
 */
const EXEMPT = new Map([
  [
    'testdata/out.mp4',
    '全仓零引用的历史遗留小样（3 KB）；不承担播放契约，如需可播请改走 CONTRACTS'
  ],
  [
    'testdata/static-bottle.mp4',
    '识别流水线输入：vision-service/scripts/generate_test_videos.py 产出，全程有瓶'
  ],
  [
    'testdata/take-one-bottle.mp4',
    '识别流水线输入：vision-service/scripts/generate_test_videos.py 产出，末帧空白'
  ],
  [
    'testdata/take-one-shelf.mp4',
    '识别流水线输入：scripts/fetch-shelf-testdata.ps1 产出，供 e2e-vision-gravity-shopping.ps1'
  ]
]);

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.toLowerCase().endsWith('.mp4')) out.push(p);
  }
  return out;
}

if (CONTRACTS.length === 0) {
  fail('CONTRACTS 为空：没有任何产物受检，本门禁已失去判别力');
}

const problems = [];

for (const c of CONTRACTS) {
  const abs = path.join(ROOT, c.file);

  // 判据 2：锚点（谁声明了它必须能播）仍然成立
  if (!c.declaredBy?.length) {
    problems.push(`${c.file}：declaredBy 为空，无法证明它「必须能播」（条目会悄悄过期）`);
  }
  for (const d of c.declaredBy || []) {
    const dp = path.join(ROOT, d.file);
    if (!fs.existsSync(dp)) {
      problems.push(`${c.file}：声明方 ${d.file} 已不存在（锚点漂了，请重定契约或删除条目）`);
      continue;
    }
    if (!fs.readFileSync(dp, 'utf8').includes(d.mustMention)) {
      problems.push(
        `${c.file}：声明方 ${d.file} 已不再提到 "${d.mustMention}"（产物可能已改名/换路径，条目过期）`
      );
    }
  }

  // 判据 3：存在 + 大小下限
  if (!fs.existsSync(abs)) {
    problems.push(`${c.file}：不存在（${c.why}）`);
    continue;
  }
  const buf = fs.readFileSync(abs);
  if (buf.length < c.minBytes) {
    problems.push(
      `${c.file}：${buf.length} 字节 < 下限 ${c.minBytes} —— 覆盖契约要求它足够大（大文件/分片/超时场景）`
    );
    continue;
  }

  // 判据 4：首个 box 是 ftyp
  if (buf.length < 12) {
    problems.push(`${c.file}：文件过短，不是 MP4`);
    continue;
  }
  const boxType = buf.toString('latin1', 4, 8);
  const brand = buf.toString('latin1', 8, 12);
  if (boxType !== 'ftyp') {
    problems.push(`${c.file}：首个 box 是 "${boxType}" 而不是 "ftyp"（不是合法 MP4 容器）`);
  } else if (!/^[\x20-\x7e]{4}$/.test(brand) || brand.trim() === '') {
    problems.push(`${c.file}：ftyp brand 不可打印（"${brand}"）`);
  }

  // 判据 5：可解编码，且不得是 mp4v
  const bytes = buf.toString('latin1');
  const found = DECODABLE.filter((c4) => bytes.includes(c4));
  const bad = UNDECODABLE.filter((c4) => bytes.includes(c4));
  if (bad.length) {
    problems.push(
      `${c.file}：含不可解编码 ${bad.join(', ')}（容器合法但浏览器解不了 ⇒ 会把环境问题误报成产品缺陷）`
    );
  }
  if (!found.length) {
    problems.push(`${c.file}：找不到任何浏览器可解编码（期望 ${DECODABLE.join('/')}）`);
  }
  if (!bad.length && found.length) {
    console.log(
      `✓ ${c.file}（${buf.length} 字节，brand=${brand}，编码 ${found.join(',')}，契约声明方 ${c.declaredBy.length} 处锚点均在）`
    );
  }
}

// 判据 6：testdata 下每个 mp4 都必须显式归类
const all = walk(path.join(ROOT, 'testdata'));
if (all.length === 0) {
  fail('testdata/ 下一个 mp4 都没有 —— 锚点可能已失效');
}
const declared = new Set(CONTRACTS.map((c) => c.file));
for (const p of all) {
  const rel = path.relative(ROOT, p).split(path.sep).join('/');
  if (declared.has(rel) || EXEMPT.has(rel)) continue;
  problems.push(
    `${rel}：未归类 —— 请显式加进 CONTRACTS（要求浏览器可解）或 EXEMPT（写明为何不要求），否则新样例会静默逃过全部校验`
  );
}

if (problems.length) {
  fail(
    `\n  ${problems.join('\n  ')}\n  ` +
      `（产物契约见本文件头部说明；生成器 scripts/generate-demo-shopping-video.mjs 不接线是刻意的）`
  );
}
console.log(
  `${TAG} OK：${CONTRACTS.length} 条产物契约全部满足；testdata/ 下 ${all.length} 个 mp4 已全部归类` +
    `（契约 ${declared.size} + 豁免 ${EXEMPT.size}）`
);
