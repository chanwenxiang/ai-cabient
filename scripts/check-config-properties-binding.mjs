#!/usr/bin/env node
/**
 * 门禁：每个 @ConfigurationProperties 类的每个绑定字段，必须在所属模块的
 * application*.yml 里有一条同名键（或带理由的豁免）。
 *
 * 背景（2026-10-24 微信支付分授权链路）：
 * `aicabinet.payscore.service-id` / `permission-notify-url` 两个字段在
 * PayScoreProperties 里声明了，但 application.yml **整段没写**，compose 也没透传。
 * 结果是 `hasServiceId()` 恒 false、真实签约恒 501——运行期完全看不出配置缺了。
 *
 * 相邻门禁 `check-config-env-passthrough` 只校验「yml 里**已经存在**的 `${KEY:}` 有没有
 * compose 透传」；键整条缺失时它没有输入，天然是盲区（lessons #336）。本门禁补这一环：
 *   1. 扫 services/<module>/src/main/java 下所有 @ConfigurationProperties(prefix=…)
 *      record/class，展开字段（含同文件嵌套 record，如 AuthProperties.SmsProperties）；
 *   2. 字段名按 Spring 规范转 kebab-case（appId→app-id、apiV3Key→api-v3-key），
 *      拼成 `prefix.field[.nested…]`；
 *   3. 要求该键出现在**所属服务模块**的 application*.yml 里；
 *      声明在 common-core 这类无 yml 的模块时，退回全仓 yml 并集；
 *   4. `@DefaultValue` 标注的字段、类字段自带初始值时允许缺席（缺席有确定默认）；
 *   5. 确实不该落 yml 的字段写进 `scripts/config-binding-waivers.json`（必须带理由；
 *      豁免失效/未命中都判红，防腐烂）。
 *
 * 为什么是静态门禁：Spring 对缺失的 String 字段绑 null、基本类型绑 0/false，
 * 不报错也不告警；而单测通常直接 `new XxxProperties(...)` 或 mocked，
 * 生产里「值其实是零」这件事没有任何测试能看见（lessons #315 同族）。
 *
 * 自测（红/绿两方向 + 解析器单测）：`node scripts/config-properties-binding.test.mjs`
 *
 *   node scripts/check-config-properties-binding.mjs
 *   CONFIG_BINDING_ROOT=.tmp/fixture node scripts/check-config-properties-binding.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, '..');
const TAG = '[check-config-properties-binding]';

/** 防「目录改名/正则失效 ⇒ 0 命中恒绿」的下界锚点（真实值见首次落地：30 类 / 200 键）。 */
const MIN_CLASSES = 20;
const MIN_REQUIRED_KEYS = 100;

