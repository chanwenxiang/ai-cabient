#!/usr/bin/env node
/**
 * 门禁：编号类输入框不能用数字键盘类型。
 *
 * 背景（真实缺陷，已修 3 处）：消费者端「柜机编号」输入框写的是 uni-app 的
 * `type="digit"`，H5 下被渲染成 `<input type="number">`，浏览器**直接丢弃字母**。
 * 实测：向该框输入 `CAB-001` 只能得到 `-001`（字母被丢、连字符被当成负号），
 * 而输入纯数字 `330449777078` 正常 —— 说明是 input type 的限制，不是输入法或脚本问题。
 *
 * 而柜机编号本身**不是纯数字**：`device_info.device_id` 既有 `CAB-001` 这类
 * 字母+连字符的编号，也有纯数字编号。所以「柜机编号」字段用 `digit`/`number`
 * 会让用户在 H5（以及小程序的数字键盘）上**根本打不出正确编号**，
 * 手动输入开门、故障报修、意见反馈三条链路全部不可用。
 *
 * 本门禁把这条隐式契约钉死：标签含「编号 / 柜机 / 设备号 / 单号」的输入框，
 * 不得使用 `type="digit"` 或 `type="number"`。
 *
 * 豁免：在输入框标签行或其后 3 行内写 `<!-- check-device-code-input: ignore -->`。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');

const SCAN_DIRS = ['clients/consumer-mp/src', 'clients/merchant-mp/src'];
/** 标签里出现这些词 → 说明该输入框承载的是「编号」而非金额/电话/数量 */
const CODE_LABEL_RE = /编号|柜机编号|设备号|单号/;
/** 只拦「整数/小数键盘」这两种类型；金额、库存、手机号属正常用法 */
const NUMERIC_TYPES = new Set(['digit', 'number']);
const IGNORE_MARK = 'check-device-code-input: ignore';
/** 往上找标签行的最大跨度（模板里 label 与 input 常隔 1~3 行） */
const LABEL_LOOKBACK = 4;
/** 判定「这一行是标签行」：带 label 语义的 class，或 aria-label */
const LABEL_LINE_RE = /class\s*=\s*["'][^"']*(?:-\s*)?label[^"']*["']|aria-label\s*=/i;

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p, out);
    else if (/\.(vue|nvue)$/.test(entry.name)) out.push(p);
  }
  return out;
}

/** 取出以 startLine 开头的 <input .../> 标签文本与结束行 */
function readTag(lines, startLine) {
  let text = '';
  let i = startLine;
  for (; i < lines.length; i++) {
    text += lines[i];
    if (/>/.test(lines[i])) break;
    text += '\n';
  }
  return { text, endLine: i };
}

function findViolations(file, lines) {
  const hits = [];
  for (let i = 0; i < lines.length; i++) {
    if (!/<input\b/.test(lines[i])) continue;
    const { text, endLine } = readTag(lines, i);
    const match = text.match(/\btype\s*=\s*["']([^"']+)["']/);
    if (!match || !NUMERIC_TYPES.has(match[1].trim())) continue;

    // 只认同「标签行」：向上一层找最近的 label 语义行，或退回用标签自身的 aria-label。
    // 不能用一整段窗口做关键词匹配 —— 否则同卡片上方的「柜机设置」段落标题
    // 会把下面的「目标温度」输入框误判成编号字段（已实测踩到）。
    let labelLine = -1;
    let labelText = '';
    for (let j = i - 1; j >= Math.max(0, i - LABEL_LOOKBACK); j--) {
      if (LABEL_LINE_RE.test(lines[j])) {
        labelLine = j;
        labelText = lines[j].replace(/<[^>]+>/g, '').trim();
        break;
      }
    }
    const ownLabel = (text.match(/aria-label\s*=\s*["']([^"']+)["']/) || [])[1] || '';
    const haystack = [labelText, ownLabel, text].join('\n');
    if (!CODE_LABEL_RE.test(haystack)) continue;
    if (haystack.includes(IGNORE_MARK)) continue;
    if (
      labelLine >= 0 &&
      lines
        .slice(labelLine, endLine + 1)
        .join('\n')
        .includes(IGNORE_MARK)
    )
      continue;

    hits.push({
      line: i + 1,
      type: match[1].trim(),
      label: labelText || ownLabel || '(未取到标签文本)'
    });
  }
  return hits;
}

const violations = [];
let scanned = 0;
for (const rel of SCAN_DIRS) {
  for (const file of walk(path.join(ROOT, rel))) {
    scanned++;
    const relFile = path.relative(ROOT, file).replace(/\\/g, '/');
    for (const hit of findViolations(relFile, fs.readFileSync(file, 'utf8').split(/\r?\n/))) {
      violations.push({ file: relFile, ...hit });
    }
  }
}

if (violations.length) {
  console.error(
    `FAIL check-device-code-input：${violations.length} 处「编号类」输入框使用了数字键盘类型`
  );
  for (const v of violations) {
    console.error(`  ${v.file}:${v.line}  label="${v.label}"  type="${v.type}"`);
  }
  console.error(
    '\n原因：uni-app `type="digit"`/`type="number"` 在 H5 渲染为 <input type="number">，' +
      '浏览器会丢弃字母，用户无法输入 `CAB-001` 这类含字母的编号。\n' +
      '修复：改为 `type="text"`（小程序端 text 是全部键盘、H5 端是原生文本框）。\n' +
      `确属纯数字编号时，在标签或输入框处加注释豁免：<!-- ${IGNORE_MARK} -->`
  );
  process.exit(1);
}

console.log(
  `PASS check-device-code-input：扫描 ${scanned} 个 .vue，无编号类字段误用数字键盘类型。`
);
