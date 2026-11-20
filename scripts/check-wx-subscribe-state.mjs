#!/usr/bin/env node
/**
 * 门禁：微信订阅消息的 `miniprogram_state` 不得硬编码（MA-372）。
 *
 * 实炸：`WeChatMiniAppClient.sendSubscribeMessage` 的请求体写死
 * `"miniprogram_state", "developer"` ⇒ 生产用户收到催付/设备告警消息，点开跳**开发版**，
 * 普通微信用户无权限打开；且该项当时**没有任何环境变量入口**，配置侧无法纠正。
 *
 * 这一项决定「消息点开落到哪个版本的小程序」，属数据/配置，不能由代码拍板。五条同时成立才算绿：
 *   ① 客户端不再出现字面量 state，且确实从配置取（resolveMiniprogramState）
 *   ② 默认值是 formal（线上版），不是 trial/developer
 *   ③ application.yml 有 miniprogram-state 且默认不是 developer
 *   ④ docker-compose 透传 WECHAT_MINIPROGRAM_STATE（否则 .env 改了容器里仍为空）
 *   ⑤ 发给消费者的消息不得用商户模板（subscribeTemplateId）——两端模板不通用，微信回 400
 *      后催付静默失败（MA-375，同轮发现）
 *
 * 为什么④也要查：本仓有过「application.yml 消费 ${X} 但 compose 没透传 ⇒ 容器 env 恒空」
 * 的同型事故（双 appid 466930d7）。少这一条，改 .env 会静默无效。
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SERVICE_DIR = join(ROOT, 'services/trade-service/src/main/java/com/aicabinet/trade');
export const CLIENT_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/wechat/WeChatMiniAppClient.java'
);
export const PROPERTIES_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/config/WeChatMiniAppProperties.java'
);
export const APP_YML_FILE = join(ROOT, 'services/trade-service/src/main/resources/application.yml');
export const COMPOSE_FILE = join(ROOT, 'infra/docker-compose.apps.yml');
const TAG = '[check-wx-subscribe-state]';

/** 递归收集发送订阅消息的业务调用方源码（文件名 → 内容）。 */
export function collectCallers(dir = SERVICE_DIR, acc = {}) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) collectCallers(full, acc);
    else if (entry.name.endsWith('.java')) acc[full] = readFileSync(full, 'utf8');
  }
  return acc;
}

/** 注释脱敏：等长空格，保行号也保「注释里的样例代码」不参与判定。 */
export function maskComments(raw) {
  return raw
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/^\s*\/\/.*$/gm, (m) => ' '.repeat(m.length))
    .replace(/^\s*#(?!\{).*$/gm, (m) => ' '.repeat(m.length));
}

/** 从 yml/compose 里取某个 key 的默认值（${VAR:default} 的 default 段）。 */
export function defaultOf(src, name) {
  const m = new RegExp(`${name}\\s*:\\s*\\$\\{[A-Z_]+\\s*:\\s*([^}]*)\\}`).exec(src);
  return m ? m[1].trim() : null;
}

