#!/usr/bin/env node
/**
 * 门禁：微信账单 CSV 解析「丢行必须可见」（2026-11-14 复核修复配套，铁律 75）。
 *
 * 背景：`WeChatBillCsvParser.parse` 旧式
 *     catch (Exception e) { log.debug("skip wechat bill line: {}", line); }
 * 把金额列解析失败与行构造异常整行吞掉，且 debug 级生产不输出 ⇒ 该单变成
 * PLATFORM_ONLY 假差异且无人知晓；与同文件 `parseFeeCents` 注释「费用列异常
 * 只置 null 不丢行，不能因费用列异常把整行从对账里剔除」自相矛盾。
 *
 * 修法（产品拍板「只让丢弃可见」，不猜业务语义）：金额解析独立 try/catch、
 * log.debug→warn、skipped 计数 + 汇总 WARN。**金额语义一律不降级**（不 HALF_UP、
 * 不保留坏行）——YuanFen 拒 scale>2 的保护必须保留。
 *
 * 判据只作用于 `parse` 方法体，先 maskComments 等长抹注释（铁律 84），
 * 不误伤 `parseFeeCents` 里「只置 null 不丢行」的 debug 日志。
 *
 *   node scripts/check-wechat-bill-skip-visibility.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-wechat-bill-skip-visibility]';
const TARGET =
  'services/trade-service/src/main/java/com/aicabinet/trade/reconciliation/WeChatBillCsvParser.java';

/**
 * 等长掩码：注释内容替换为空格，保留换行与全部偏移量（行号不漂移，铁律 84）。
 * 🔴 字符串内容**默认保留**：本门禁判据依赖日志文案字面量（"skip wechat bill line"、
 *    "wechat bill parse finished with skipped rows"），抹掉字符串会永久假绿。
 *    maskStrings=true 只用于括号配平（字符串里的 `{}` 不能参与配平）。
 *    字符串状态原样输出还有一个作用：串内的 `//`、`/*` 不会被误判成注释起点。
 */
export function maskComments(src, maskStrings = false) {
  let out = '';
  let state = 'code';
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    const next = src[i + 1];
    if (state === 'line') {
      if (ch === '\n') {
        state = 'code';
        out += '\n';
      } else out += ' ';
      continue;
    }
    if (state === 'block') {
      if (ch === '*' && next === '/') {
        out += '  ';
        i++;
        state = 'code';
      } else out += ch === '\n' ? '\n' : ' ';
      continue;
    }
    if (state === 'string') {
      if (ch === '\\') {
        out += maskStrings ? '  ' : ch + (next ?? '');
        i++;
      } else if (ch === '"') {
        out += '"';
        state = 'code';
      } else out += maskStrings ? (ch === '\n' ? '\n' : ' ') : ch;
      continue;
    }
    if (ch === '/' && next === '/') {
      out += '  ';
      i++;
      state = 'line';
    } else if (ch === '/' && next === '*') {
      out += '  ';
      i++;
      state = 'block';
    } else if (ch === '"') {
      out += '"';
      state = 'string';
    } else out += ch;
  }
  return out;
}

/** 括号配平抽 `parse(` 方法体（跳过字符串/注释由 maskComments 先行保证）。 */
export function extractParseBody(src) {
  const masked = maskComments(src, true);
  const m = /\bparse\s*\(\s*String\s+csv\s*,/.exec(masked);
  if (!m) return null;
  const open = masked.indexOf('{', m.index);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < masked.length; i++) {
    if (masked[i] === '{') depth++;
    else if (masked[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(open + 1, i);
    }
  }
  return null;
}

/**
 * 纯判定：给 parse 方法体原文，返回问题列表（空=绿）。
 * ① 不得有静默 debug 丢行；② 必须有汇总 WARN；③ 必须有 skipped 声明与自增；
 * ④ 金额解析必须独立 try/catch（YuanFen.toCents 与 result.add 之间有 catch）；
 * ⑤ 金额必须走 YuanFen 单源，且不得出现降级取整（HALF_UP/setScale）。
 */
export function evaluate(parseBody) {
  const problems = [];
  if (parseBody == null) return ['未找到 parse 方法体——文件结构变化，判据需人工复核'];
  const code = maskComments(parseBody);

  if (/log\.debug\s*\(\s*"skip wechat bill line/.test(code)) {
    problems.push('parse 内仍有 log.debug 静默丢行（生产不输出 ⇒ PLATFORM_ONLY 假差异无人知晓）');
  }
  if (!/log\.warn\s*\([\s\S]*?wechat bill parse finished with skipped rows/.test(code)) {
    problems.push('缺少「本次对账跳过 N 行」的汇总 WARN——逐行日志不够，运营看不到缺口总数');
  }
  if (!/\bint\s+skipped\s*=/.test(code)) problems.push('缺少 skipped 计数变量声明');
  if (!/skipped\s*\+\+/.test(code)) problems.push('缺少 skipped 自增（丢行未计数）');

  const amountIdx = code.indexOf('YuanFen.toCents');
  const addIdx = code.indexOf('result.add');
  if (amountIdx < 0) {
    problems.push('parse 内金额换算未走 YuanFen 单源');
  } else if (addIdx < 0) {
    problems.push('parse 内未找到 result.add（结构变化需复核）');
  } else {
    const between = code.slice(amountIdx, addIdx);
    if (!/catch\s*\(\s*RuntimeException/.test(between)) {
      problems.push('金额解析未独立 try/catch：失败仍会被外层大 catch 整行静默吞掉');
    }
  }
  if (/setScale\s*\(|HALF_UP|Math\.round\s*\(/.test(code)) {
    problems.push('parse 内出现金额降级取整——会把三位小数静默算成错账，禁');
  }
  return problems;
}

function main() {
  const src = readFileSync(join(ROOT, TARGET), 'utf8');
  const problems = evaluate(extractParseBody(src));
  if (problems.length > 0) {
    console.error(`${TAG} FAIL：${TARGET}`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(`${TAG} OK：parse 丢行可见（WARN + skipped 计数 + 汇总），金额语义未降级`);
}

// Windows 盘符大小写 / fileURL 解码差异会让全路径比对失效，用文件名后缀判断
if (process.argv[1] && process.argv[1].endsWith('check-wechat-bill-skip-visibility.mjs')) main();
