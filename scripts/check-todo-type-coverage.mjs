#!/usr/bin/env node
/**
 * 门禁：后端异常类型字典 ↔ 商户端待办四卡归类清单必须一致（R19-10，2026-11-12）。
 *
 * 为什么需要跨端静态判据：待办四卡的归类已改成「穷尽划分 + 未知兜底审核卡」，
 * 所以**漏计**在页面上已经不可能发生；但「新类型被兜底进审核卡是否合理」需要人判断，
 * 而纯前端用例（`todo-list.test.ts` 的 DICT_TYPES 快照）拿后端没有任何约束力——
 * 后端加一个类型，前端快照不跟，用例照样全绿（铁律 75：只钉一侧护不住跨端契约）。
 *
 * 判据（双向，任一不满足即红）：
 *   ① 后端 `SysDictBootstrap` 的 exception_type 全部出现在前端快照里
 *   ② 前端快照里的类型必须都真实存在于后端（防改名后残留死类型）
 *
 * 解析方式：两边的 type code 都是「全大写标识符」形态（Java 侧既有字符串字面量，
 * 也有 `DISPUTE`/`LOW_STOCK` 这类静态常量引用），所以统一按 `[A-Z][A-Z0-9_]{2,}`
 * 抓 token；中文标签、驼峰类名、`DC-27` 这类短片段都不匹配。
 *
 * 用法：node scripts/check-todo-type-coverage.mjs [--self-test]
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.env.GATE_ROOT ? resolve(process.env.GATE_ROOT) : join(dirname(fileURLToPath(import.meta.url)), '..');
const JAVA =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/SysDictBootstrap.java';
const TS = 'clients/merchant-mp/src/utils/todo-list.test.ts';

/** 从排版文本里抓全大写类型码（3 字符起，避免 DC-27/G7 这类注释片段） */
export function extractTypeCodes(text) {
  return new Set([...text.matchAll(/[A-Z][A-Z0-9_]{2,}/g)].map((m) => m[0]));
}

/**
 * 精确解析「type code, "标签"」交替序列里的 code 位。
 *
 * <p>不能用通用大写扫描：中文标签里混着 `XXL-JOB 执行器接线异常` 这类英文片段，
 * 会把 `XXL`/`JOB` 误当类型码。这里只认「大写 token + 逗号 + 引号」的奇数位。
 */
export function extractDictCodes(text) {
  const codes = new Set();
  // 关键：结尾引号用 lookahead，不消耗——否则下一次匹配会丢掉「下一个元素的开引号」，
  // 只能抓到隔一个（R19-10 首版实测：35 类只抽出 17 个）。
  // 末尾元素允许没有尾逗号（`..., 'LAST'\n];`）。
  const re = /(?:["']([A-Z][A-Z0-9_]{2,})["']|([A-Z][A-Z0-9_]{2,}))(?=\s*,\s*["']|\s*$)/g;
  for (const m of text.matchAll(re)) codes.add(m[1] || m[2]);
  return codes;
}

/** 截取 `map.put("<key>"` 到下一个 `map.put("` 之间的块 */
export function sliceDictBlock(src, dictKey) {
  const start = src.indexOf(`map.put("${dictKey}"`);
  if (start < 0) return '';
  const next = src.indexOf('map.put("', start + 1);
  return src.slice(start, next < 0 ? src.length : next);
}

/** 截取 `const NAME = [` 的**方括号内**内容（不含数组名，避免把 DICT_TYPES 自己当元素） */
export function sliceArrayLiteral(src, name) {
  const start = src.indexOf(`const ${name} = [`);
  if (start < 0) return '';
  const open = src.indexOf('[', start);
  const end = src.indexOf('];', open);
  return end < 0 ? '' : src.slice(open + 1, end);
}

/** 双向差。返回 { missingInTs, staleInTs } */
export function compare(javaTypes, tsTypes) {
  return {
    missingInTs: [...javaTypes].filter((t) => !tsTypes.has(t)).sort(),
    staleInTs: [...tsTypes].filter((t) => !javaTypes.has(t)).sort()
  };
}

