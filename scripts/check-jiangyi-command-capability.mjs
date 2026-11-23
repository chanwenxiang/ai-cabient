#!/usr/bin/env node
/**
 * 门禁：**每条 MQTT 运维指令都必须声明它在将邑柜上的真实归属**。
 *
 * 成因（2026-11-23 复核，缺陷实证）：
 *   将邑柜是当前**唯一真实车队**，而它不接 MQTT —— 只连 jiangyi-gateway 的 WebSocket。
 *   trade 侧 `DeviceServiceClient.requestOpsCommand` / `requestSetTargetTemp` 却一律往
 *   device-service 的 MQTT 主题发：对将邑柜等于发给**没有订阅者**的主题，接口当场回 200
 *   「已下发」，15s 后 `DeviceCommandTracker` 置 TIMEOUT，柜机零动作。
 *   实测可达路径：商户端「重启」(`POST /merchant/devices/{id}/commands` → `AdminDeviceOpsService#reboot`)
 *   —— 而将邑侧的 `reboot`（§4.3.2.1）早在 2026-10-23 就实现了并真机验过。
 *
 * 为什么用「能力表 + 门禁」而不是只在修复处加 if：
 *   V308 加 SELF_TEST 时吃过同型的亏（edge 端未实现 ⇒ 静默丢弃 ⇒ 15s 超时），当时的解法是
 *   「用 Set 而非 if-else 链，漏改更容易被 review 发现」。但 review 不是判据；判据要有牙齿：
 *   新增 `MQTT_CMD_*` 常量却没在 `JiangyiCommandCapability` 声明归属 ⇒ 本条红。
 *
 * 判据链（任一处退化即红）：
 *   ① `CabinetConstants` 里每个 `MQTT_CMD_*` 常量都在能力表里有一条 `Route`
 *   ② 能力表里不得出现常量表没有的名字（改名/删常量后的陈旧项）
 *   ③ `DeviceServiceClient` 的两个设备指令出口都带 `isJiangyi` 分支，且分支里 `throw`
 *      （fail-closed —— 静默 return 会让调用方以为发成功了）
 *
 * 用法：node scripts/check-jiangyi-command-capability.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export const CONSTANTS_FILE = join(
  ROOT,
  'services/common/common-core/src/main/java/com/aicabinet/common/constants/CabinetConstants.java'
);
export const CAPABILITY_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/client/JiangyiCommandCapability.java'
);
export const CLIENT_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/client/DeviceServiceClient.java'
);

/** 等长空格替换注释，保行号；本仓所有静态门禁统一口径。 */
export function maskComments(src) {
  let out = '';
  let i = 0;
  while (i < src.length) {
    if (src.startsWith('//', i)) {
      while (i < src.length && src[i] !== '\n') {
        out += ' ';
        i++;
      }
    } else if (src.startsWith('/*', i)) {
      out += '  ';
      i += 2;
      while (i < src.length && !src.startsWith('*/', i)) {
        out += src[i] === '\n' ? '\n' : ' ';
        i++;
      }
      if (i < src.length) {
        out += '  ';
        i += 2;
      }
    } else {
      out += src[i];
      i++;
    }
  }
  return out;
}

/** 取方法声明的整段花括号体（含花括号）；找不到返回 null。 */
export function methodBody(maskedSrc, name) {
  const decl = new RegExp(`^[ \\t]*(?:[\\w<>\\[\\]]+[ \\t]+)+${name}[ \\t]*\\(`, 'm');
  const m = maskedSrc.match(decl);
  if (!m) return null;
  const open = maskedSrc.indexOf('{', m.index + m[0].length);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < maskedSrc.length; i++) {
    const c = maskedSrc[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return maskedSrc.slice(open, i + 1);
    }
  }
  return null;
}

/** `CabinetConstants` 里声明的 `MQTT_CMD_*` 常量名（去注释）。 */
export function constantNames(constantsSrc) {
  const src = maskComments(constantsSrc);
  return [...src.matchAll(/\bstatic\s+final\s+String\s+(MQTT_CMD_[A-Z0-9_]+)\b/g)].map((m) => m[1]);
}

