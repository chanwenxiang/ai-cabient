#!/usr/bin/env node
/**
 * 门禁：yml 里「空默认值」的配置键，必须由某个可部署 compose 的 environment 透传。
 *
 * 背景（lessons #237 第二例 / #333 / #266，复现 ≥3 次）：
 * Spring 的 `${KEY:}` 只是「取不到就用空串」，**不会**把宿主 `.env` 自动塞进容器。
 * 新增配置键时只在 application.yml 里写占位符、忘了在 compose 的 environment 块补
 * `${KEY:-…}`，后果是容器里恒为空、功能静默不生效（订阅消息模板、商户模板各踩一次），
 * 而本地 IDEA 跑得好好的——最难查的一类。
 *
 * 判据：
 *   1. 收集 services/<module>/src/main/resources/application*.yml 里所有 `${KEY:}`（空默认）；
 *   2. 对每个键，要求它出现在**同名服务块**的 environment 里（至少一个 compose 文件）；
 *   3. `application-prod.yml` 里的键必须由生产链 compose（production/prod-server/
 *      prod-managed/staging/apps）透传；
 *   4. 确实不该透传的键写进 `scripts/config-env-waivers.json`（必须带理由）；
 *      豁免键若已被透传、或已从 yml 消失，都判红（防豁免腐烂成噪音）。
 *
 * 负向验证：给任意 application.yml 加一行 `${NEW_KEY_PROBE:}` => 红；
 * 同时在 compose 透传或加带理由的豁免 => 绿。
 *
 *   node scripts/check-config-env-passthrough.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-config-env-passthrough]';
const WAIVERS = join(ROOT, 'scripts', 'config-env-waivers.json');

const PROD_CHAIN = /docker-compose\.(production|prod-server|prod-managed|staging|apps)\.ya?ml$/;

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

/** 解析 compose 的 service -> environment keys */
function parseCompose(text) {
  const services = new Map();
  let service = null;
  let inEnvironment = false;
  for (const line of text.split(/\r?\n/)) {
    const svc = line.match(/^  ([A-Za-z0-9_-]+):\s*$/);
    if (svc) {
      service = svc[1];
      services.set(service, new Set());
      inEnvironment = false;
      continue;
    }
    if (!service) continue;
    if (/^    environment:\s*$/.test(line)) {
      inEnvironment = true;
      continue;
    }
    if (/^    [A-Za-z]/.test(line)) inEnvironment = false;
    if (!inEnvironment) continue;
    const mapStyle = line.match(/^\s+([A-Z][A-Z0-9_]+)\s*:/);
    const listStyle = line.match(/^\s+-\s*([A-Z][A-Z0-9_]+)\s*=/);
    if (mapStyle) services.get(service).add(mapStyle[1]);
    if (listStyle) services.get(service).add(listStyle[1]);
  }
  return services;
}

const composeFiles = readdirSync(join(ROOT, 'infra'))
  .filter((f) => /^docker-compose.*\.ya?ml$/.test(f))
  .map((f) => ({
    name: f,
    path: join(ROOT, 'infra', f),
    services: parseCompose(readFileSync(join(ROOT, 'infra', f), 'utf8'))
  }));

/** key -> { services:Set<module>, prodOnly:boolean } */
const needed = new Map();
const resourcesRoot = join(ROOT, 'services');
for (const mod of readdirSync(resourcesRoot, { withFileTypes: true })) {
  if (!mod.isDirectory()) continue;
  const dir = join(resourcesRoot, mod.name, 'src', 'main', 'resources');
  if (!existsSync(dir)) continue;
  for (const file of readdirSync(dir).filter((f) => /^application.*\.ya?ml$/.test(f))) {
    const text = readFileSync(join(dir, file), 'utf8');
    const isProd = /prod/.test(file);
    for (const m of text.matchAll(/\$\{([A-Z][A-Z0-9_]+):\}/g)) {
      const key = m[1];
      const cur = needed.get(key) || { services: new Set(), prodOnly: false, files: new Set() };
      cur.services.add(mod.name);
      if (isProd) cur.prodOnly = true;
      cur.files.add(`${mod.name}/${file}`);
      needed.set(key, cur);
    }
  }
}
if (needed.size === 0) fail('没有解析到任何空默认配置键，锚点可能已失效');

const waivers = existsSync(WAIVERS) ? JSON.parse(readFileSync(WAIVERS, 'utf8')) : {};
const waiverKeys = new Set(Object.keys(waivers));
const problems = [];

for (const [key, info] of needed) {
  const hits = [];
  for (const compose of composeFiles) {
    if (info.prodOnly && !PROD_CHAIN.test(compose.name)) continue;
    for (const service of info.services) {
      if (compose.services.get(service)?.has(key)) hits.push(`${compose.name}:${service}`);
    }
  }
  if (hits.length > 0) {
    if (waiverKeys.has(key)) {
      problems.push(`${key} 已被 ${hits[0]} 透传，豁免条目已过期（删掉 ${WAIVERS} 里的这条）`);
    }
    continue;
  }
  if (waiverKeys.has(key)) {
    if (!String(waivers[key] || '').trim()) {
      problems.push(`${key} 的豁免缺少理由（${WAIVERS}）`);
    }
    continue;
  }
  problems.push(
    `${key} 在 ${[...info.files].join(', ')} 里是空默认值，但 compose 的 ${[...info.services].join(
      '/'
    )} environment 从未透传 —— 容器里恒为空，功能会静默不生效`
  );
}

for (const key of waiverKeys) {
  if (!needed.has(key)) {
    problems.push(`${key} 的豁免已无对应 yml 占位符（删掉 ${WAIVERS} 里的这条）`);
  }
}

if (problems.length) {
  fail(
    `配置透传问题 ${problems.length} 处：\n  - ${problems.join('\n  - ')}\n` +
      '正解：在对应 compose 的 environment 里补 `${KEY:-}`；确认不需要宿主注入的写进 scripts/config-env-waivers.json（带理由）。'
  );
}

console.log(
  `${TAG} OK：${needed.size} 个空默认配置键均有 compose 透传或带理由的豁免（豁免 ${waiverKeys.size} 个）`
);