/** 去注释但保留字符串字面量（record 参数里常有 javadoc，注释里有括号和逗号）。 */
export function stripJavaComments(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    const next = src[i + 1];
    if (c === '/' && next === '/') {
      while (i < n && src[i] !== '\n') i++;
      continue;
    }
    if (c === '/' && next === '*') {
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] === '\n') out += '\n';
        i++;
      }
      i += 2;
      continue;
    }
    if (c === '"' && src.slice(i, i + 3) === '"""') {
      out += '"""';
      i += 3;
      while (i < n && src.slice(i, i + 3) !== '"""') out += src[i++];
      out += '"""';
      i += 3;
      continue;
    }
    if (c === '"' || c === "'") {
      const quote = c;
      out += c;
      i++;
      while (i < n) {
        out += src[i];
        if (src[i] === '\\') {
          i++;
          if (i < n) out += src[i++];
          continue;
        }
        if (src[i] === quote) {
          i++;
          break;
        }
        i++;
      }
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

export function camelToKebab(name) {
  return name
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1-$2')
    .toLowerCase();
}

/**
 * Spring relaxed binding 对「字母↔数字边界」的横线是可选的：`maxDisputesPer7Days`
 * 既可以写 `max-disputes-per7-days`（标准 kebab），也可以写
 * `max-disputes-per-7-days`（多插横线）——2026-10-24 用真 Binder 实测两者都能绑上
 * （MapConfigurationPropertySource 用例 B）。门禁两种写法都认，其余拼写仍须逐字一致。
 */
export function kebabVariants(path) {
  const digitSplit = path.replace(/([a-z])(\d)/g, '$1-$2').replace(/(\d)([a-z])/g, '$1-$2');
  return digitSplit === path ? [path] : [path, digitSplit];
}

/** 行号：1 基。 */
function lineAt(text, index) {
  let line = 1;
  for (let i = 0; i < index && i < text.length; i++) if (text[i] === '\n') line++;
  return line;
}

/** 按顶层逗号切分（忽略 <>、()、[] 内的逗号）。 */
function splitTopLevel(text, sep = ',') {
  const parts = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '<' || c === '(' || c === '[') depth++;
    else if (c === '>' || c === ')' || c === ']') depth--;
    else if (c === sep && depth === 0) {
      parts.push(text.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(text.slice(start));
  return parts.map((p) => p.trim()).filter(Boolean);
}

/** 剥掉参数上的注解（@DefaultValue("true")、@Nullable 等）。 */
function stripAnnotations(param) {
  let s = param.trim();
  for (;;) {
    const m = /^@[\w.$]+\s*(\((?:[^()]|\([^()]*\))*\))?\s*/.exec(s);
    if (!m) return s;
    s = s.slice(m[0].length).trim();
  }
}

function typeBase(type) {
  return type
    .replace(/<[\s\S]*$/, '')
    .replace(/\[\s*\]/g, '')
    .replace(/^java\.(util|lang|time)\./, '')
    .trim();
}

function isCollectionType(type) {
  return /^(List|Set|Collection|Map|SortedMap|SortedSet|Queue|Deque|MutableList)\b|<.*>/.test(
    type.trim()
  );
}

/**
 * 解析一个 java 文件里的 @ConfigurationProperties 主类型与同文件嵌套 record。
 * @returns {Array<{name:string,prefix:string|null,kind:'record'|'class',components:Array<{name:string,type:string,optional:boolean,line:number}>,file:string}>}
 */
export function parsePropertiesFile(file, source) {
  const text = stripJavaComments(source);
  const shapes = [];
  const annRe = /@ConfigurationProperties\s*\(\s*prefix\s*=\s*"([^"]+)"/g;
  let m;
  while ((m = annRe.exec(text))) {
    const prefix = m[1];
    const rest = text.slice(m.index + m[0].length);
    const decl = /\b(record|class)\s+([A-Za-z_$][\w$]*)/.exec(rest);
    if (!decl) continue;
    const kind = decl[1];
    const name = decl[2];
    const declStart = m.index + m[0].length + decl.index;
    if (kind === 'record') {
      const open = text.indexOf('(', declStart + decl[0].length);
      const close = matchParen(text, open);
      if (open < 0 || close < 0) continue;
      const components = splitTopLevel(text.slice(open + 1, close)).map((raw) => {
        const cleaned = stripAnnotations(raw);
        const optional = /@DefaultValue\b/.test(raw);
        const fm =
          /^(?:final\s+)?([\w.$]+(?:\s*<[\s\S]*>)?(?:\[\s*\])*)\s+([A-Za-z_$][\w$]*)$/.exec(
            cleaned.replace(/\s+/g, ' ').trim()
          );
        if (!fm) return null;
        const offset = text.indexOf(raw, open);
        return {
          name: fm[2],
          type: fm[1].trim(),
          optional,
          line: lineAt(text, offset >= 0 ? offset : open)
        };
      });
      shapes.push({
        name,
        prefix,
        kind,
        components: components.filter(Boolean),
        file
      });
    } else {
      const body = classBody(text, declStart + decl[0].length);
      if (!body) continue;
      shapes.push({
        name,
        prefix,
        kind,
        components: parseClassFields(text, body.start, body.end),
        file
      });
    }
  }

  // 同文件嵌套 record（AuthProperties.SmsProperties 等）：无 prefix，由父级字段类型引用。
  const nestedRe = /\brecord\s+([A-Za-z_$][\w$]*)\s*\(/g;
  let rm;
  while ((rm = nestedRe.exec(text))) {
    const open = rm.index + rm[0].length - 1;
    const close = matchParen(text, open);
    if (close < 0) continue;
    const components = splitTopLevel(text.slice(open + 1, close)).map((raw) => {
      const cleaned = stripAnnotations(raw);
      const fm = /^(?:final\s+)?([\w.$]+(?:\s*<[\s\S]*>)?(?:\[\s*\])*)\s+([A-Za-z_$][\w$]*)$/.exec(
        cleaned.replace(/\s+/g, ' ').trim()
      );
      if (!fm) return null;
      const optional = /@DefaultValue\b/.test(raw);
      const offset = text.indexOf(raw, open);
      return {
        name: fm[2],
        type: fm[1].trim(),
        optional,
        line: lineAt(text, offset >= 0 ? offset : open)
      };
    });
    shapes.push({
      name: rm[1],
      prefix: null,
      kind: 'record',
      components: components.filter(Boolean),
      file
    });
  }
  return shapes;
}

function matchParen(text, open) {
  if (open < 0) return -1;
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === '(') depth++;
    else if (text[i] === ')') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/** class 体起止（不含外层花括号）。 */
function classBody(text, from) {
  const open = text.indexOf('{', from);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === '{') depth++;
    else if (text[i] === '}') {
      depth--;
      if (depth === 0) return { start: open + 1, end: i };
    }
  }
  return null;
}

