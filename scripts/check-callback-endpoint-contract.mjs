#!/usr/bin/env node
/**
 * 门禁（S11）：外部回调入口契约——「回调漏免登录白名单」「验签失败冒 500」「mock 裸奔」
 * 三族（MA-047 / MA-065 / WebConfig:24）的登记制控制。
 *
 * 依据 scripts/callback-endpoint-registry.json（8 个回调端点全量入册），判据：
 *   ① **完备性**：trade/gateway/device 主源码里新出现的 notify/callback 映射必须登记
 *     （机械提取：类级 @RequestMapping 前缀 + 方法级路径含 notify|callback，排除
 *     prefs/subscribe/wx-bind 商户配置面）——新回调不登记即红；
 *   ② **白名单锚点（MA-047 本体）**：authMode=SIGNATURE_BYPASS 的 path 必须真实出现在
 *     trade WebConfig 免登录白名单；ONE_TIME_TOKEN 必须在 gateway excludePathPatterns
 *     ——回调漏配白名单=渠道重投全 401=线上死路；
 *   ③ **mock 裸奔防线**：DEV_ONLY 的 path 必须不在 trade 白名单（WebConfig:24 注释明令）；
 *   ④ **设备面不豁免**：DEVICE_TOKEN 的 path 必须不在 gateway 豁免表（豁免=免 JWT 裸奔）；
 *   ⑤ 控制器文件与路径字面量必须真实存在（防登记漂移，仿 check-ledger-evidence）。
 *
 *   node scripts/check-callback-endpoint-contract.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-callback-endpoint-contract]';
const REGISTRY_PATH = join('scripts', 'callback-endpoint-registry.json');
const TRADE_WEB_CONFIG =
  'services/trade-service/src/main/java/com/aicabinet/trade/config/WebConfig.java';
const GATEWAY_WEB_CONFIG =
  'services/jiangyi-gateway/src/main/java/com/aicabinet/jiangyi/config/GatewayWebConfig.java';

const MAPPING_RE = /@(Post|Get)Mapping\s*\(([^)]*)\)/g;

export function extractCallbackMappings(src, rel) {
  const clsPrefix = (src.match(/@RequestMapping\s*\(\s*"([^"]+)"/) || [])[1] || '';
  const out = [];
  for (const m of src.matchAll(MAPPING_RE)) {
    const pathM = m[2].match(/"([^"]+)"/);
    const path = pathM ? pathM[1] : '';
    const full = (clsPrefix + path).replace(/\/+/g, '/');
    if (/notify|callback/i.test(full) && !/prefs|subscribe|wx-bind/.test(full)) {
      out.push({ file: rel, path: full, method: m[1] === 'Post' ? 'POST' : 'GET' });
    }
  }
  return out;
}

/** 纯判定：登记表 × 实际映射 × 两份白名单源码。 */
export function evaluate(mappings, registry, tradeWebSrc, gatewayWebSrc, existsFn, sourceOf) {
  const problems = [];
  const regPaths = new Map(registry.entries.map((e) => [e.path, e]));
  const actualPaths = new Set(mappings.map((m) => m.path));

  // ① 完备性
  for (const m of mappings) {
    if (!regPaths.has(m.path)) {
      problems.push(
        `回调未登记：${m.path}（${m.file}）——外部回调必须先在 callback-endpoint-registry.json 登记` +
          `契约（鉴权模式/白名单锚点/验签器），漏白名单=渠道重投全 401（MA-047）`
      );
    }
  }
  // ②③④ 白名单双向 + ⑤ 锚点
  for (const e of registry.entries || []) {
    if (!actualPaths.has(e.path)) {
      problems.push(`登记陈旧：${e.path} 已无对应映射（控制器改名/删端点要同步登记表）`);
      continue;
    }
    if (!existsFn(e.controller)) {
      problems.push(`登记锚点失效：${e.controller} 不存在`);
      continue;
    }
    const src = sourceOf(e.controller) || '';
    if (!src.includes(e.path.split('/').pop().replace('{orderId}', '')) && !src.includes(e.path)) {
      problems.push(
        `登记路径漂移：${e.path} 在 ${e.controller.split('/').pop()} 源码中找不到（路径字面量变了要同步登记）`
      );
    }
    if (e.authMode === 'SIGNATURE_BYPASS') {
      if (!tradeWebSrc.includes(e.path)) {
        problems.push(
          `MA-047 形态：${e.path} 是验签回调但不在 trade WebConfig 免登录白名单——渠道重投将全 401`
        );
      }
    } else if (e.authMode === 'ONE_TIME_TOKEN') {
      if (!gatewayWebSrc.includes(e.path)) {
        problems.push(
          `MA-047 形态（网关面）：${e.path} 是一次性凭据回调但不在 gateway excludePathPatterns——厂商重投将全 401`
        );
      }
    } else if (e.authMode === 'DEV_ONLY') {
      if (tradeWebSrc.includes(e.path)) {
        problems.push(
          `mock 裸奔防线击穿：${e.path} 出现在 trade 白名单——无验签端点必须保持鉴权（WebConfig:24 注释明令）`
        );
      }
    } else if (e.authMode === 'DEVICE_TOKEN') {
      if (gatewayWebSrc.includes(e.path)) {
        problems.push(
          `设备面豁免击穿：${e.path} 出现在 gateway 豁免表——设备 JWT 端点被免鉴权=裸奔`
        );
      }
    }
  }
  // 白名单里有 notify/callback 但未登记（白名单悄悄加路径）
  const whitelistNotifyPaths = [...tradeWebSrc.matchAll(/"([^"]*(?:notify|callback)[^"]*)"/gi)]
    .map((m) => m[1])
    .filter((p) => p.startsWith('/api/'));
  for (const p of whitelistNotifyPaths) {
    if (!regPaths.has(p)) {
      problems.push(
        `白名单路径未登记：${p} 出现在 trade WebConfig 免登录白名单但不在回调契约登记表——白名单加路径必须先过契约登记`
      );
    }
  }
  return problems;
}

