#!/usr/bin/env node
/**
 * R20 P3-9 门禁（2026-11-12）：列表 / 封面 / 横幅用 OSS 缩略参数，省首屏带宽。
 *
 * 🔴 判据设计（铁律 84 / 75）：
 *   ① 扫 TS 源码前 maskComments() 抹注释（含 JSDoc），避免注释复述 x-oss-process 造成假绿；
 *   ② 判据贴真实责任边界：只取 appendOssProcess / thumbUrl 的**函数体**，不全文 includes；
 *   ③ 缩略只对直连 OSS 的 URL（host 含 aliyuncs.com 或 oss- 前缀）生效，后端相对路径 / 非 OSS
 *      域名原样返回——否则会给非 OSS 链接拼出坏参数；
 *   ④ 钉住四个高频调用点（订单封面 / 营销横幅 / 营销首页 / 商户设备详情）已改用 thumbUrl，
 *      且 consumer-mp 通过 product-thumb.ts 重新导出 thumbUrl。
 *
 * 用法：
 *   node scripts/check-oss-thumb-param.mjs             # 正向门禁
 *   node scripts/check-oss-thumb-param.mjs --self-test # 沙箱化负向变异 + md5 零残留
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const P = {
  impl: 'packages/shared-uni/src/product-image.ts',
  orders: 'clients/consumer-mp/src/pages/orders/orders.vue',
  adBanner: 'clients/consumer-mp/src/components/marketing-ad-banner.vue',
  marketing: 'clients/consumer-mp/src/pages/marketing/index.vue',
  deviceDetail: 'clients/merchant-mp/src/pages/device-detail/device-detail.vue',
  thumbExport: 'clients/consumer-mp/src/utils/product-thumb.ts'
};

/** 注释替换为等长空格：保行号/列偏移，注释里的旧写法不得被当成实现。 */
function maskComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
}

/** 从 openIdx（开括号后一个字符）起截取配平内容；字符串/模板里的括号不计数（模板无裸花括号时安全）。 */
function balanced(src, openIdx) {
  let depth = 1;
  let inStr = null;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      if (c === '\\') {
        i++;
        continue;
      }
      if (c === inStr) inStr = null;
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      inStr = c;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}' && --depth === 0) return src.slice(openIdx, i);
  }
  return '';
}

/** 取函数签名后的完整函数体；找不到返回 null（调用方必须判红，不能当空串放过）。 */
function methodBody(src, signature) {
  const i = src.indexOf(signature);
  if (i < 0) return null;
  const open = src.indexOf('{', i + signature.length);
  if (open < 0) return null;
  return balanced(src, open + 1);
}

