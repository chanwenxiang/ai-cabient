#!/usr/bin/env node
/** `check-subscribe-banner.mjs` 的负向夹具（2026-11-20 真机反馈）。 */
import { readFileSync } from 'node:fs';
import { MESSAGES_FILE, subscribeBannerProblems } from './check-subscribe-banner.mjs';

const TAG = '[check-subscribe-banner.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const real = readFileSync(MESSAGES_FILE, 'utf8');
assert(real.length > 2000, '真实 messages.vue 读取失败');
assert(subscribeBannerProblems(real).length === 0, '真实仓面必须绿');

const noDismiss = real.replace('!subscribeAcked.value', 'true');
assert(noDismiss !== real, '注入锚点 !subscribeAcked.value 找不到');
assert(
  subscribeBannerProblems(noDismiss).some((p) => p.includes('展示判据')),
  '去掉回执判据必须红（原实炸形态）'
);

const noAck = real.replace('subscribeAcked.value = true;', '');
assert(noAck !== real, '注入锚点 accept 置位找不到');
assert(
  subscribeBannerProblems(noAck).some((p) => p.includes('置 true')),
  'accept 分支不记账必须红'
);

const noPersist = real.replace(/markSubscribeAck\(/g, '');
assert(noPersist !== real, '注入锚点 markSubscribeAck 找不到');
assert(
  subscribeBannerProblems(noPersist).some((p) => p.includes('持久化')),
  '不持久化回执必须红（重启后又劝开）'
);

const noLoad = real.replace(
  'subscribeAcked.value = readSubscribeAck(subscribeTemplateId.value);',
  ''
);
assert(noLoad !== real, '注入锚点 readSubscribeAck 找不到');
assert(
  subscribeBannerProblems(noLoad).some((p) => p.includes('初始化')),
  '载入时不读回执必须红'
);

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / 四种注入红）`);