function walkJava(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (['node_modules', 'target', 'static'].includes(name)) continue;
      walkJava(p, out);
    } else if (name.endsWith('.java')) out.push(p);
  }
  return out;
}

function main() {
  const regPath = join(ROOT, REGISTRY_PATH);
  if (!existsSync(regPath)) fail(`登记表缺失 ${REGISTRY_PATH}`);
  const registry = JSON.parse(readFileSync(regPath, 'utf8'));
  const tradeWebSrc = readFileSync(join(ROOT, TRADE_WEB_CONFIG), 'utf8');
  const gatewayWebSrc = readFileSync(join(ROOT, GATEWAY_WEB_CONFIG), 'utf8');

  const scanDirs = [
    join(ROOT, 'services/trade-service/src/main/java/com/aicabinet/trade/api'),
    join(ROOT, 'services/jiangyi-gateway/src/main/java/com/aicabinet/jiangyi/api'),
    join(ROOT, 'services/device-service/src/main/java')
  ];
  const mappings = [];
  for (const d of scanDirs) {
    for (const f of walkJava(d)) {
      mappings.push(
        ...extractCallbackMappings(readFileSync(f, 'utf8'), relative(ROOT, f).replace(/\\/g, '/'))
      );
    }
  }
  if (mappings.length === 0) fail('回调映射提取数=0（提取器坏了，零命中=假绿）');

  const problems = evaluate(
    mappings,
    registry,
    tradeWebSrc,
    gatewayWebSrc,
    (rel) => existsSync(join(ROOT, rel)),
    (rel) => readFileSync(join(ROOT, rel), 'utf8')
  );
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S11 回调入口契约）`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${registry.entries.length} 个回调端点契约全对账（白名单双向锚点/mock 防线/设备面校验），映射 ${mappings.length} 个`
  );
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
