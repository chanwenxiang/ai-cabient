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
    values[line.slice(0, separator).trim()] = line.slice(separator + 1).trim().replace(/^['"]|['"]$/g, '');
  }
  return values;
}

const packageDir = process.cwd();
const production = readEnvFile(join(packageDir, '.env.production'));
const local = readEnvFile(join(packageDir, '.env.production.local'));
const apiBaseUrl = process.env.VITE_API_BASE_URL || local.VITE_API_BASE_URL || production.VITE_API_BASE_URL || '';

let parsed;
try {
  parsed = new URL(apiBaseUrl);
} catch {
  // Handled by the validation error below.
}

const invalidHost = !parsed || ['localhost', '127.0.0.1', 'your-production-host', 'api.example.com'].includes(parsed.hostname);
if (!parsed || parsed.protocol !== 'https:' || invalidHost) {
  console.error('Production mini-program build requires VITE_API_BASE_URL to be a real HTTPS API domain.');
  console.error('Set it in .env.production.local or in the build environment. See .env.production.example.');
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
console.log(`mini-program AppID => ${manifestAppId || projectAppId || '(未配置，请确认)'}`);