/** 解析 class 顶层字段：private [static] [final] Type name [= init]; */
function parseClassFields(text, start, end) {
  const fields = [];
  let depth = 0;
  let stmtStart = start;
  for (let i = start; i < end; i++) {
    const c = text[i];
    if (c === '{') depth++;
    else if (c === '}') depth--;
    else if (c === ';' && depth === 0) {
      const stmt = text.slice(stmtStart, i);
      const line = lineAt(text, stmtStart + stmt.search(/\S|$/));
      stmtStart = i + 1;
      const cleaned = stmt.replace(/\s+/g, ' ').trim();
      if (/\bstatic\b/.test(cleaned)) continue;
      if (/\b\w+\s*\(/.test(cleaned.replace(/^@[\w.$]+(?:\([^)]*\))?\s*/, ''))) continue;
      const fm =
        /^(?:private|protected|public)\s+((?:final|transient|volatile)\s+)*([\w.$]+(?:\s*<[\s\S]*>)?(?:\[\])*)\s+([A-Za-z_$][\w$]*)\s*(=[\s\S]*)?$/.exec(
          cleaned
        );
      if (!fm) continue;
      fields.push({
        name: fm[3],
        type: fm[2].replace(/\s+/g, ''),
        optional: Boolean(fm[4]),
        line
      });
    }
  }
  return fields;
}

/**
 * 解析 yml 的键路径集合（点分）。只关心键，不关心值；支持嵌套 map、list of map、
 * 引号键、块标量（跳过其内容）、多文档、dotted key。
 */
export function parseYamlKeys(text) {
  const keys = new Set();
  const stack = [];
  let blockIndent = -1;
  for (const rawLine of text.split(/\r?\n/)) {
    if (!rawLine.trim()) continue;
    const indent = rawLine.match(/^[ \t]*/)[0].replace(/\t/g, '  ').length;
    if (blockIndent >= 0) {
      if (indent > blockIndent) continue;
      blockIndent = -1;
    }
    const trimmed = rawLine.trim();
    if (trimmed === '---' || trimmed === '...' || trimmed.startsWith('#')) continue;

    let content = trimmed;
    let contentIndent = indent;
    const li = /^-\s+(.*)$/.exec(trimmed);
    if (li) {
      content = li[1];
      contentIndent = indent + 2;
    }

    const kv = /^(?:"([^"]*)"|'([^']*)'|([^:\s][^:]*?))\s*:(?:\s+([\s\S]*))?$/.exec(content);
    if (!kv) continue;
    const key = (kv[1] ?? kv[2] ?? kv[3]).trim();
    const value = (kv[4] || '').trim();
    if (!key || key === '<<') continue;

    while (stack.length && stack[stack.length - 1].indent >= contentIndent) stack.pop();
    const parent = stack.length ? stack[stack.length - 1].path : '';
    const path = (parent ? parent + '.' : '') + key;
    keys.add(path);
    stack.push({ indent: contentIndent, path });
    if (/^[|>][+-]?\d*$/.test(value)) blockIndent = indent;
  }
  return keys;
}

