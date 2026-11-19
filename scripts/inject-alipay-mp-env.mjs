#!/usr/bin/env node
/**
 * 支付宝小程序 appid **构建期注入点**（与 `inject-miniapp-env.mjs` 同思路）。
 *
 * 为什么不能写进 `src/manifest.json`：
 *   - appid 是账号资产，不该硬编码进仓库（消费者微信端当年就吃过这个亏：
 *     `project.config.json` 里躺着一个假 appid，校验脚本分辨不出来 ⇒「校验」假绿）；
 *   - manifest 里的 mp-alipay.appid 留空时，uni 会把产物写成支付宝的
 *     **touristappid**（游客模式：能编译、能模拟器预览，但**不能真机预览/上传**）。
 *     这个值不会报错，只会让人误以为「构建成功 = 可发布」。
 *
 * 做法：只改构建产物 `dist/{build,dev}/mp-alipay/mini.project.json`（已被 .gitignore 忽略），
 * 不动源码。release 缺 appid 直接 exit 1 —— 宁可在 CI 红，也不要发出一个游客包。
 *
 * 环境变量（优先级从高到低）：
 *   MP_ALIPAY_APPID_CONSUMER / MP_ALIPAY_APPID
 *
 * 用法（clients/consumer-mp 下）：
 *   node ../../scripts/inject-alipay-mp-env.mjs --mode release
 *   node ../../scripts/inject-alipay-mp-env.mjs --mode dev
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const modeArgIndex = process.argv.indexOf('--mode');
const mode = modeArgIndex >= 0 ? String(process.argv[modeArgIndex + 1] || '').trim() : '';
if (mode !== 'release' && mode !== 'dev') {
  console.error(
    '[inject-alipay-mp-env] 用法：node scripts/inject-alipay-mp-env.mjs --mode release|dev'
  );
  process.exit(2);
}

const trim = (v) => (typeof v === 'string' ? v.trim() : '');

/** 与 validate-miniapp-env.mjs 同口径：允许把 appid 放在客户端 env 文件里（不进前端产物）。 */
function readEnvFile(file) {
  const out = {};
  if (!existsSync(file)) return out;
  for (const rawLine of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    out[line.slice(0, eq).trim()] = line
      .slice(eq + 1)
      .trim()
      .replace(/^['"]|['"]$/g, '');
  }
  return out;
}

const packageDir = process.cwd();
const envFiles = [
  join(packageDir, '.env.production.local'),
  join(packageDir, '.env.production'),
  join(packageDir, '.env.development.local'),
  join(packageDir, '.env.development')
];
const fileEnv = Object.assign({}, ...envFiles.map(readEnvFile));

const appId =
  trim(process.env.MP_ALIPAY_APPID_CONSUMER) ||
  trim(process.env.MP_ALIPAY_APPID) ||
  trim(fileEnv.MP_ALIPAY_APPID_CONSUMER) ||
  trim(fileEnv.MP_ALIPAY_APPID);

// uni 的 `build` 恒写 dist/build（`--mode development` 也不改目录），`uni -p` watch 写 dist/dev。
// 两种都扫：以实际存在的那份为准，避免「构建成功、注入找不到文件」。
const candidates = ['build', 'dev'].map((kind) =>
  join(process.cwd(), 'dist', kind, 'mp-alipay', 'mini.project.json')
);
const existing = candidates.filter((p) => existsSync(p));
if (existing.length === 0) {
  console.error(
    `[inject-alipay-mp-env] 未找到产物 mini.project.json（先跑 uni build -p mp-alipay）：\n  ${candidates.join('\n  ')}`
  );
  process.exit(2);
}

if (!appId) {
  if (mode === 'release') {
    console.error(
      '[inject-alipay-mp-env] 缺少 MP_ALIPAY_APPID（或 MP_ALIPAY_APPID_CONSUMER）。\n' +
        '  release 包不能带 touristappid —— 支付宝开发者工具无法真机预览/上传。\n' +
        '  请在客户端环境文件里配消费者支付宝小程序 appid 后重跑。'
    );
    process.exit(1);
  }
  console.warn(
    '[inject-alipay-mp-env] dev 模式未配 appid：保留 touristappid（模拟器可用，真机不可用）'
  );
  process.exit(0);
}

for (const file of existing) {
  const raw = readFileSync(file, 'utf8');
  let project;
  try {
    project = JSON.parse(raw);
  } catch {
    console.error(`[inject-alipay-mp-env] mini.project.json 不是合法 JSON：${file}`);
    process.exit(1);
  }
  const before = String(project.appid || '');
  project.appid = appId;
  writeFileSync(file, `${JSON.stringify(project, null, 2)}\n`, 'utf8');
  console.log(`[inject-alipay-mp-env] ${file}: appid ${before || '(空)'} -> ${appId}`);
}
