#!/usr/bin/env node
/** `check-wx-subscribe-state.mjs` 的负向夹具（MA-372：订阅消息落地版本写死 developer）。 */
import { readFileSync } from 'node:fs';
import {
  APP_YML_FILE,
  CLIENT_FILE,
  COMPOSE_FILE,
  PROPERTIES_FILE,
  collectCallers,
  wxSubscribeStateProblems
} from './check-wx-subscribe-state.mjs';

const TAG = '[check-wx-subscribe-state.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const real = {
  client: readFileSync(CLIENT_FILE, 'utf8'),
  properties: readFileSync(PROPERTIES_FILE, 'utf8'),
  appYml: readFileSync(APP_YML_FILE, 'utf8'),
  compose: readFileSync(COMPOSE_FILE, 'utf8'),
  callers: collectCallers()
};

const unpaidKey = Object.keys(real.callers).find((k) => k.endsWith('UnpaidOrderService.java'));
assert(Boolean(unpaidKey), '未能收集到 UnpaidOrderService.java（规则⑤失去判别力）');

assert(real.client.length > 2000, '真实 WeChatMiniAppClient.java 读取失败');
assert(wxSubscribeStateProblems(real).length === 0, '真实仓面必须绿');

// ① 原样回退成硬编码 ⇒ 必红（缺陷本体）
const hardcoded = {
  ...real,
  client: real.client.replace(
    'body.put("miniprogram_state", properties.resolveMiniprogramState());',
    'body.put("miniprogram_state", "developer");'
  )
};
assert(hardcoded.client !== real.client, '注入锚点 miniprogram_state 写入找不到');
assert(
  wxSubscribeStateProblems(hardcoded).some((p) => p.includes('字面量')),
  '硬编码 miniprogram_state 必须红'
);

// ② 配置类回落改成 developer ⇒ 必红（缺省即废消息）
const devFallback = {
  ...real,
  properties: real.properties.replace(': "formal";', ': "developer";')
};
assert(devFallback.properties !== real.properties, '注入锚点 properties 回落 formal 找不到');
assert(
  wxSubscribeStateProblems(devFallback).some((p) => p.includes('不得是 developer')),
  '配置类默认回落 developer 必须红'
);

// ③ yml 默认值改成 developer ⇒ 必红
const ymlDev = {
  ...real,
  appYml: real.appYml.replace(
    '${WECHAT_MINIPROGRAM_STATE:formal}',
    '${WECHAT_MINIPROGRAM_STATE:developer}'
  )
};
assert(ymlDev.appYml !== real.appYml, '注入锚点 application.yml 默认 formal 找不到');
assert(
  wxSubscribeStateProblems(ymlDev).some((p) => p.includes('默认值不得是 developer')),
  'application.yml 默认 developer 必须红'
);

// ④ compose 漏透传 ⇒ 必红（.env 改了容器里仍为空，同型事故）
const noPass = {
  ...real,
  compose: real.compose.replace(
    /\n\s*WECHAT_MINIPROGRAM_STATE: \$\{WECHAT_MINIPROGRAM_STATE:-formal\}/,
    ''
  )
};
assert(noPass.compose !== real.compose, '注入锚点 compose 透传找不到');
assert(
  wxSubscribeStateProblems(noPass).some((p) => p.includes('未透传')),
  'compose 未透传 WECHAT_MINIPROGRAM_STATE 必须红'
);

// ⑤ 消费者消息改回商户模板 ⇒ 必红（MA-375：模板与接收人不匹配，微信 400 后静默失败）
const merchantTpl = {
  ...real,
  callers: {
    ...real.callers,
    [unpaidKey]: real.callers[unpaidKey].replace(
      'weChatMiniAppProperties.resolveConsumerTemplateId()',
      'weChatMiniAppProperties.subscribeTemplateId()'
    )
  }
};
assert(
  merchantTpl.callers[unpaidKey] !== real.callers[unpaidKey],
  '注入锚点 resolveConsumerTemplateId 找不到'
);
assert(
  wxSubscribeStateProblems(merchantTpl).some((p) => p.includes('商户模板')),
  '消费者消息用商户模板必须红'
);

// ⑥ 注释里的旧代码不得误判（否则「删注释」这类改动会假红）
const commented = {
  ...real,
  client: real.client.replace(
    'Map<String, Object> body = new java.util.LinkedHashMap<>();',
    '// 历史写法（错的，勿恢复）："miniprogram_state", "developer"\n        Map<String, Object> body = new java.util.LinkedHashMap<>();'
  )
};
assert(commented.client !== real.client, '注入锚点 buildSubscribeBody 找不到');
assert(wxSubscribeStateProblems(commented).length === 0, '注释里的旧字面量不得误判为红');

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / 五种注入红 / 注释不误判）`);