/** 能力表里的 `常量名 → Route` 条目（只认 `.put(CabinetConstants.X, Route.Y)`）。 */
export function routeEntries(capabilitySrc) {
  const src = maskComments(capabilitySrc);
  const out = new Map();
  for (const m of src.matchAll(
    /\.put\(\s*CabinetConstants\.(MQTT_CMD_[A-Z0-9_]+)\s*,\s*Route\.([A-Z_]+)\s*\)/g
  )) {
    out.set(m[1], m[2]);
  }
  return out;
}

/** 取 `if (… isJiangyi(…) …) { … }` 这一分支块本身（含花括号）。 */
export function isJiangyiGuard(maskedBody) {
  const m = maskedBody.match(/if\s*\([^()]*isJiangyi\s*\([^)]*\)[^()]*\)\s*\{/);
  if (!m) return null;
  const open = maskedBody.indexOf('{', m.index);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < maskedBody.length; i++) {
    const c = maskedBody[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return maskedBody.slice(open, i + 1);
    }
  }
  return null;
}

/**
 * 设备指令出口必须「对将邑 fail-closed」。
 *
 * <p>只要求**将邑分支块内**有 `throw`：`return null` / `return ""` 会让调用方把「没发」当「发了」，
 * 正是本门禁要拦的形态。
 * <p>⚠️ 判据必须只看分支块 —— 方法体尾部还有 `throw new IllegalStateException(…)`（响应体为空时），
 * 拿整段方法体判 `throw` 会恒绿（本夹具第 6 条注入就是抓这个）。
 */
export function clientGuardProblems(clientSrc) {
  const src = maskComments(clientSrc);
  const problems = [];
  for (const name of ['requestOpsCommand', 'requestSetTargetTemp']) {
    const body = methodBody(src, name);
    if (body == null) {
      problems.push(`DeviceServiceClient 找不到 ${name} 方法体（改名？判据失效即红）`);
      continue;
    }
    const guard = isJiangyiGuard(body);
    if (guard == null) {
      problems.push(
        `${name} 没有 isJiangyi 分支——将邑柜会收到没人订阅的 MQTT 指令（假成功 + 15s 超时）`
      );
      continue;
    }
    if (!/\bthrow\b/.test(guard)) {
      problems.push(`${name} 的将邑分支没有 throw（静默跳过 = 调用方以为已下发）`);
    }
  }
  return problems;
}

/** 纯函数入口：三份源码 → 问题列表（夹具直接调，不碰真实磁盘）。 */
export function evaluate({ constantsSrc, capabilitySrc, clientSrc }) {
  const constants = constantNames(constantsSrc);
  const declared = routeEntries(capabilitySrc);
  const problems = [];

  if (constants.length === 0) {
    problems.push('CabinetConstants 里没有解析到任何 MQTT_CMD_* 常量——判据失效（改写法？）');
  }
  for (const name of constants) {
    if (!declared.has(name)) {
      problems.push(
        `${name} 未在 JiangyiCommandCapability 声明将邑归属（Route.GATEWAY / PLATFORM_ONLY / UNSUPPORTED）` +
          '——不声明就会按 MQTT 发出去，对将邑柜等于假成功'
      );
    }
  }
  for (const name of declared.keys()) {
    if (!constants.includes(name)) {
      problems.push(`JiangyiCommandCapability 声明了不存在的常量 ${name}（改名/删常量后的陈旧项）`);
    }
  }
  problems.push(...clientGuardProblems(clientSrc));
  return problems;
}

export function run() {
  const problems = evaluate({
    constantsSrc: readFileSync(CONSTANTS_FILE, 'utf8'),
    capabilitySrc: readFileSync(CAPABILITY_FILE, 'utf8'),
    clientSrc: readFileSync(CLIENT_FILE, 'utf8')
  });
  if (problems.length) {
    console.error('[check-jiangyi-command-capability] FAIL:\n  - ' + problems.join('\n  - '));
    process.exit(1);
  }
  const names = constantNames(readFileSync(CONSTANTS_FILE, 'utf8'));
  console.log(
    `[check-jiangyi-command-capability] OK：${names.length} 条 MQTT 指令均已声明将邑归属，` +
      '设备指令出口对将邑 fail-closed'
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  run();
}
