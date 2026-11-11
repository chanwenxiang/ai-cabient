#!/usr/bin/env node
/**
 * check-seq-success-path-guard.mjs — R11-A-N2 门禁（收窄形态）
 *
 * 判据（贴真实责任边界，按 2026-11-11 R11 拍板收窄）：
 *   只扫「已有 seq 代际守卫但只挂了 catch/finally 半边」的函数——即函数体内
 *   同时出现 `loadSeq.begin(`（或 `= loadSeq.begin()`）与 `isCurrent(` 判定，
 *   但 try 块内存在「await 之后、`.value =` 赋值之前没有 isCurrent 判定」的
 *   成功路径赋值。旧响应晚到会回写列表/total/清勾选。
 *
 * 硬要求（铁律 84）：
 *   1. 扫源码前 maskComments() 抹注释（替换为等长空格保行号），注释里的示例写法不假红；
 *   2. 判据贴真实责任边界：只分析「begin + isCurrent 双证据」函数的 try 区段，
 *      无守卫义务的一次性加载/幂等重取（无 begin）不在此列；
 *   3. --self-test 内置三态自测（红用例 / 绿用例 / 注释不触发）。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TARGET_DIRS = [join(ROOT, 'clients/admin-vue/src')];
const EXTS = new Set(['.vue', '.ts', '.tsx']);

/** 把注释替换为等长空白（保行号与列数），防止注释内示例触发判据。 */
export function maskComments(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const two = src.slice(i, i + 2);
    if (two === '//') {
      let j = src.indexOf('\n', i);
      if (j === -1) j = n;
      out += ' '.repeat(j - i);
      i = j;
      continue;
    }
    if (two === '/*') {
      let j = src.indexOf('*/', i + 2);
      j = j === -1 ? n : j + 2;
      const seg = src.slice(i, j);
      out += seg
        .split('')
        .map((c) => (c === '\n' ? '\n' : ' '))
        .join('');
      i = j;
      continue;
    }
    if (two === "''" || two === '""') {
      out += two;
      i += 2;
      continue;
    }
    const ch = src[i];
    if (ch === '"' || ch === "'" || ch === '`') {
      // 字符串/模板串原样保留（内部可能有 // 注释样文本，不抹——字符串里写代码不是注释）
      let j = i + 1;
      while (j < n) {
        if (src[j] === '\\') {
          j += 2;
          continue;
        }
        if (src[j] === ch) break;
        if (ch === '`' && src.slice(j, j + 2) === '${') {
          // 模板插值内可能含注释，跳过配对大括号
          let depth = 1;
          let k = j + 2;
          while (k < n && depth > 0) {
            if (src[k] === '{') depth++;
            else if (src[k] === '}') depth--;
            k++;
          }
          j = k;
          continue;
        }
        j++;
      }
      out += src.slice(i, Math.min(j + 1, n));
      i = j + 1;
      continue;
    }
    if (src.slice(i, i + 4) === '<!--') {
      let j = src.indexOf('-->', i + 4);
      j = j === -1 ? n : j + 3;
      const seg = src.slice(i, j);
      out += seg
        .split('')
        .map((c) => (c === '\n' ? '\n' : ' '))
        .join('');
      i = j;
      continue;
    }
    out += ch;
    i++;
  }
  return out;
}