function walkJava(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const abs = join(dir, entry.name);
    if (entry.isDirectory()) walkJava(abs, out);
    else if (entry.name.endsWith('.java')) out.push(abs);
  }
  return out;
}

function moduleOf(absPath, root) {
  const rel = relative(join(root, 'services'), absPath);
  if (rel.startsWith('..')) return null;
  return rel.split(/[\\/]/)[0];
}

function readModuleYmlKeys(root) {
  const servicesDir = join(root, 'services');
  const byModule = new Map();
  if (!existsSync(servicesDir)) return byModule;
  for (const mod of readdirSync(servicesDir, { withFileTypes: true })) {
    if (!mod.isDirectory()) continue;
    const dir = join(servicesDir, mod.name, 'src', 'main', 'resources');
    if (!existsSync(dir)) continue;
    const keys = new Set();
    let files = 0;
    for (const f of readdirSync(dir)) {
      if (!/^application.*\.ya?ml$/.test(f)) continue;
      files++;
      for (const k of parseYamlKeys(readFileSync(join(dir, f), 'utf8'))) keys.add(k);
    }
    byModule.set(mod.name, { keys, files });
  }
  return byModule;
}

/** 展开字段树为叶子键（嵌套 record 递归；集合/Map 视为叶子）。 */
export function expandLeaves(
  shape,
  shapesByName,
  prefix = shape.prefix,
  depth = 0,
  seen = new Set()
) {
  const leaves = [];
  if (!shape || depth > 6 || seen.has(shape.name)) return leaves;
  seen.add(shape.name);
  for (const comp of shape.components) {
    const path = `${prefix}.${camelToKebab(comp.name)}`;
    const base = typeBase(comp.type);
    const nested = !isCollectionType(comp.type) && shapesByName.get(base);
    if (nested && nested !== shape) {
      leaves.push(...expandLeaves(nested, shapesByName, path, depth + 1, new Set(seen)));
    } else {
      leaves.push({
        path,
        field: comp.name,
        optional: comp.optional,
        line: comp.line,
        owner: shape.name
      });
    }
  }
  return leaves;
}

function collectShapes(root) {
  const servicesDir = join(root, 'services');
  const files = walkJava(servicesDir);
  const all = [];
  for (const file of files) {
    if (/[\\/]src[\\/]test[\\/]/.test(file)) continue;
    const src = readFileSync(file, 'utf8');
    if (!src.includes('@ConfigurationProperties')) continue;
    for (const shape of parsePropertiesFile(file, src)) {
      shape.fileRel = relative(root, file).split(sep).join('/');
      shape.module = moduleOf(file, root);
      all.push(shape);
    }
  }
  return all;
}

export function analyze(root) {
  const shapes = collectShapes(root);
  const yml = readModuleYmlKeys(root);
  const globalKeys = new Set();
  for (const { keys } of yml.values()) for (const k of keys) globalKeys.add(k);

  const byName = new Map();
  const ambiguous = new Set();
  for (const s of shapes) {
    if (s.prefix == null) {
      if (byName.has(s.name)) ambiguous.add(s.name);
      byName.set(s.name, s);
    }
  }
  for (const name of ambiguous) byName.delete(name);

  const primary = shapes.filter((s) => s.prefix != null);
  const results = [];
  for (const shape of primary) {
    const modYml = yml.get(shape.module);
    // 声明模块无 yml（如 common-core）时退回全仓并集
    const keySet = modYml && modYml.files > 0 ? modYml.keys : globalKeys;
    const leaves = expandLeaves(shape, byName);
    for (const leaf of leaves) {
      if (leaf.optional) continue;
      if (kebabVariants(leaf.path).some((k) => keySet.has(k))) continue;
      results.push({ ...leaf, prefix: shape.prefix, class: shape.name, file: shape.fileRel });
    }
  }

  const requiredTotal = primary.reduce((n, s) => {
    const leaves = expandLeaves(s, byName);
    return n + leaves.filter((l) => !l.optional).length;
  }, 0);

  return {
    classes: primary.length,
    requiredTotal,
    ymlModules: [...yml.keys()].filter((m) => yml.get(m).files > 0),
    missing: results
  };
}

