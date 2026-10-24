#!/usr/bin/env node
/**
 * Mockito 写法门禁：测试源码里禁止三类「编译错 / 静默失配 / 重载歧义」的坑
 * （lessons #296、#322，均真实复现过）。
 *
 *   1. `when(void方法(...))` —— void 不能 stub，编译报「空类型」；用 doNothing/doThrow。
 *   2. matcher 与形参类型不符 —— `anyInt()` 桩 `long` 形参能编译，但运行时实参是
 *      `Long`，matcher 匹配不上 ⇒ 桩静默不生效（曾表现为 409 假象）。long 用 anyLong。
 *   3. `delete(any())` 重载歧义 —— `BaseMapper` 同时有 `delete(Wrapper)` / `delete(T)`，
 *      裸 `any()` 两处都能推 ⇒ 编译歧义；须 `(实体) any()` 或 `any(Wrapper.class)`。
 *
 * 方法签名靠全仓 main+test 源码建索引；同名同参数个数的候选若形参/返回类型不一致就
 * 跳过该调用点（宁可漏报不假阳）。锚点：测试文件数、stub/verify 点数、成功解析的
 * matcher 调用数各设下限——解析器退化时零命中是假绿。
 *
 *   node scripts/check-test-mockito-pitfalls.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-test-mockito-pitfalls]';
const MIN_TEST_FILES = 350;
const MIN_STUB_SITES = 1000;
const MIN_RESOLVED_CALLS = 500;

const MATCHER_FAMILIES = {
  anyInt: ['int', 'Integer'],
  anyLong: ['long', 'Long'],
  anyShort: ['short', 'Short'],
  anyByte: ['byte', 'Byte'],
  anyDouble: ['double', 'Double'],
  anyFloat: ['float', 'Float'],
  anyBoolean: ['boolean', 'Boolean'],
  anyChar: ['char', 'Character'],
  anyString: ['String'],
  anyList: ['List'],
  anyMap: ['Map'],
  anySet: ['Set'],
  anyCollection: ['Collection'],
  anyIterable: ['Iterable']
};
const SCALARS = new Set([
  'int',
  'Integer',
  'long',
  'Long',
  'short',
  'Short',
  'byte',
  'Byte',
  'double',
  'Double',
  'float',
  'Float',
  'boolean',
  'Boolean',
  'char',
  'Character'
]);
const CONTROL = new Set([
  'if',
  'for',
  'while',
  'switch',
  'catch',
  'return',
  'new',
  'assert',
  'synchronized',
  'do',
  'else',
  'try',
  'throw',
  'super',
  'this'
]);

/** 注释掩成空格（保留换行与列）；字符串原样保留。 */
export function stripJavaComments(source) {
  let out = '';
  let state = 'code';
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    const n = source[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') {
        out += '  ';
        i++;
        state = 'line';
      } else if (c === '/' && n === '*') {
        out += '  ';
        i++;
        state = 'block';
      } else if (c === '"') {
        out += '"';
        state = 'str';
      } else if (c === "'") {
        out += "'";
        state = 'char';
      } else {
        out += c;
      }
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        out += '\n';
        state = 'code';
      } else {
        out += ' ';
      }
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') {
        out += '  ';
        i++;
        state = 'code';
      } else {
        out += c === '\n' ? '\n' : ' ';
      }
      continue;
    }
    if (c === '\\' && i + 1 < source.length) {
      out += c + source[++i];
      continue;
    }
    out += c;
    if ((state === 'str' && c === '"') || (state === 'char' && c === "'")) {
      state = 'code';
    }
  }
  return out;
}

export function findMatching(src, open) {
  const pair = src[open] === '(' ? ')' : '}';
  let depth = 0;
  let inStr = false;
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      if (c === '\\') {
        i++;
        continue;
      }
      if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      continue;
    }
    if (c === src[open]) depth++;
    else if (c === pair) {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

export function lineOf(src, index) {
  let line = 1;
  for (let i = 0; i < index; i++) if (src[i] === '\n') line++;
  return line;
}

/** 顶层逗号切分（忽略 () <> [] 内部与字符串）。 */
export function splitTopLevel(text, sep = ',') {
  const parts = [];
  let depth = 0;
  let inStr = false;
  let last = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inStr) {
      if (c === '\\') i++;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      continue;
    }
    if (c === '(' || c === '<' || c === '[' || c === '{') depth++;
    else if (c === ')' || c === '>' || c === ']' || c === '}') depth--;
    else if (c === sep && depth === 0) {
      parts.push(text.slice(last, i));
      last = i + 1;
    }
  }
  parts.push(text.slice(last));
  return parts.map((p) => p.trim()).filter((p) => p.length > 0);
}