function runReal() {
  const javaBlock = sliceDictBlock(readFileSync(join(ROOT, JAVA), 'utf8'), 'exception_type');
  const tsBlock = sliceArrayLiteral(readFileSync(join(ROOT, TS), 'utf8'), 'DICT_TYPES');
  if (!javaBlock) {
    console.error(`[check-todo-type-coverage] 在 ${JAVA} 找不到 exception_type 字典块`);
    process.exit(1);
  }
  if (!tsBlock) {
    console.error(`[check-todo-type-coverage] 在 ${TS} 找不到 DICT_TYPES 快照数组`);
    process.exit(1);
  }
  const javaTypes = extractDictCodes(javaBlock);
  const tsTypes = extractDictCodes(tsBlock);
  const { missingInTs, staleInTs } = compare(javaTypes, tsTypes);
  if (missingInTs.length || staleInTs.length) {
    if (missingInTs.length) {
      console.error(
        '[check-todo-type-coverage] 后端有、商户端待办快照缺（会归到兜底「审核」卡，需确认归类）：'
      );
      for (const t of missingInTs) console.error(`  - ${t}`);
    }
    if (staleInTs.length) {
      console.error('[check-todo-type-coverage] 商户端快照有、后端字典已无（死类型，删掉）：');
      for (const t of staleInTs) console.error(`  - ${t}`);
    }
    console.error(`  修复：更新 ${TS} 的 DICT_TYPES 快照，并在 todo-list.ts 确认新类型归哪张卡。`);
    process.exit(1);
  }
  console.log(
    `[check-todo-type-coverage] OK：后端 exception_type ${javaTypes.size} 类与前端快照一一对应`
  );
}

function runSelfTest() {
  let failed = 0;
  const java = new Set(['DISPUTE', 'LOW_STOCK', 'EXPIRY']);
  const tsSame = new Set(['DISPUTE', 'LOW_STOCK', 'EXPIRY']);
  const tsMissing = new Set(['DISPUTE', 'LOW_STOCK']);
  const tsStale = new Set(['DISPUTE', 'LOW_STOCK', 'EXPIRY', 'GONE_TYPE']);

  const ok = compare(java, tsSame);
  if (ok.missingInTs.length || ok.staleInTs.length) {
    console.error('✗ 自测失败：完全一致却被判红');
    failed++;
  }
  const miss = compare(java, tsMissing);
  if (!miss.missingInTs.includes('EXPIRY') || miss.staleInTs.length) {
    console.error('✗ 自测失败：后端多一项未被识别');
    failed++;
  }
  const stale = compare(java, tsStale);
  if (!stale.staleInTs.includes('GONE_TYPE') || stale.missingInTs.length) {
    console.error('✗ 自测失败：前端死类型未被识别');
    failed++;
  }
  // 解析器自测：常量引用与字符串字面量都要抓到，注释里的短片段不能当类型
  const codes = extractTypeCodes('DISPUTE, "消费争议", LOW_STOCK, "低库存", // DC-27 G7\nmap');
  for (const t of ['DISPUTE', 'LOW_STOCK']) {
    if (!codes.has(t)) {
      console.error(`✗ 自测失败：解析器漏抓 ${t}`);
      failed++;
    }
  }
  for (const bad of ['DC', 'G7', 'map']) {
    if (codes.has(bad)) {
      console.error(`✗ 自测失败：解析器误抓 ${bad}`);
      failed++;
    }
  }
  if (failed) {
    console.error(`check-todo-type-coverage --self-test: ${failed} 项失败`);
    process.exit(1);
  }
  console.log('check-todo-type-coverage --self-test: 缺失/死类型/解析器 三向断言全部生效');
}

const invokedDirectly =
  process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1]);

if (invokedDirectly) {
  if (process.argv.includes('--self-test')) {
    runSelfTest();
  } else {
    runReal();
  }
}