/**
 * 跑一遍校验（不退出进程），CLI 与自测共用。
 * @returns {{ok:boolean, classes:number, requiredTotal:number, ymlModules:string[],
 *            missing:Array, realMissing:Array, problems:string[], waiverPath:string}}
 */
export function checkProject(root, options = {}) {
  const minClasses = options.minClasses ?? MIN_CLASSES;
  const minRequiredKeys = options.minRequiredKeys ?? MIN_REQUIRED_KEYS;
  const waiverPath = options.waiverPath ?? join(root, 'scripts', 'config-binding-waivers.json');
  const { classes, requiredTotal, ymlModules, missing } = analyze(root);

  const problems = [];
  if (classes < minClasses) {
    problems.push(
      `只解析到 ${classes} 个 @ConfigurationProperties 类（下界 ${minClasses}）——扫描面可能已失效，0 命中不是绿`
    );
  }
  if (requiredTotal < minRequiredKeys) {
    problems.push(
      `只得到 ${requiredTotal} 个必绑字段（下界 ${minRequiredKeys}）——解析器可能已退化`
    );
  }
  if (ymlModules.length === 0) problems.push('没有解析到任何 application*.yml 键');

  const waivers = existsSync(waiverPath) ? JSON.parse(readFileSync(waiverPath, 'utf8')) : {};
  const usedWaivers = new Set();
  for (const w of Object.keys(waivers)) {
    if (!String(waivers[w] || '').trim()) problems.push(`豁免 ${w} 缺少理由（${waiverPath}）`);
  }

  const realMissing = [];
  for (const m of missing) {
    const id = `${m.class}#${m.field}`;
    if (Object.prototype.hasOwnProperty.call(waivers, id)) {
      usedWaivers.add(id);
      continue;
    }
    realMissing.push(m);
  }
  for (const [id, reason] of Object.entries(waivers)) {
    if (!String(reason || '').trim()) continue;
    if (!usedWaivers.has(id)) {
      problems.push(`豁免 ${id} 已失效（字段已绑 yml 或已被删），请从 ${waiverPath} 移除`);
    }
  }

  return {
    ok: realMissing.length === 0 && problems.length === 0,
    classes,
    requiredTotal,
    ymlModules,
    missing,
    realMissing,
    problems,
    waiverPath,
    waiverCount: usedWaivers.size
  };
}

function main() {
  const root = process.env.CONFIG_BINDING_ROOT
    ? resolve(process.env.CONFIG_BINDING_ROOT)
    : REPO_ROOT;
  const result = checkProject(root);

  if (!result.ok) {
    if (result.realMissing.length) {
      console.error(
        `${TAG} FAIL: ${result.realMissing.length} 个 @ConfigurationProperties 字段在 yml 里没有绑定：`
      );
      for (const m of result.realMissing) {
        console.error(`  - ${m.path}  (${m.class}#${m.field}, ${m.file}:${m.line})`);
      }
      console.error(
        `  修法：在所属模块 application.yml 对应前缀下补该键（占位符写 \${ENV:…}，空默认会自动进入 ` +
          `check-config-env-passthrough），或写进 ${result.waiverPath} 并说明为什么不需要（缺省=0/null 也算需要）`
      );
    }
    for (const p of result.problems) console.error(`${TAG} FAIL: ${p}`);
    process.exit(1);
  }

  console.log(
    `${TAG} OK：${result.classes} 个 @ConfigurationProperties 类、${result.requiredTotal} 个必绑字段` +
      `全部在 ${result.ymlModules.length} 个模块的 application*.yml 中有键（豁免 ${result.waiverCount} 个）`
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main();
}
