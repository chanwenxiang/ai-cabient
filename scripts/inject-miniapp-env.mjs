#!/usr/bin/env node
/**
 * 小程序发布参数**构建期注入点**。
 *
 * 为什么需要：
 *   - appid 是账号资产，不该硬编码进仓库（历史上 `clients/consumer-mp/project.config.json`
 *     里躺着一个假的 `wx5a5bc7b541b62a13`，而 `validate-miniapp-env.mjs` 无法分辨真假，
 *     于是"appid 校验"对它是**假绿**）；
 *   - `urlCheck` 开发期必须 false（否则开发者工具拒绝连 localhost 后端），
 *     发布期必须 true（否则合法域名形同虚设）——同一个文件放不下两种值。
 *
 * 做法：**不改源码**，只改构建产物。微信开发者工具导入的是 `dist/{dev,build}/mp-weixin`
 * （见 docs/LOCAL_SETUP.md:318），而 `clients/star/dist/` 已被 .gitignore:16 忽略，
 * 所以把真值写进产物既不会误提交、也不会污染工作区。
 *
 * 用法（在各小程序包目录下执行）：
 *   node ../../scripts/inject-miniapp-env.mjs --mode release
 *   node ../../scripts/inject-miniapp-env.mjs --mode dev
 *
 * 环境变量（优先级从高到低）：
 *   MP_WEIXIN_APPID_CONSUMER / MP_WEIXIN_APPID_MERCHANT  —— 按包区分的 appid
 *   MP_WEIXIN_APPID                                      —— 兜底 appid
 *   MP_WEIXIN_URL_CHECK                                  —— 显式 "true"/"false"，覆盖默认
 *
 * 默认 urlCheck：release → true；dev → false。
 *
 * 🔴 本脚本是**唯一**对 urlCheck 的权威判据：它检查的是**最终产物**，
 *    而不是源文件里写了什么（源文件写了 true 但产物没吃到，同样算失败）。
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const modeArgIndex = process.argv.indexOf('--mode');
const mode = modeArgIndex >= 0 ? String(process.argv[modeArgIndex + 1] || '').trim() : '';
if (mode !== 'release' && mode !== 'dev') {
  console.error(
    '[inject-miniapp-env] 用法：node scripts/inject-miniapp-env.mjs --mode release|dev'
  );
  process.exit(2);
}

const packageDir = process.cwd();
const pkg = JSON.parse(readFileSync(join(packageDir, 'package.json'), 'utf8'));
const pkgName = String(pkg.name || '');

// @aicabinet/consumer-mp -> CONSUMER；@aicabinet/merchant-mp -> MERCHANT
const suffix = pkgName.split('/').pop() || '';
const key = suffix
  .replace(/-mp$/, '')
  .replace(/[^A-Za-z0-9]+/g, '_')
  .toUpperCase();

const trim = (v) => (typeof v === 'string' ? v.trim() : '');
const appId =
  trim(process.env[`MP_WEIXIN_APPID_${key}`]) || trim(process.env.MP_WEIXIN_APPID) || '';

const explicitUrlCheck = trim(process.env.MP_WEIXIN_URL_CHECK).toLowerCase();
const urlCheck = explicitUrlCheck === '' ? mode === 'release' : explicitUrlCheck === 'true';
if (explicitUrlCheck !== '' && explicitUrlCheck !== 'true' && explicitUrlCheck !== 'false') {
  console.error(
    `[inject-miniapp-env] MP_WEIXIN_URL_CHECK 只能是 true/false，收到 ${explicitUrlCheck}`
  );
  process.exit(2);
}

console.log(`[inject-miniapp-env] package=${pkgName} key=${key} mode=${mode}`);

// ---- 发布模式：产物必须拿到真 appid + urlCheck=true，否则整包不合格，直接红 ----
if (mode === 'release') {
  if (!appId) {
    console.error(
      `[inject-miniapp-env] release 构建缺少 appid：请设置 MP_WEIXIN_APPID_${key}（或 MP_WEIXIN_APPID）后再构建。`
    );
    console.error(
      '  例：MP_WEIXIN_APPID_MERCHANT=wxXXXXXXXXXXXXXXXX pnpm --filter @aicabinet/merchant-mp run build:mp-weixin'
    );
    process.exit(1);
  }
  if (appId === 'touristappid') {
    console.error('[inject-miniapp-env] release 构建不接受 touristappid（试玩号不能上传发布）。');
    process.exit(1);
  }
  if (!urlCheck) {
    console.error(
      '[inject-miniapp-env] release 构建 urlCheck 必须为 true（合法域名校验不得关闭）。'
    );
    process.exit(1);
  }
}

// dev 模式允许缺 appid：回落到微信「试玩号」，开发者工具可直接导入
const effectiveAppId = appId || (mode === 'dev' ? 'touristappid' : '');
if (mode === 'dev' && !appId) {
  console.warn('[inject-miniapp-env] dev 未提供 appid，产物回落为 touristappid（试玩号）。');
}

const targets = [
  join(packageDir, 'dist', 'dev', 'mp-weixin'),
  join(packageDir, 'dist', 'build', 'mp-weixin')
];
let patched = 0;
let skipped = 0;

for (const dir of targets) {
  const file = join(dir, 'project.config.json');
  if (!existsSync(file)) {
    skipped += 1;
    continue;
  }
  const json = JSON.parse(readFileSync(file, 'utf8'));
  const before = `${json.appid || ''}|${json.setting?.urlCheck}`;
  json.appid = effectiveAppId;
  json.setting = { ...(json.setting || {}), urlCheck };
  const after = `${json.appid}|${json.setting.urlCheck}`;
  if (before === after) {
    console.log(`ok       ${file} (${after})`);
    continue;
  }
  writeFileSync(file, `${JSON.stringify(json, null, 2)}\n`);
  patched += 1;
  console.log(`patched  ${file} (${before} -> ${after})`);
}

if (!patched && skipped === targets.length) {
  console.warn(
    `[inject-miniapp-env] 未找到任何构建产物（${targets.map((t) => `dist/${t.split(/[\\/]dist[\\/]/).pop()}`).join(', ')}），请先执行 uni build。`
  );
}

// 产物回读自检：确认写进去的就是最终值（防"改了源、产物没吃到"）
for (const dir of targets) {
  const file = join(dir, 'project.config.json');
  if (!existsSync(file)) continue;
  const json = JSON.parse(readFileSync(file, 'utf8'));
  if (json.appid !== effectiveAppId || json.setting?.urlCheck !== urlCheck) {
    console.error(`[inject-miniapp-env] 自检失败：${file} 实际值与预期不一致`);
    process.exit(1);
  }
}

console.log(
  `[inject-miniapp-env] done mode=${mode} appid=${effectiveAppId || '(empty)'} urlCheck=${urlCheck} patched=${patched}`
);