/** 纯函数判据：read(relPath) → 源码文本。正向从磁盘读，自测从内存副本读。 */
function evaluate(read) {
  const problems = [];
  const bad = (msg) => problems.push(msg);
  const ts = maskComments(read(P.impl));

  // ── R1 appendOssProcess：拼 x-oss-process=image/resize，且仅对 OSS host 生效 ──
  const appendBody = methodBody(ts, 'function appendOssProcess(');
  if (appendBody === null) {
    bad('找不到 appendOssProcess 函数体');
  } else {
    if (!/x-oss-process/.test(appendBody))
      bad('appendOssProcess 未追加 x-oss-process 参数（OSS 缩略失效）');
    if (!/image\/resize/.test(appendBody)) bad('appendOssProcess 未使用 image/resize 下采样');
    if (!/isOssHost\(host\)/.test(appendBody))
      bad('appendOssProcess 未对 OSS host 限定（非 OSS 域名不应加缩略参数）');
  }

  // ── R2 thumbUrl 把 {width,height} 转交 absoluteImageUrl ──
  const thumb = methodBody(ts, 'export function thumbUrl(');
  if (thumb === null) {
    bad('找不到 thumbUrl 函数体');
  } else if (!/absoluteImageUrl\(url, \{ width, height \}\)/.test(thumb)) {
    bad('thumbUrl 未把 {width,height} 转交 absoluteImageUrl');
  }

  // ── R3 absoluteImageUrl 支持 opts 并在存在时调 appendOssProcess ──
  if (!/export function absoluteImageUrl\(url\?: string \| null, opts\?: ThumbOptions\)/.test(ts))
    bad('absoluteImageUrl 签名未支持 opts（缩略参数无法传入）');
  if (!/opts \? appendOssProcess\(value, opts\) : value/.test(ts))
    bad('absoluteImageUrl 未在 opts 存在时调用 appendOssProcess');

  // ── R4 导出（thumbUrl 是消费端入口；appendOssProcess 是 absoluteImageUrl 的内部 helper，不导出） ──
  if (!/export function thumbUrl/.test(ts)) bad('thumbUrl 未导出');

  // ── R5 四个高频调用点改用 thumbUrl（不拉原图） ──
  const sites = [
    ['orders', P.orders],
    ['marketing-ad-banner', P.adBanner],
    ['marketing/index', P.marketing],
    ['device-detail', P.deviceDetail]
  ];
  for (const [name, path] of sites) {
    if (!/thumbUrl\(/.test(read(path)))
      bad(`${name} 未改用 thumbUrl( 缩略图（仍拉原图，浪费首屏带宽）`);
  }

  // ── R6 consumer-mp 重新导出 thumbUrl ──
  if (!/thumbUrl/.test(read(P.thumbExport)))
    bad('consumer-mp product-thumb.ts 未 re-export thumbUrl');

  return problems;
}

const diskRead = (p) => readFileSync(join(ROOT, p), 'utf8');
const md5 = (s) => createHash('md5').update(s).digest('hex');

/** 负向夹具：只在内存副本上变异，逐条期望转红；再验注释不假红与磁盘 md5 零残留。 */
function selfTest() {
  const paths = [P.impl, P.orders, P.adBanner, P.marketing, P.deviceDetail, P.thumbExport];
  const baseline = new Map(paths.map((p) => [p, diskRead(p)]));
  const before = new Map(paths.map((p) => [p, md5(baseline.get(p))]));

  const mutations = [
    [
      'appendOssProcess 不再下采样（删 image/resize）',
      P.impl,
      (s) => s.replace('image/resize,m_', 'image/original,m_'),
      'image/resize'
    ],
    [
      'appendOssProcess 不再限定 OSS host（对所有域名加参数）',
      P.impl,
      (s) => s.replace('if (!isOssHost(host)) return url;', ''),
      'OSS host'
    ],
    [
      'thumbUrl 不再转交 absoluteImageUrl',
      P.impl,
      (s) => s.replace('return absoluteImageUrl(url, { width, height });', 'return url || "";'),
      'absoluteImageUrl'
    ],
    [
      'absoluteImageUrl 不再调用 appendOssProcess',
      P.impl,
      (s) => s.replace('return opts ? appendOssProcess(value, opts) : value;', 'return value;'),
      'appendOssProcess'
    ],
    [
      'orders.vue 退回拉原图（去掉 thumbUrl）',
      P.orders,
      (s) => s.replace(':src="thumbUrl(o.coverImageUrl, 200)"', ':src="o.coverImageUrl"'),
      'thumbUrl('
    ]
  ];

  let passed = 0;
  for (const [name, path, mutate, expect] of mutations) {
    const copy = new Map(baseline);
    const mutated = mutate(baseline.get(path));
    if (mutated === baseline.get(path)) {
      console.error(`[self-test] FAIL 变异未生效（源串没匹配上）：${name}`);
      process.exit(1);
    }
    copy.set(path, mutated);
    const problems = evaluate((p) => copy.get(p));
    if (!problems.length) {
      console.error(`[self-test] FAIL 变异未被咬住：${name}`);
      process.exit(1);
    }
    if (!problems.some((p) => p.includes(expect))) {
      console.error(`[self-test] FAIL 变异命中的不是预期判据「${expect}」：${name}`, problems);
      process.exit(1);
    }
    passed++;
  }

  // 反向：注释里复述 x-oss-process 不得造成假绿（铁律 84①）
  const withComment = new Map(baseline);
  withComment.set(
    P.impl,
    baseline.get(P.impl) +
      '\n// 旧实现：直接返回原图 url，未拼 x-oss-process=image/resize 下采样参数\n'
  );
  const commentProblems = evaluate((p) => withComment.get(p));
  if (commentProblems.length) {
    console.error('[self-test] FAIL 注释里的旧写法造成假红：', commentProblems);
    process.exit(1);
  }
  passed++;

  // md5 零残留：自测全程只在内存副本上变异
  for (const [p] of baseline) {
    if (md5(diskRead(p)) !== before.get(p)) {
      console.error(`[self-test] FAIL 磁盘被改动：${p}`);
      process.exit(1);
    }
  }
  console.log(
    `[check-oss-thumb-param] self-test OK：${passed} 个变异/防假红断言全部符合，md5 零残留`
  );
  process.exit(0);
}

// 负向夹具识别要求剥注释后同时出现 selfTest( 与 --self-test，故自测主体必须是具名函数 + 显式调用。
if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  const problems = evaluate(diskRead);
  if (problems.length) {
    console.error('[check-oss-thumb-param] FAIL');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log('[check-oss-thumb-param] OK：OSS 缩略参数 + 四个高频调用点改 thumbUrl，6 组判据全绿');
}