/** 按 await 分段扫描：返回违规赋值列表 [{ line, text, fn }]。 */
export function findViolations(masked, fileName) {
  const violations = [];
  const lines = masked.split('\n');

  // 1) 收集候选函数块：以 async function / const x = async ( 起始，大括号配对取块
  const fns = [];
  const fnStartRe = /^(\s*)(?:async\s+function\s+\w+|(?:const|let)\s+\w+\s*=\s*async\b)/;
  for (let li = 0; li < lines.length; li++) {
    if (!fnStartRe.test(lines[li])) continue;
    // 从起始行开始做大括号配对（忽略字符串——已 mask，字符串仍含 {}? 已原样保留，
    // 但对象字面量等真实代码才驱动计数；模板串内 ${} 已跳过处理不适用此处，可接受）
    let depth = 0;
    let started = false;
    let end = li;
    for (let k = li; k < lines.length && k < li + 400; k++) {
      for (const c of lines[k]) {
        if (c === '{') {
          depth++;
          started = true;
        } else if (c === '}') {
          depth--;
        }
      }
      if (started && depth <= 0) {
        end = k;
        break;
      }
    }
    if (!started) continue;
    fns.push({ start: li, end });
    li = end; // 不嵌套重复扫描
  }

  for (const { start, end } of fns) {
    const body = lines.slice(start, end + 1);
    const hasBegin = body.some(
      (l) => /loadSeq\s*\.\s*begin\s*\(/.test(l) || /\.\s*begin\s*\(\s*'/.test(l)
    );
    const guardCount = body.filter((l) => /isCurrent\s*\(/.test(l)).length;
    if (!hasBegin || guardCount === 0) continue; // 无守卫义务 / 守卫只挂半边之外的不在判据内

    const nameMatch = body[0].match(
      /(?:function\s+|=\s*async\s*(?:\([^)]*\)\s*=>|function))\s*(\w+)?/
    );
    const fnName = nameMatch?.[1] || `line ${start + 1}`;

    // 2) 提取 try 区段（大括号配对）
    const tryStarts = [];
    for (let k = 0; k < body.length; k++) {
      if (/\btry\s*\{/.test(body[k])) {
        let depth = 0;
        let started = false;
        let tEnd = k;
        for (let m = k; m < body.length; m++) {
          for (const c of body[m]) {
            if (c === '{') {
              depth++;
              started = true;
            } else if (c === '}') depth--;
          }
          if (started && depth <= 0) {
            tEnd = m;
            break;
          }
        }
        if (started) {
          tryStarts.push([k, tEnd]);
          k = tEnd;
        }
      }
    }

    for (const [ts, te] of tryStarts) {
      // 3) 在 try 区段内：每个 .value = 赋值，其上方最近的 isCurrent 判定必须晚于
      //    上方最近的 await 行（防护必须覆盖最近的等待点）
      let lastGuardLine = -1;
      let lastAwaitLine = -1;
      for (let k = ts; k <= te; k++) {
        const line = body[k];
        if (/isCurrent\s*\(/.test(line)) lastGuardLine = k;
        if (/\bawait\b/.test(line)) lastAwaitLine = k;
        if (/\.\s*value\s*=(?!=)/.test(line)) {
          // 本行含 await（`x.value = await f()` 形态）＝赋值发生在等待之后，同样无防护；
          // 或本行纯赋值但上方存在未覆盖的 await 等待点 → 红
          const hasAwaitOnLine = /\bawait\b/.test(line);
          if (hasAwaitOnLine || (lastAwaitLine !== -1 && lastAwaitLine > lastGuardLine)) {
            violations.push({
              file: fileName,
              line: start + k + 1,
              fn: fnName,
              text: line.trim().slice(0, 120)
            });
          }
        }
      }
    }
  }
  return violations;
}

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) yield* walk(p);
    else if (EXTS.has(name.slice(name.lastIndexOf('.')))) yield p;
  }
}

function run() {
  if (process.argv.includes('--self-test')) return selfTest();
  const all = [];
  for (const dir of TARGET_DIRS) {
    for (const f of walk(dir)) {
      const masked = maskComments(readFileSync(f, 'utf8'));
      all.push(...findViolations(masked, f.replace(ROOT + '/', '').replace(/\\/g, '/')));
    }
  }
  if (all.length) {
    console.error(
      `[check-seq-success-path-guard] FAIL：${all.length} 处「守卫只挂 catch 半边」的成功路径赋值：`
    );
    for (const v of all) console.error(`  - ${v.file}:${v.line} (${v.fn}) ${v.text}`);
    console.error(
      "修法：成功赋值前补 `if (!loadSeq.isCurrent(seq, '<channel>')) return;`（照 useCrudTable.load 口径）"
    );
    process.exit(1);
  }
  console.log('[check-seq-success-path-guard] OK：无「catch 判定而 try 内赋值前未判定」的残留');
}

function selfTest() {
  const red = [
    'async function loadLedger() {',
    "  const seq = loadSeq.begin('loadLedger');",
    '  try {',
    '    const data = await api.get();',
    "    if (!loadSeq.isCurrent(seq, 'loadLedger')) return; // catch-only 形态：判定在上方但后面又有 await",
    '    const more = await api.get2();',
    '    ledger.value = more.items;', // await 后无判定 → 红
    "  } catch (e) { if (!loadSeq.isCurrent(seq, 'loadLedger')) return; }",
    '  }'
  ].join('\n');
  const green = [
    'async function loadLedger() {',
    "  const seq = loadSeq.begin('loadLedger');",
    '  try {',
    '    const data = await api.get();',
    "    if (!loadSeq.isCurrent(seq, 'loadLedger')) return;",
    '    const more = await api.get2();',
    "    if (!loadSeq.isCurrent(seq, 'loadLedger')) return;",
    '    ledger.value = more.items;',
    "  } catch (e) { if (!loadSeq.isCurrent(seq, 'loadLedger')) return; }",
    '  }'
  ].join('\n');
  const commentTrap = [
    'async function loadX() {',
    "  const seq = loadSeq.begin('x');",
    '  try {',
    '    // 示例（注释不应触发）：const d = await api.get(); list.value = d.items;',
    '    const d = await api.get();',
    "    if (!loadSeq.isCurrent(seq, 'x')) return;",
    '    list.value = d.items;',
    "  } catch (e) { if (!loadSeq.isCurrent(seq, 'x')) return; }",
    '  }'
  ].join('\n');

  const inlineAwait = [
    'async function loadStatus() {',
    "  const seq = loadSeq.begin('loadStatus');",
    '  try {',
    "    psStatus.value = await api.request('u');", // 同行 assign+await，前面无 await → 也须红
    "  } catch (e) { if (!loadSeq.isCurrent(seq, 'loadStatus')) return; }",
    '  }'
  ].join('\n');

  const redV = findViolations(maskComments(red), 'self-test/red.vue');
  const greenV = findViolations(maskComments(green), 'self-test/green.vue');
  const trapV = findViolations(maskComments(commentTrap), 'self-test/trap.vue');
  const inlineV = findViolations(maskComments(inlineAwait), 'self-test/inline.vue');
  const ok = redV.length === 1 && greenV.length === 0 && trapV.length === 0 && inlineV.length === 1;
  if (!ok) {
    console.error('[check-seq-success-path-guard] SELF-TEST FAIL');
    console.error(' red:', JSON.stringify(redV));
    console.error(' green:', JSON.stringify(greenV));
    console.error(' trap:', JSON.stringify(trapV));
    console.error(' inline:', JSON.stringify(inlineV));
    process.exit(1);
  }
  console.log(
    '[check-seq-success-path-guard] self-test OK（红 1 / 绿 0 / 注释陷阱 0 / 同行await红 1）'
  );
}

run();
