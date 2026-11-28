#!/usr/bin/env node
/**
 * 门禁：消息中心「开启微信消息提醒」横幅必须认订阅回执（2026-11-20 真机反馈）。
 *
 * 实炸：`showSubscribeBanner` 只看后台配置开关 + 模板 id，与用户是否已点「去开启」
 * 完全无关 ⇒ 授权成功后 toast 说「已开启」，横幅却还在。
 * 微信没有查询订阅状态的接口，唯一权威信号是 requestSubscribeMessage 的 accept 回执，
 * 故本地按模板 id 记账，三处必须同时存在：展示判据排除已授权 / 成功分支写账 / 载入时读账。
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const MESSAGES_FILE = join(ROOT, 'clients/consumer-mp/src/pages/messages/messages.vue');
const TAG = '[check-subscribe-banner]';

export function subscribeBannerProblems(raw) {
  const src = raw
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');
  const problems = [];
  const computed = /const showSubscribeBanner = computed\(\s*([\s\S]*?)\n\);/.exec(src);
  if (!computed) {
    problems.push('找不到 showSubscribeBanner computed（结构已变，门禁失去判别力）');
  } else if (!/!\s*subscribeAcked\.value/.test(computed[1])) {
    problems.push('展示判据必须排除已授权模板（!subscribeAcked.value）');
  }
  const ack = /subscribeAcked\.value\s*=\s*result\.items\.some\(([\s\S]*?)\);/.exec(src);
  if (
    !ack ||
    !/item\.status\s*===\s*'accept'/.test(ack[1]) ||
    !/item\.tmplId\s*===\s*subscribeTemplateId\.value/.test(ack[1])
  ) {
    problems.push('当前模板的 accept 回执必须把 subscribeAcked 置 true');
  }
  if (!/if\s*\(item\.status\s*===\s*'accept'\)\s*markSubscribeAck\(item\.tmplId\)/.test(src)) {
    problems.push('accept 分支必须调用 markSubscribeAck 持久化回执');
  }
  if (!/subscribeAcked\.value\s*=\s*readSubscribeAck\s*\(/.test(src)) {
    problems.push('load() 必须用 readSubscribeAck 初始化本机回执');
  }
  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const problems = subscribeBannerProblems(readFileSync(MESSAGES_FILE, 'utf8'));
  if (problems.length) {
    console.error(`${TAG} FAIL:`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(`${TAG} OK：订阅横幅认回执，授权后不再重复劝开`);
}