/** 形参声明 → 简单类型名（去注解/final/varargs/泛型/数组）。 */
export function paramType(decl) {
  let t = String(decl || '').trim();
  t = t.replace(/@[\w$.]+(?:\s*\([^)]*\))?\s*/g, '');
  t = t.replace(/\bfinal\s+/g, '');
  t = t.replace(/\.\.\./g, '[]');
  t = t.replace(/\s+/g, ' ');
  const tokens = t.split(' ');
  if (tokens.length < 2) return tokens[0] || '';
  const type = tokens.slice(0, -1).join(' ');
  return simpleType(type);
}

export function simpleType(type) {
  let t = String(type || '').replace(/\s+/g, '');
  t = t.replace(/<.*>/g, '');
  t = t.replace(/\[\]/g, '');
  const segs = t.split('.');
  return segs[segs.length - 1] || t;
}

/**
 * 全仓方法签名索引：方法名 → [{params:[type], returnsVoid, ret}]。
 * 只做签名收集，不区分所属类；解析调用点时要求候选唯一。
 */
export function buildMethodIndex(sources) {
  const index = new Map();
  const re =
    /(?:^|[\n;{}])\s*(?:(?:public|protected|private|static|final|synchronized|default|abstract|native|strictfp)\s+)*(?:<[^>{};()]*>\s*)?([A-Za-z_$][\w$.]*(?:\s*<[^;{}()]*>)?(?:\s*\[\s*\])?)\s+([A-Za-z_$][\w$]*)\s*\(/g;
  for (const masked of sources) {
    let m;
    while ((m = re.exec(masked))) {
      const name = m[2];
      if (CONTROL.has(name)) continue;
      const open = m.index + m[0].length - 1;
      const close = findMatching(masked, open);
      if (close < 0) continue;
      const after = masked.slice(close + 1, close + 200);
      if (!/^\s*(?:throws\s+[\w$.,\s]+)?[{;]/.test(after)) continue;
      const params = splitTopLevel(masked.slice(open + 1, close)).map(paramType);
      const ret = m[1].replace(/\s+/g, '');
      const list = index.get(name) ?? [];
      list.push({ params, ret, returnsVoid: /^void$/.test(ret) });
      index.set(name, list);
    }
  }
  return index;
}

/** 候选签名唯一 → 返回它；否则 null（宁可漏报）。 */
export function uniqueSignature(index, name, arity) {
  const candidates = (index.get(name) ?? []).filter((s) => s.params.length === arity);
  if (candidates.length === 0) return null;
  const key = (s) => `${s.ret}|${s.params.join(',')}`;
  const first = key(candidates[0]);
  if (!candidates.every((s) => key(s) === first)) return null;
  return candidates[0];
}

/** 从调用实参里识别单个 matcher（`anyInt()` / `Mockito.anyLong()`）。 */
export function matcherName(arg) {
  const m = /^(?:Mockito\.|ArgumentMatchers\.)?([a-zA-Z]+)\s*\(\s*\)$/.exec(
    String(arg || '').trim()
  );
  return m ? m[1] : null;
}

const MATCHER_EXACT = new Set([
  'eq',
  'isNull',
  'isNotNull',
  'notNull',
  'nullable',
  'same',
  'argThat',
  'contains',
  'startsWith',
  'endsWith',
  'matches',
  'isA'
]);

export function matcherFor(declared) {
  for (const [name, family] of Object.entries(MATCHER_FAMILIES)) {
    if (family.includes(declared)) return name;
  }
  return null;
}

/** 实参是否调用了 matcher（含 `any(X.class)` / `(X) any()` 这类带参形态）。 */
export function isMatcherArg(arg) {
  const text = String(arg || '').trim();
  if (/^\([^()]+\)\s*(?:Mockito\.|ArgumentMatchers\.)?[a-zA-Z]+\s*\(/.test(text)) return true;
  const m = /^(?:Mockito\.|ArgumentMatchers\.)?([a-zA-Z]+)\s*\(/.exec(text);
  if (!m) return false;
  return m[1].startsWith('any') || MATCHER_EXACT.has(m[1]);
}

/** 接收者类型表：`private FooMapper fooMapper;` / `Foo foo = mock(Foo.class)`。 */
export function receiverTypes(masked) {
  const map = new Map();
  const re =
    /(?:^|[\n;{}(])\s*(?:@[\w$.]+(?:\s*\([^)]*\))?\s*)*(?:private|protected|public)?\s*(?:final\s+)?(?:static\s+)?([A-Z][\w$]*)(?:\s*<[^;{}()=]*>)?\s+([a-z_$][\w$]*)\s*(?:=|;)/g;
  let m;
  while ((m = re.exec(masked))) map.set(m[2], m[1]);
  return map;
}

function findOpenBackwards(src, close) {
  let depth = 0;
  for (let i = close; i >= 0; i--) {
    const c = src[i];
    if (c === ')') depth++;
    else if (c === '(') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

const CHAIN_HOSTS = new Set(['verify', 'given', 'lenient', 'when']);

/**
 * 收集带点号的方法调用。两种形态都收：
 *   `mock.method(...)`（直接）
 *   `verify(mock).method(...)` / `given(mock).method(...)`（接收者写在宿主调用里）
 */
export function collectCalls(masked, receivers) {
  const calls = [];
  const callRe = /\b([a-zA-Z_$][\w$]*)\s*\.\s*([a-z_$][\w$]*)\s*\(/g;
  let m;
  while ((m = callRe.exec(masked))) {
    calls.push({
      receiverName: m[1],
      receiverType: receivers.get(m[1]) ?? null,
      method: m[2],
      open: m.index + m[0].length - 1,
      index: m.index
    });
  }
  const chainRe = /\)\s*\.\s*([a-z_$][\w$]*)\s*\(/g;
  while ((m = chainRe.exec(masked))) {
    const open = findOpenBackwards(masked, m.index);
    if (open < 0) continue;
    const host = /([A-Za-z_$][\w$]*)\s*$/.exec(masked.slice(Math.max(0, open - 60), open));
    if (!host || !CHAIN_HOSTS.has(host[1])) continue;
    const hostArgs = splitTopLevel(masked.slice(open + 1, m.index));
    const target = hostArgs[0] && /^[a-zA-Z_$][\w$]*$/.test(hostArgs[0]) ? hostArgs[0] : null;
    calls.push({
      receiverName: target ?? 'mock',
      receiverType: target ? (receivers.get(target) ?? null) : null,
      method: m[1],
      open: m.index + m[0].length - 1,
      index: m.index
    });
  }
  return calls.sort((a, b) => a.index - b.index);
}

export function analyzeTestSource(masked, index, rel) {
  const findings = [];
  const receivers = receiverTypes(masked);
  let stubSites = 0;
  let resolvedCalls = 0;

  for (const call of collectCalls(masked, receivers)) {
    const { receiverName: receiver, method, open } = call;
    const receiverType = call.receiverType;
    const close = findMatching(masked, open);
    if (close < 0) continue;
    const rawArgs = masked.slice(open + 1, close);
    const args = splitTopLevel(rawArgs);

    const hasMatcher = args.some((a) => isMatcherArg(a));
    if (!hasMatcher) continue;
    stubSites++;

    const sig = uniqueSignature(index, method, args.length);
    if (sig) resolvedCalls++;

    const line = lineOf(masked, open);

    // BaseMapper.delete(Wrapper) / delete(T) 重载：裸 any() 两处都能推 ⇒ 编译歧义。
    if (
      method === 'delete' &&
      args.length === 1 &&
      receiverType &&
      /Mapper$/.test(receiverType) &&
      /^(?:Mockito\.|ArgumentMatchers\.)?any\(\s*\)$/.test(args[0])
    ) {
      findings.push(
        `${rel}:${line} (${receiver}.delete(any())) 重载歧义（BaseMapper.delete(Wrapper) / delete(T)）` +
          `—— 写 (实体) any() 或 any(Wrapper.class)`
      );
    }

    if (!sig) continue;

    args.forEach((arg, i) => {
      const matcher = matcherName(arg);
      if (!matcher) return;
      const family = MATCHER_FAMILIES[matcher];
      if (!family) return;
      const declared = sig.params[i];
      if (!declared) return;
      const known = [...SCALARS, 'String'].includes(declared);
      if (!known) return;
      if (!family.includes(declared)) {
        findings.push(
          `${rel}:${line} (${receiver}.${method}) ${matcher}() 匹配不上形参 #${i + 1}「${declared}」` +
            `—— 实参会被装箱成 ${declared}，桩静默不生效；改用 ${matcherFor(declared) ?? '与形参同类型'}()`
        );
      }
    });
  }

  // when(void方法(...)) —— void 不能 stub。
  const whenRe = /\bwhen\s*\(/g;
  let w;
  while ((w = whenRe.exec(masked))) {
    const open = w.index + w[0].length - 1;
    const close = findMatching(masked, open);
    if (close < 0) continue;
    const inner = masked.slice(open + 1, close).trim();
    const innerCall = /^([a-zA-Z_$][\w$]*)\s*\.\s*([a-z_$][\w$]*)\s*\(/.exec(inner);
    if (!innerCall) continue;
    const innerOpen = open + 1 + inner.indexOf('(');
    const innerClose = findMatching(masked, innerOpen);
    if (innerClose < 0) continue;
    const innerArgs = splitTopLevel(masked.slice(innerOpen + 1, innerClose));
    const sig = uniqueSignature(index, innerCall[2], innerArgs.length);
    if (!sig || !sig.returnsVoid) continue;
    findings.push(
      `${rel}:${lineOf(masked, w.index)} when(${innerCall[1]}.${innerCall[2]}(...)) 目标方法返回 void` +
        `—— void 不能 stub（编译报空类型），用 doNothing()/doThrow()`
    );
  }

  return { findings, stubSites, resolvedCalls };
}

function walkJavaFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'target' || entry === 'node_modules') continue;
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) walkJavaFiles(full, out);
    else if (entry.endsWith('.java')) out.push(full);
  }
  return out;
}

export function checkProject(root, options = {}) {
  const minTestFiles = options.minTestFiles ?? MIN_TEST_FILES;
  const minStubSites = options.minStubSites ?? MIN_STUB_SITES;
  const minResolvedCalls = options.minResolvedCalls ?? MIN_RESOLVED_CALLS;
  const servicesDir = join(root, 'services');
  const all = existsSync(servicesDir) ? walkJavaFiles(servicesDir) : [];
  const mainFiles = all.filter((f) => /\\src\\main\\java\\|\/src\/main\/java\//.test(f));
  const testFiles = all.filter((f) => /\\src\\test\\java\\|\/src\/test\/java\//.test(f));

  const mainMasked = mainFiles.map((f) => stripJavaComments(readFileSync(f, 'utf8')));
  const testMasked = testFiles.map((f) => {
    const masked = stripJavaComments(readFileSync(f, 'utf8'));
    return { file: f, masked };
  });
  const index = buildMethodIndex([...mainMasked, ...testMasked.map((t) => t.masked)]);

  const findings = [];
  let stubSites = 0;
  let resolvedCalls = 0;
  for (const { file, masked } of testMasked) {
    const rel = relative(root, file).split('\\').join('/');
    const r = analyzeTestSource(masked, index, rel);
    findings.push(...r.findings);
    stubSites += r.stubSites;
    resolvedCalls += r.resolvedCalls;
  }

  if (testFiles.length < minTestFiles) {
    findings.push(
      `锚点失效：只扫到 ${testFiles.length} 个测试源文件（下限 ${minTestFiles}）—— 解析器退化时零命中是假绿`
    );
  }
  if (stubSites < minStubSites) {
    findings.push(`锚点失效：只识别到 ${stubSites} 处带 matcher 的调用（下限 ${minStubSites}）`);
  }
  if (resolvedCalls < minResolvedCalls) {
    findings.push(
      `锚点失效：只有 ${resolvedCalls} 处调用能唯一定位签名（下限 ${minResolvedCalls}）—— 索引退化会变成假绿`
    );
  }

  return {
    ok: findings.length === 0,
    testFiles: testFiles.length,
    stubSites,
    resolvedCalls,
    findings
  };
}

function main() {
  const result = checkProject(ROOT);
  if (!result.ok) {
    console.error(`${TAG} FAIL: ${result.findings.length} 处 Mockito 写法问题：`);
    for (const f of result.findings) console.error(`  - ${f}`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${result.testFiles} 个测试源文件、${result.stubSites} 处 matcher 调用（${result.resolvedCalls} 处签名可唯一定位），` +
      `无 when(void) / matcher 类型失配 / delete(any()) 歧义`
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main();
}
