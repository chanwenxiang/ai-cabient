/**
 * 生成 admin 运行时配置 → services/trade-service/src/main/resources/static/admin/runtime-config.json
 *
 * 为什么需要它（2026-09-22）：前端 JS API key **不能**内联进构建产物。
 *   留下 `clients/admin-vue/.env.local` 时（它在**所有 mode** 下加载），vite 会把 key 字面量
 *   写进 `BigScreenView-*.js`；而 CI 检出里没有该文件 ⇒ 同一提交在两处构建得到不同 chunk 哈希，
 *   `admin-artifacts` 的「重建后 git status 必须为空」判据永远对不上。
 *   ⇒ 把 key 从「构建时」挪到「运行时」：产物里只剩一句 `fetch(BASE_URL + 'runtime-config.json')`，
 *     key 由部署侧单独放进 static/admin（gateway 把该目录直接 bind-mount 给 nginx）。
 *
 * 该 json 在 .gitignore 里 ⇒ 不出现在 `git status --porcelain -uall`，
 * 因此**不参与** CI 的 admin-artifacts 比对（判据只覆盖 tracked 与非忽略的 untracked）。
 *
 * 取值优先级（前者优先）：
 *   1. 环境变量 `AMAP_JS_KEY` / `AMAP_JS_SECURITY_CODE`（部署侧 / CI 注入）
 *   2. `infra/.env`（未入库）
 *      ⚠️ 注意与后端 `AMAP_WEB_KEY` 区分：那是**Web 服务 API** key（`AmapGeocodeService` 服务端
 *      地理编码用），前端 JS API key 在控制台是**另一个条目**，不能互相顶替。
 *   3. `clients/admin-vue/.env.development.local` 的 `VITE_AMAP_JS_KEY` /
 *      `VITE_AMAP_SECURITY_CODE`（本机 dev 既有配置，作兜底以**避免同一个 key 写两处**）
 * 都没有 ⇒ 写 `{}`（前端按未配置处理、降级 Leaflet 免 key 瓦片），**不报错**。
 *
 * Usage:
 *   node scripts/gen-admin-runtime-config.mjs
 *   node scripts/gen-admin-runtime-config.mjs --quiet
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const OUT = path.join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'resources',
  'static',
  'admin',
  'runtime-config.json'
);

/**
 * 解析 dotenv 风格文件：只取每个 key 的**首个**出现，忽略注释与空行。
 * 按行解析一律 `split(/\r?\n/)`（`$` 锚定遇 CRLF 会假绿 —— 见 PROJECT-REFERENCE §11.32③）。
 */
function readEnvFile(file) {
  const map = new Map();
  if (!existsSync(file)) return map;
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const s = line.trim();
    if (!s || s.startsWith('#')) continue;
    const eq = s.indexOf('=');
    if (eq <= 0) continue;
    const key = s.slice(0, eq).trim();
    const value = s
      .slice(eq + 1)
      .trim()
      .replace(/^["']|["']$/g, '');
    if (!map.has(key)) map.set(key, value);
  }
  return map;
}

const infraEnv = readEnvFile(path.join(root, 'infra', '.env'));
const adminDevEnv = readEnvFile(path.join(root, 'clients', 'admin-vue', '.env.development.local'));

/** 依次取值；返回 { value, source }（source 只用于日志，不含值）。 */
function pick(envName, fileSources) {
  const fromShell = (process.env[envName] ?? '').trim();
  if (fromShell) return { value: fromShell, source: `env:${envName}` };
  for (const [map, key, label] of fileSources) {
    const value = (map.get(key) ?? '').trim();
    if (value) return { value, source: `${label}#${key}` };
  }
  return { value: '', source: 'none' };
}

const jsKey = pick('AMAP_JS_KEY', [
  [infraEnv, 'AMAP_JS_KEY', 'infra/.env'],
  [adminDevEnv, 'VITE_AMAP_JS_KEY', 'clients/admin-vue/.env.development.local']
]);
const secCode = pick('AMAP_JS_SECURITY_CODE', [
  [infraEnv, 'AMAP_JS_SECURITY_CODE', 'infra/.env'],
  [adminDevEnv, 'VITE_AMAP_SECURITY_CODE', 'clients/admin-vue/.env.development.local']
]);

const config = {};
if (jsKey.value) config.amapJsKey = jsKey.value;
if (secCode.value) config.amapSecurityCode = secCode.value;

mkdirSync(path.dirname(OUT), { recursive: true });
writeFileSync(OUT, `${JSON.stringify(config, null, 2)}\n`, 'utf8');

if (!process.argv.includes('--quiet')) {
  // 🔴 只报来源与长度，绝不打印 key 值（凭据纪律）
  const rel = path.relative(root, OUT).split(path.sep).join('/');
  console.log(`[gen-admin-runtime-config] → ${rel}`);
  console.log(
    jsKey.value
      ? `  amapJsKey        : 已注入 (len=${jsKey.value.length}, from ${jsKey.source})`
      : '  amapJsKey        : 未配置 ⇒ 大屏降级 Leaflet 免 key 瓦片'
  );
  console.log(
    secCode.value
      ? `  amapSecurityCode : 已注入 (len=${secCode.value.length}, from ${secCode.source})`
      : '  amapSecurityCode : 未配置（可选）'
  );
}
