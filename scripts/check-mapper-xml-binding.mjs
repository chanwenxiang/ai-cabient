#!/usr/bin/env node
/**
 * 门禁：MyBatis XML 与 Java 源码必须**双向对得上**，防「删了 Java 源文件、
 * XML 还留着」这类编译期查不出、启动即崩的悬空绑定。
 *
 * 背景（lessons #233）：删 `DistributedTransactionMapper.java` 后 trade-service
 * 启动即崩——XML 的 `namespace` 指向不存在的接口，MyBatis 在 `SqlSessionFactory`
 * 初始化时才报错，`mvn compile`/单测全绿。同族还有 #323「删源文件后的编译验证」。
 *
 * 判据（全部为静态解析，不启动 Spring）：
 *   1. 每个 `<mapper namespace="...">` 的 FQCN 必须能解析到本仓 `services/**` 下的
 *      class/interface/record/enum；
 *   2. 每个 `<select|insert|update|delete id="...">` 必须在对应接口里有同名方法
 *      （MyBatis-Plus BaseMapper 的内置方法不会出现在 XML，故不豁免）；
 *   3. `resultType` / `parameterType` / `javaType` / `type` / `ofType` 里的
 *      `com.aicabinet.*` FQCN 必须能解析到源文件；简单别名必须能在全仓找到同名类型
 *      （跳过 JDK/MyBatis 内置别名）。
 *
 * 负向验证：把任一 Mapper XML 的 namespace 改成 `com.aicabinet.trade.mapper.Nope`
 * => 必须红；在 XML 里加一个接口没有的 `<select id="nope">` => 必须红。
 *
 *   node scripts/check-mapper-xml-binding.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mapper-xml-binding]';
const SERVICES = join(ROOT, 'services');

// JDK / MyBatis / MyBatis-Plus 内置别名，不要求出现在本仓源码里
const BUILTIN_ALIASES = new Set(
  [
    'string',
    'byte',
    'long',
    'short',
    'int',
    'integer',
    'double',
    'float',
    'boolean',
    'date',
    'decimal',
    'bigdecimal',
    'biginteger',
    'object',
    'map',
    'hashmap',
    'list',
    'arraylist',
    'collection',
    'iterator',
    'localdate',
    'localdatetime',
    'localtime',
    'timestamp',
    'instant',
    'zoneddatetime',
    'uuid',
    'char',
    'character',
    'sql_date',
    'sqldate',
    'sqltimestamp',
    'byte[]',
    'byte[]_'
  ].map((s) => s.toLowerCase())
);

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    if (e.name === 'target' || e.name === 'node_modules' || e.name === '.git') continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

// ── 建 Java 类型索引 ────────────────────────────────────────────────────────
const typeByFqcn = new Map(); // fqcn -> { file, text, module }
const typesBySimpleName = new Map(); // simple -> [{ fqcn, text, module }]
const modules = readdirSync(SERVICES, { withFileTypes: true })
  .filter((d) => d.isDirectory())
  .map((d) => d.name);

for (const mod of modules) {
  const javaRoot = join(SERVICES, mod, 'src', 'main', 'java');
  let st;
  try {
    st = statSync(javaRoot);
  } catch {
    continue;
  }
  if (!st.isDirectory()) continue;
  for (const file of walk(javaRoot).filter((f) => f.endsWith('.java'))) {
    const text = readFileSync(file, 'utf8');
    const pkg = (text.match(/^\s*package\s+([\w.]+)\s*;/m) || [])[1] || '';
    for (const m of text.matchAll(
      /\b(?:class|interface|record|enum)\s+([A-Za-z_$][\w$]*)\s*(?:<|\{|extends|implements|\()/g
    )) {
      const simple = m[1];
      const fqcn = pkg ? `${pkg}.${simple}` : simple;
      const entry = { file, text, module: mod };
      if (!typeByFqcn.has(fqcn)) typeByFqcn.set(fqcn, entry);
      const list = typesBySimpleName.get(simple) || [];
      list.push({ fqcn, ...entry });
      typesBySimpleName.set(simple, list);
    }
  }
}
if (typeByFqcn.size === 0) fail('没有解析到任何 Java 类型，锚点可能已失效');

// ── 扫 XML ─────────────────────────────────────────────────────────────────
const xmlFiles = walk(join(SERVICES)).filter((f) =>
  /[\\/]src[\\/]main[\\/]resources[\\/].*\.xml$/i.test(f)
);
const mapperXmls = [];
for (const file of xmlFiles) {
  const text = readFileSync(file, 'utf8');
  const nsMatch = text.match(/<mapper\s+[^>]*namespace\s*=\s*"([^"]+)"/);
  if (nsMatch) mapperXmls.push({ file, text, namespace: nsMatch[1] });
}
if (mapperXmls.length === 0) fail('没有找到任何 Mapper XML，锚点可能已失效');

const problems = [];

for (const { file, text, namespace } of mapperXmls) {
  const rel = relative(ROOT, file).replace(/\\/g, '/');
  const iface = typeByFqcn.get(namespace);
  if (!iface) {
    problems.push(`${rel}: namespace "${namespace}" 找不到对应 Java 类型（删源文件后 XML 会悬空）`);
    continue;
  }

  // 2) mapped statement id 必须是接口方法
  for (const m of text.matchAll(/<(select|insert|update|delete)\s+[^>]*\bid\s*=\s*"([^"]+)"/g)) {
    const id = m[2];
    if (!new RegExp(`\\b${id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\(`).test(iface.text)) {
      problems.push(`${rel}: <${m[1]} id="${id}"> 在 ${namespace} 里没有同名方法`);
    }
  }

  // 3) 类型引用必须可解析
  for (const m of text.matchAll(
    /\b(resultType|parameterType|javaType|ofType|type)\s*=\s*"([^"]+)"/g
  )) {
    const type = m[2].trim();
    if (!type) continue;
    const bare = type.replace(/<.*>$/, '').replace(/\[\]$/, '').trim();
    if (!bare) continue;
    if (!bare.includes('.') && BUILTIN_ALIASES.has(bare.toLowerCase())) continue;
    if (bare.includes('.')) {
      if (!bare.startsWith('com.aicabinet.')) continue; // JDK / MyBatis / 第三方 FQCN
      if (!typeByFqcn.has(bare)) {
        problems.push(`${rel}: ${m[1]}="${type}" 找不到对应 Java 类型`);
      }
      continue;
    }
    if (!typesBySimpleName.has(bare)) {
      problems.push(`${rel}: ${m[1]}="${type}" 不是已知别名，也找不到同名 Java 类型`);
    }
  }
}

if (problems.length) {
  fail(`Mapper XML 绑定断裂 ${problems.length} 处：\n  - ${problems.join('\n  - ')}`);
}

console.log(
  `${TAG} OK：${mapperXmls.length} 个 Mapper XML 的 namespace、statement id、类型引用均能解析到 Java 源码`
);