export function wxSubscribeStateProblems({ client, properties, appYml, compose, callers = {} }) {
  const c = maskComments(client);
  const p = maskComments(properties);
  const y = maskComments(appYml);
  const m = maskComments(compose);
  const problems = [];

  // ① 客户端：不许出现 "miniprogram_state", "<字面量>"
  if (/"miniprogram_state"\s*,\s*"/.test(c)) {
    problems.push('客户端仍把 miniprogram_state 写成字面量（必须走配置，见 MA-372）');
  }
  if (!/resolveMiniprogramState\s*\(/.test(c)) {
    problems.push('客户端请求体必须取 properties.resolveMiniprogramState()');
  }

  // ② 默认值：声明了 formal 回落，且回落分支不是 trial/developer
  if (!/resolveMiniprogramState\s*\(/.test(p)) {
    problems.push('WeChatMiniAppProperties 缺少 resolveMiniprogramState()（配置入口被删）');
  }
  if (!/MINIPROGRAM_STATES/.test(p) || !/:\s*"formal"/.test(p)) {
    problems.push('非法/缺省值必须回落 "formal"（宁可跳线上版，不能跳用户打不开的版本）');
  }
  if (/:\s*"(developer|trial)"\s*;/.test(p)) {
    problems.push('配置类默认回落不得是 developer/trial（只有显式配置才可出现）');
  }

  // ③ application.yml：键存在，且缺省不是 developer
  if (!/miniprogram-state\s*:/.test(y)) {
    problems.push('application.yml 缺 miniprogram-state 键（配置无法注入）');
  } else {
    const def = defaultOf(y, 'miniprogram-state');
    if (def === null) {
      problems.push('miniprogram-state 必须写成 ${WECHAT_MINIPROGRAM_STATE:默认值} 形式');
    } else if (/^"?developer"?$/.test(def)) {
      problems.push('application.yml 的 miniprogram-state 默认值不得是 developer');
    }
  }

  // ④ compose 透传，且默认不是 developer
  if (!/WECHAT_MINIPROGRAM_STATE\s*:/.test(m)) {
    problems.push('docker-compose.apps.yml 未透传 WECHAT_MINIPROGRAM_STATE（改 .env 会静默无效）');
  } else {
    const def = defaultOf(m, 'WECHAT_MINIPROGRAM_STATE');
    if (def !== null && /^"?developer"?$/.test(def)) {
      problems.push('compose 里 WECHAT_MINIPROGRAM_STATE 的默认值不得是 developer');
    }
  }

  // ⑤ 消费者消息不得用商户模板（MA-375）：模板 id 与接收人不匹配时微信回 400，
  //    sendSubscribeMessage 只 return false ⇒ 催付静默失败、页面只留「已记审计」。
  //
  //    实际写法是「先赋值给局部变量再传」，故要跟踪数据流而不能只看调用实参：
  //    ① 收集 `x = …subscribeTemplateId()` 的变量名（商户模板来源）
  //    ② 消费者调用实参里出现这些变量 ⇒ 红
  //    这样同一文件里「商户变量走商户调用、消费者变量走消费者调用」的写法不会被误判。
  for (const [file, raw] of Object.entries(callers)) {
    const src = maskComments(raw);
    const merchantVars = new Set();
    const assign = /(\w+)\s*=\s*[^;\n]*subscribeTemplateId\s*\(/g;
    let a;
    while ((a = assign.exec(src)) !== null) merchantVars.add(a[1]);
    const call = /sendSubscribeMessage\s*\(([\s\S]*?)\)\s*;/g;
    let hit;
    while ((hit = call.exec(src)) !== null) {
      const args = hit[1];
      if (!/MiniAppTarget\.CONSUMER/.test(args)) continue;
      const usedMerchantVar = [...merchantVars].some((v) => new RegExp(`\\b${v}\\b`).test(args));
      if (usedMerchantVar || /[.\w]subscribeTemplateId\s*\(/.test(args)) {
        problems.push(
          `${file.split(/[\\/]/).pop()} 给消费者发消息用了商户模板（subscribeTemplateId），应 resolveConsumerTemplateId`
        );
      }
    }
  }

  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const problems = wxSubscribeStateProblems({
    client: readFileSync(CLIENT_FILE, 'utf8'),
    properties: readFileSync(PROPERTIES_FILE, 'utf8'),
    appYml: readFileSync(APP_YML_FILE, 'utf8'),
    compose: readFileSync(COMPOSE_FILE, 'utf8'),
    callers: collectCallers()
  });
  if (problems.length) {
    console.error(`${TAG} FAIL:`);
    for (const x of problems) console.error(`  - ${x}`);
    process.exit(1);
  }
  console.log(`${TAG} OK：订阅消息落地版本走配置，缺省 formal`);
}
