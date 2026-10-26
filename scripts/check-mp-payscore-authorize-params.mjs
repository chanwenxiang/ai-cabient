#!/usr/bin/env node
/**
 * 门禁：小程序端拉起微信支付分授权页必须用 **extraData** 形态（2026-10-26 CB-067 实证）。
 *
 * 🔴 缺陷类（铁律 75：修的是这一类，不是这一处）：
 *   微信支付分「免确认模式」拉起授权页有**两套形态**：
 *     · 小程序端 `wx.openBusinessView({ businessType, extraData:{ apply_permissions_token } })`
 *       —— 官方《wx.openBusinessView（免确认模式·小程序端）》
 *         https://pay.weixin.qq.com/doc/v3/partner/4012677915
 *     · 公众号 H5 端 `wx.invoke('openBusinessView', { businessType, queryString:'apply_permissions_token=...' })`
 *       —— 官方《JSAPI 调起支付分授权页》
 *         https://pay.weixin.qq.com/doc/v3/merchant/4012647371
 *   两者的**参数容器不同**（extraData 对象 vs queryString 字符串）。小程序端 `wx.openBusinessView`
 *   的参数表只有 `businessType` + `extraData`，**没有 queryString** ⇒ 写成 queryString 真机报
 *   「非法的参数」，授权页永远拉不起来 ⇒ 「开通免密支付」按钮静默失效。
 *
 *   本仓此前在 `pages/verify/verify.vue` 抄了 JSAPI/H5 端的 queryString 形态（CB-064 订正字段时
 *   一并抄串），属**载体形态混淆**：字段名对、容器错。
 *
 * 判据（与写侧口径一致，铁律 51）：
 *   对每个小程序端源码中出现的 `openBusinessView` 调用：
 *     ① 必须出现 `extraData` 且其内含 `apply_permissions_token`；
 *     ② **不得**出现 `queryString`（含 `apply_permissions_token=` 拼接形态）；
 *     ③ `businessType` 必须为 `wxpayScoreEnable`。
 *   注释里的形态说明**不计入**（注释是解释，不是调用）⇒ 判据按「去掉行注释/块注释后」再匹配。
 *
 * 自检：内置 mini fixture 走判据函数（防恒真/恒假），任何断言失败即 exit 1。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mp-payscore-authorize-params]';
const MP_APPS = ['clients/consumer-mp/src', 'clients/merchant-mp/src'];
const EXT = new Set(['.vue', '.ts', '.js', '.tsx', '.jsx']);

/** 去掉注释（行注释 + 块注释），避免把「形态说明注释」当成真实调用。 */
export function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

/**
 * 校验单文件里所有 openBusinessView 调用。
 * @returns {{ok: boolean, reasons: string[]}}
 */
export function checkFile(src) {
  const reasons = [];
  const code = stripComments(src);
  if (!code.includes('openBusinessView')) return { ok: true, reasons };

  // 逐个调用片段：以 openBusinessView( 为锚点，取其后的一层对象字面量（花括号配平）。
  let idx = 0;
  while ((idx = code.indexOf('openBusinessView', idx)) >= 0) {
    const open = code.indexOf('{', idx);
    if (open < 0) break;
    let depth = 0;
    let end = open;
    for (let k = open; k < code.length; k++) {
      if (code[k] === '{') depth++;
      else if (code[k] === '}') {
        depth--;
        if (depth === 0) {
          end = k;
          break;
        }
      }
    }
    const call = code.slice(open, end + 1);

    if (!/extraData\s*:/.test(call)) {
      reasons.push('openBusinessView 缺 extraData（小程序端参数表只有 businessType + extraData）');
    } else if (!/apply_permissions_token/.test(call)) {
      reasons.push('extraData 内缺 apply_permissions_token');
    }
    if (/queryString\s*:/.test(call)) {
      reasons.push(
        'openBusinessView 出现 queryString（那是公众号 H5/JSAPI 端形态，小程序端会报「非法的参数」）'
      );
    }
    if (!/businessType\s*:\s*['"]wxpayScoreEnable['"]/.test(call)) {
      reasons.push("businessType 必须是 'wxpayScoreEnable'");
    }
    idx = end + 1;
  }
  return { ok: reasons.length === 0, reasons };
}

/** 递归收集源码文件。 */
function collect(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    if (name === 'node_modules' || name === 'dist' || name === 'unpackage') continue;
    const full = join(dir, name);
    let st;
    try {
      st = statSync(full);
    } catch {
      continue;
    }
    if (st.isDirectory()) collect(full, out);
    else if (EXT.has(name.slice(name.lastIndexOf('.')))) out.push(full);
  }
  return out;
}

// ── 自检（防恒真/恒假）：判据函数对合成样本必须给出预期结果 ──
(function selftest() {
  const fail = (m) => {
    console.error('[selftest] FAIL: ' + m);
    process.exit(1);
  };
  const good = `wx.openBusinessView({ businessType: 'wxpayScoreEnable', extraData: { apply_permissions_token: t } });`;
  if (!checkFile(good).ok) fail('合法 extraData 形态应通过');

  const badQs = `wx.openBusinessView({ businessType: 'wxpayScoreEnable', queryString: 'apply_permissions_token=' + t });`;
  if (checkFile(badQs).ok) fail('queryString 形态必须判红');

  const missingToken = `wx.openBusinessView({ businessType: 'wxpayScoreEnable', extraData: { foo: t } });`;
  if (checkFile(missingToken).ok) fail('extraData 缺 token 必须判红');

  const missingExtra = `wx.openBusinessView({ businessType: 'wxpayScoreEnable' });`;
  if (checkFile(missingExtra).ok) fail('缺 extraData 必须判红');

  const wrongType = `wx.openBusinessView({ businessType: 'wxpayScoreUse', extraData: { apply_permissions_token: t } });`;
  if (checkFile(wrongType).ok) fail('businessType 错误必须判红');

  // 恒真陷阱：注释里出现 queryString 不该判红
  const onlyComment = `// 这里原本写成 queryString: 'apply_permissions_token=' + t\nconst x = 1;`;
  if (!checkFile(onlyComment).ok) fail('纯注释不得判红');

  // 块注释同理
  const blockComment = `/* 旧写法 queryString: 'apply_permissions_token=' */\nconst y = 2;`;
  if (!checkFile(blockComment).ok) fail('块注释不得判红');

  // 无调用文件必须通过（恒假陷阱）
  if (!checkFile('const a = 1;').ok) fail('无调用必须通过');

  // 多个调用都要查
  const two =
    `wx.openBusinessView({ businessType: 'wxpayScoreEnable', extraData: { apply_permissions_token: t } });\n` +
    `wx.openBusinessView({ businessType: 'wxpayScoreEnable', queryString: 'x' });`;
  if (checkFile(two).ok) fail('第二个调用违规必须判红');
})();

let violations = 0;
for (const app of MP_APPS) {
  for (const file of collect(join(ROOT, app))) {
    const { ok, reasons } = checkFile(readFileSync(file, 'utf8'));
    if (!ok) {
      violations++;
      console.error(`${TAG} FAIL ${relative(ROOT, file)}`);
      for (const r of reasons) console.error(`${TAG}   - ${r}`);
    }
  }
}

if (violations > 0) {
  console.error(
    `${TAG} FAIL：${violations} 个文件的小程序端授权页参数形态不合规（须 extraData + apply_permissions_token）。`
  );
  process.exit(1);
}
console.log(
  `${TAG} OK：小程序端授权页参数形态合规（extraData + apply_permissions_token，无 queryString）。`
);
