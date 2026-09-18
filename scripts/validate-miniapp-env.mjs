import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

function readEnvFile(file) {
  if (!existsSync(file)) return {};
  const values = {};
  for (const rawLine of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const separator = line.indexOf('=');
    if (separator < 1) continue;
    values[line.slice(0, separator).trim()] = line
      .slice(separator + 1)
      .trim()
      .replace(/^['"]|['"]$/g, '');
  }
  return values;
}

const packageDir = process.cwd();
const production = readEnvFile(join(packageDir, '.env.production'));
const local = readEnvFile(join(packageDir, '.env.production.local'));
const apiBaseUrl =
  process.env.VITE_API_BASE_URL || local.VITE_API_BASE_URL || production.VITE_API_BASE_URL || '';

let parsed;
try {
  parsed = new URL(apiBaseUrl);
} catch {
  // Handled by the validation error below.
}

const invalidHost =
  !parsed ||
  ['localhost', '127.0.0.1', 'your-production-host', 'api.example.com'].includes(parsed.hostname);
if (!parsed || parsed.protocol !== 'https:' || invalidHost) {
  console.error(
    'Production mini-program build requires VITE_API_BASE_URL to be a real HTTPS API domain.'
  );
  console.error(
    'Set it in .env.production.local or in the build environment. See .env.production.example.'
  );
  process.exit(1);
}

console.log(`mini-program production API => ${parsed.origin}`);

// AppID 一致性：manifest.json（uni 构建用）与 project.config.json（微信开发者工具用）必须一致
function readJson(file) {
  if (!existsSync(file)) return null;
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

const manifest = readJson(join(packageDir, 'src/manifest.json'));
const projectConfig = readJson(join(packageDir, 'project.config.json'));
const manifestAppId = manifest?.mpWeixin?.appid || manifest?.['mp-weixin']?.appid || '';
const projectAppId = projectConfig?.appid || '';

if (manifestAppId && projectAppId && manifestAppId !== projectAppId) {
  console.error(
    `AppID 不一致：manifest.json=${manifestAppId}，project.config.json=${projectAppId}。请统一后再构建，避免发到错误的小程序账号。`
  );
  process.exit(1);
}
if (!manifestAppId && projectAppId) {
  console.warn(
    `manifest.json 未配置 mp-weixin.appid（当前 project.config.json=${projectAppId}）。`
  );
}
// AppID 优先级：构建期注入（env / CI secret） > 源码里已提交的值。
// 🔴 源码里**不该**出现真 appid（账号资产）；而提交一个**假** appid 更糟——
//    本校验分辨不了 wx5a5bc7b541b62a13 是真是假，于是那条「appid 校验」对它是**假绿**。
//    因此真值统一由 scripts/inject-miniapp-env.mjs 在构建期注入产物，源码留空即 fail-closed。
const packageJson = readJson(join(packageDir, 'package.json')) || {};
const appKey = String(packageJson.name || '')
  .split('/')
  .pop()
  .replace(/-mp$/, '')
  .replace(/[^A-Za-z0-9]+/g, '_')
  .toUpperCase();
const envAppId = (
  process.env[`MP_WEIXIN_APPID_${appKey}`] ||
  process.env.MP_WEIXIN_APPID ||
  ''
).trim();

const effectiveAppId = envAppId || manifestAppId || projectAppId;
console.log(
  `mini-program AppID => ${effectiveAppId || '(未配置)'}${envAppId ? ' (from build-time injection)' : ''}`
);
if (!effectiveAppId || effectiveAppId === 'touristappid') {
  console.error(
    `Production mini-program build requires a real appid: set MP_WEIXIN_APPID_${appKey} (or MP_WEIXIN_APPID) in the build environment, or configure src/manifest.json.`
  );
  process.exit(1);
}

// urlCheck 的**权威判据不在这里**，而在 scripts/inject-miniapp-env.mjs：
// 它检查的是**最终产物** `dist/*/mp-weixin/project.config.json`，而不是源文件里写了什么。
// 源文件必须长期保持 false（开发期要连 localhost 后端），发布期的 true 由注入步骤写入产物。
// 此处只拦「显式要求关闭」这种自相矛盾的配置。
const explicitUrlCheck = (process.env.MP_WEIXIN_URL_CHECK || '').trim().toLowerCase();
if (explicitUrlCheck === 'false') {
  console.error(
    'MP_WEIXIN_URL_CHECK=false 与生产构建冲突：发布产物必须开启合法域名校验（urlCheck=true）。'
  );
  process.exit(1);
}
if (explicitUrlCheck && explicitUrlCheck !== 'true') {
  console.error(`MP_WEIXIN_URL_CHECK 只能是 true/false，收到 ${explicitUrlCheck}`);
  process.exit(1);
}
console.log(
  'mini-program urlCheck => 交由产物判据（inject-miniapp-env.mjs --mode release 校验产物为 true）'
);
