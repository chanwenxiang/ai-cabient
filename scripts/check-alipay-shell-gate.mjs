#!/usr/bin/env node
/**
 * 门禁：alipay-mp-shell（支付宝壳小程序·芝麻先享审核载体）的内容红线。
 *
 * 背景（2026-11-10 四端审计）：壳小程序此前游离在全部前端门禁之外，但它有
 * 两类独有风险，且 README 已写明红线却没有机器执行：
 *   ① 审核红线——壳是**支付宝/芝麻信用审核载体**，出现微信品牌字样/微信小程序
 *      API 会被审核直接驳回（README「注意」节，铁律 12：业务语义真相在文案）；
 *   ② 占位红线——壳内**不得接网络请求/真实订单渲染**（订单页是「守约链接承接页 +
 *      占位态」，如实说明扣款记录请在支付宝账单/信用服务查看）。未来接入真实订单时
 *      该文案与占位形态必然变化 ⇒ 本门禁会红，这正是钩子：改形态必须重新过审评估，
 *      不许悄悄把占位壳变成业务端（铁律 57：新能力归属先看同类入口落在哪）。
 *
 * 判据（对 clients/alipay-mp-shell 下所有 .js/.axml/.acss/.json；README.md 豁免——
 * 它是开发文档不是审核内容）：
 *   1. 微信品牌字样 / 微信小程序 API：微信 | wechat(i) | wx. | uni. | weui —— 本门禁
 *      **有意不抹注释**：审核载体连注释都不该出现跨平台字样（与铁律 84 ① 的
 *      maskComments 原则有意不同，见上）；
 *   2. orders.axml 必须仍含「请在支付宝」+「信用服务」的占位如实说明文案；
 *   3. 壳内禁一切网络调用：my.request / my.httpRequest / my.uploadFile /
 *      my.downloadFile / my.connectSocket / fetch( / XMLHttpRequest；
 *   4. 凭证不落仓：private_key / privateKey / -----BEGIN / app_secret / appSecret；
 *      且 `app_id` 只允许出现在 mini.project.json（APPID 白名单单点）；
 *   5. 防空扫：扫描文件数 < 8 ⇒ 红（目录被移走/清空时静默绿 = 门禁假死）。
 *
 * 用法：node scripts/check-alipay-shell-gate.mjs
 *   （SHELL_GATE_ROOT 环境变量可覆盖扫描根，供负向验证沙箱使用）
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[check-alipay-shell-gate]';
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SHELL_ROOT = process.env.SHELL_GATE_ROOT
  ? process.env.SHELL_GATE_ROOT
  : join(ROOT, 'clients', 'alipay-mp-shell');

const MIN_FILES = 8;

const WECHAT_RE = /微信|wechat|weui|wx\s*\.\s*\w|\buni\s*\.\s*\w/i;
const NET_CALL_RE =
  /\bmy\s*\.\s*(request|httpRequest|uploadFile|downloadFile|connectSocket|websocket)\b|fetch\s*\(|XMLHttpRequest/;
const SECRET_RE = /private_key|privateKey|-----BEGIN|app_secret|appSecret/i;
const APPID_RE = /\bapp_id\b\s*["']?\s*[:=]/;

function walk(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'dist') continue;
      walk(full, out);
      continue;
    }
    // README.md 是开发文档不是审核内容；上面的扩展名正则已把它排除（勿再加 no-op 豁免行）
    if (!/\.(js|axml|acss|json)$/.test(entry.name)) continue;
    out.push(full);
  }
  return out;
}

function lineOf(text, index) {
  let line = 1;
  for (let i = 0; i < index; i += 1) if (text[i] === '\n') line += 1;
  return line;
}

const problems = [];
const files = walk(SHELL_ROOT).map((f) => ({
  path: f,
  rel: relative(SHELL_ROOT, f).replaceAll('\\', '/'),
  text: readFileSync(f, 'utf8')
}));

if (files.length < MIN_FILES) {
  problems.push(
    `扫描到 ${files.length} 个壳文件（< ${MIN_FILES}）——目录被移走/清空时门禁必须红而不是静默绿`
  );
}

for (const { path, rel, text } of files) {
  for (const [re, why] of [
    [
      WECHAT_RE,
      '出现微信品牌字样 / 微信小程序 API——审核载体必须保持支付宝/芝麻口径（README 红线，审核驳回项）'
    ],
    [
      NET_CALL_RE,
      '壳内出现网络请求调用——占位壳禁止接后端；接入真实订单属新能力归属决策，必须先重评（铁律 57）'
    ],
    [SECRET_RE, '壳内出现疑似凭证（private_key/secret/PEM 头）——密钥只能走服务端，绝不落仓']
  ]) {
    const m = re.exec(text);
    if (m) {
      problems.push(`${relative(ROOT, path)}:${lineOf(text, m.index)} ${why}`);
    }
  }
  if (rel !== 'mini.project.json') {
    const m = APPID_RE.exec(text);
    if (m) {
      problems.push(
        `${relative(ROOT, path)}:${lineOf(text, m.index)} app_id 只允许出现在 mini.project.json（白名单单点，防 APPID 泄散）`
      );
    }
  }
}

// 占位如实说明（钉住 2026-11-10 P0 修复形态）：文案一旦变化 = 占位态在变 = 必须重评
const ordersAxml = files.find((f) => f.rel === 'pages/orders/orders.axml');
if (ordersAxml) {
  for (const word of ['请在支付宝', '信用服务']) {
    if (!ordersAxml.text.includes(word)) {
      problems.push(
        `${relative(ROOT, ordersAxml.path)} 占位如实说明丢失「${word}」——订单页必须如实说明扣款记录在支付宝账单/信用服务查看；换成真实订单渲染前必须过审评估`
      );
    }
  }
}

if (problems.length) {
  console.error(`${TAG} FAIL:\n  ${problems.join('\n  ')}\n`);
  process.exit(1);
}
console.log(
  `${TAG} OK：alipay-mp-shell ${files.length} 个文件零命中——支付宝口径、占位如实说明、无网络调用、凭证不落仓`
);
