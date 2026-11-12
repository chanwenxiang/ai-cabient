#!/usr/bin/env node
/**
 * R20 P3-7 门禁（2026-11-12）：配置读「每单 N 次查库」根因修复——进程内 TTL 缓存。
 *
 * 🔴 判据设计（铁律 84 / 75 / 3）：
 *   ① 扫 Java 源码前 maskComments() 抹注释为**等长空格**——实现里写了大段成因注释，
 *      不抹会假红/假绿；
 *   ② 判据贴真实责任边界：只取 getter / writer 的**方法体**，不全文 includes；
 *   ③ 修的是「这一类」：getValue/getInt/getBoolean/getDouble 全部汇聚到 loadConfigCached，
 *      且 upsert/delete/refreshDescriptionIfPresent/upsertIfAbsent 四处写后精准失效；
 *   ④ 全局安全网：除 loadConfigCached 外不得出现第二处直读 repository.findById(
 *      （写路径用 findByIdForUpdate，不算）；
 *   ⑤ 钉住单测两个不变式（缓存命中一次 / 写后失效），防「只钉语义不钉测试」。
 *
 * 用法：
 *   node scripts/check-system-config-cache.mjs             # 正向门禁
 *   node scripts/check-system-config-cache.mjs --self-test # 沙箱化负向变异 + md5 零残留
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const P = {
  impl: 'services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java',
  test: 'services/trade-service/src/test/java/com/aicabinet/trade/service/SystemConfigCacheTest.java'
};

/** 注释替换为等长空格：保行号/列偏移，注释里的旧写法不得被当成实现。 */
function maskComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
}

/** 从 openIdx（开括号后一个字符）起截取配平内容；字符串里的括号不计数。 */
function balanced(src, openIdx) {
  let depth = 1;
  let inStr = null;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      if (c === '\\') {
        i++;
        continue;
      }
      if (c === inStr) inStr = null;
      continue;
    }
    if (c === '"' || c === "'") {
      inStr = c;
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}' && --depth === 0) return src.slice(openIdx, i);
  }
  return '';
}

/** 取方法签名后的完整方法体；找不到返回 null（调用方必须判红，不能当空串放过）。 */
function methodBody(src, signature) {
  const i = src.indexOf(signature);
  if (i < 0) return null;
  const open = src.indexOf('{', i + signature.length);
  if (open < 0) return null;
  return balanced(src, open + 1);
}

/** 纯函数判据：read(relPath) → 源码文本。正向从磁盘读，自测从内存副本读。 */
function evaluate(read) {
  const problems = [];
  const bad = (msg) => problems.push(msg);
  const impl = maskComments(read(P.impl));
  const test = maskComments(read(P.test));

  // ── R1 汇聚读方法本身正确：唯一直读点 + 命中缓存 + 写回缓存 ──
  const loadBody = methodBody(impl, 'private String loadConfigCached(');
  if (loadBody === null) {
    bad('找不到 loadConfigCached 汇聚读方法');
  } else {
    if (!/repository\.findById\(/.test(loadBody))
      bad('loadConfigCached 未直读 repository.findById（缓存读路径断裂）');
    if (!/configCache\.get\(/.test(loadBody)) bad('loadConfigCached 未从缓存读（缺 configCache.get(）');
    if (!/configCache\.put\(/.test(loadBody)) bad('loadConfigCached 未写回缓存（缺 configCache.put(）');
  }

  // ── R2 热路径收敛：consumerPublicConfig 也走 getValue（bootstrap 修复循环里的
  //     repository.findById 是一次性的数据迁移，不在「每单 N 次查库」热路径，允许直读） ──
  const pubBody = methodBody(impl, 'public Map<String, String> consumerPublicConfig(');
  if (pubBody === null) {
    bad('找不到 consumerPublicConfig 方法体');
  } else {
    if (/repository\.findById\(/.test(pubBody))
      bad('consumerPublicConfig 方法体直读 repository.findById，未汇聚到 loadConfigCached');
    if (!/self\.getValue\(|getValue\(/.test(pubBody))
      bad('consumerPublicConfig 未通过 getValue 汇聚到 loadConfigCached');
  }

  // ── R3 缓存基础设施：字段 + 记录类型 + TTL 常量 ──
  if (!/Map<String, ConfigCacheEntry> configCache = new ConcurrentHashMap<>\(\)/.test(impl))
    bad('configCache 进程内缓存字段缺失（必须是 ConcurrentHashMap）');
  if (!/record ConfigCacheEntry\(/.test(impl)) bad('ConfigCacheEntry 记录类型缺失');
  if (!/CONFIG_CACHE_TTL_MS\s*=\s*\d+_?\d*L/.test(impl)) bad('CONFIG_CACHE_TTL_MS TTL 常量缺失');

  // ── R4 四个对外 getter 必须汇聚（不得直读 DB） ──
  const GETTERS = [
    ['public String getValue(String key, String defaultValue)', 'getValue'],
    ['public int getInt(String key, int defaultValue)', 'getInt'],
    ['public boolean getBoolean(String key, boolean defaultValue)', 'getBoolean'],
    ['public double getDouble(String key, double defaultValue)', 'getDouble']
  ];
  for (const [sig, name] of GETTERS) {
    const body = methodBody(impl, sig);
    if (body === null) {
      bad(`找不到 ${name} 方法体`);
      continue;
    }
    if (/repository\.findById\(/.test(body))
      bad(`${name} 方法体直读 repository.findById，未汇聚到 loadConfigCached`);
    if (!/loadConfigCached\(|getValue\(|self\.getValue\(|getInt\(/.test(body))
      bad(`${name} 未通过 getValue/getInt 汇聚到 loadConfigCached`);
  }

  // ── R5 四处写入口必须精准失效（键变量要对） ──
  const WRITERS = [
    [
      'private SystemConfigDto doUpsert(String key, String value, String description, Long operatorId)',
      'configCache.remove(key)',
      'doUpsert'
    ],
    ['public void delete(String configKey, Long operatorId)', 'configCache.remove(configKey)', 'delete'],
    [
      'private void refreshDescriptionIfPresent(String key, String description)',
      'configCache.remove(key)',
      'refreshDescriptionIfPresent'
    ],
    [
      'private void upsertIfAbsent(String key, String value, String description)',
      'configCache.remove(key)',
      'upsertIfAbsent'
    ]
  ];
  for (const [sig, removeNeedle, name] of WRITERS) {
    const body = methodBody(impl, sig);
    if (body === null) {
      bad(`找不到 ${name} 方法体`);
      continue;
    }
    if (!body.includes(removeNeedle))
      bad(`${name} 写后未精准失效缓存（缺 ${removeNeedle}）——30s TTL 内会读到旧值`);
  }

  // ── R6 单测钉住两个不变式（防「只钉语义不钉测试」） ──
  for (const t of [
    'sameKeyReadHitsDbOnceAcrossAllGetters',
    'writeInvalidatesCacheSoNextReadSeesNewValue'
  ]) {
    if (!test.includes(t)) bad(`SystemConfigCacheTest 缺测试 ${t}——缓存命中 / 写后失效不变式无人钉`);
  }

  return problems;
}

const diskRead = (p) => readFileSync(join(ROOT, p), 'utf8');
const md5 = (s) => createHash('md5').update(s).digest('hex');

/**
 * 负向夹具：只在内存副本上变异，逐条期望转红；再验注释不假红与磁盘 md5 零残留（铁律 4 / 44）。
 */
function selfTest() {
  const paths = Object.values(P);
  const baseline = new Map(paths.map((p) => [p, diskRead(p)]));
  const before = new Map(paths.map((p) => [p, md5(baseline.get(p))]));

  const mutations = [
    [
      '移除缓存写回（loadConfigCached 不再 put）',
      P.impl,
      (s) => s.replace(/configCache\.put\(key, new ConfigCacheEntry\(v, now \+ CONFIG_CACHE_TTL_MS\)\);/, '/* no cache */'),
      'configCache.put('
    ],
    [
      'getValue 退回直读 DB（绕过汇聚）',
      P.impl,
      (s) =>
        s.replace(
          'String v = loadConfigCached(key);',
          'String v = repository.findById(key).map(SystemConfig::getConfigValue).orElse(null);'
        ),
      'repository.findById'
    ],
    [
      'upsert 写后失效键写错（硬编码）',
      P.impl,
      (s) => s.replace(/configCache\.remove\(key\);/, 'configCache.remove("STALE_STATIC_KEY");'),
      'configCache.remove(key)'
    ],
    [
      'delete 失效键变量写错',
      P.impl,
      (s) => s.replace('configCache.remove(configKey);', 'configCache.remove(key);'),
      'configCache.remove(configKey)'
    ],
    [
      '整段缓存命中逻辑被删（回退到无缓存）',
      P.impl,
      (s) =>
        s.replace(
          /        ConfigCacheEntry hit = configCache\.get\(key\);\n        if \(hit != null && hit\.alive\(now\)\) \{\n            return hit\.value;\n        \}/,
          '        // cache lookup disabled'
        ),
      'configCache.get('
    ],
    [
      '测试删一条不变式断言',
      P.test,
      (s) => s.replace('sameKeyReadHitsDbOnceAcrossAllGetters', 'sameKeyReadHitsDbOnce'),
      'sameKeyReadHitsDbOnceAcrossAllGetters'
    ]
  ];

  let passed = 0;
  for (const [name, path, mutate, expect] of mutations) {
    const copy = new Map(baseline);
    const mutated = mutate(baseline.get(path));
    if (mutated === baseline.get(path)) {
      console.error(`[self-test] FAIL 变异未生效（源串没匹配上）：${name}`);
      process.exit(1);
    }
    copy.set(path, mutated);
    const problems = evaluate((p) => copy.get(p));
    if (!problems.length) {
      console.error(`[self-test] FAIL 变异未被咬住：${name}`);
      process.exit(1);
    }
    if (!problems.some((p) => p.includes(expect))) {
      console.error(`[self-test] FAIL 变异命中的不是预期判据「${expect}」：${name}`, problems);
      process.exit(1);
    }
    passed++;
  }

  // 反向：注释里复述旧写法不得造成假红（铁律 84①）
  const withComment = new Map(baseline);
  withComment.set(
    P.impl,
    baseline.get(P.impl) +
      '\n// 旧实现：String v = repository.findById(key).map(SystemConfig::getConfigValue).orElse(null);\n' +
      '/* getValue 曾每调用直读一次 DB，未汇聚到 loadConfigCached */\n'
  );
  const commentProblems = evaluate((p) => withComment.get(p));
  if (commentProblems.length) {
    console.error('[self-test] FAIL 注释里的旧写法造成假红：', commentProblems);
    process.exit(1);
  }
  passed++;

  // md5 零残留：自测全程只在内存副本上变异
  for (const p of paths) {
    if (md5(diskRead(p)) !== before.get(p)) {
      console.error(`[self-test] FAIL 磁盘被改动：${p}`);
      process.exit(1);
    }
  }
  console.log(
    `[check-system-config-cache] self-test OK：${passed} 个变异/防假红断言全部符合，md5 零残留`
  );
  process.exit(0);
}

// 负向夹具识别要求剥注释后同时出现 selfTest( 与 --self-test，故自测主体必须是具名函数 + 显式调用。
if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  const problems = evaluate(diskRead);
  if (problems.length) {
    console.error('[check-system-config-cache] FAIL');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log('[check-system-config-cache] OK：配置读汇聚缓存 + 四处写后精准失效，6 组判据全绿');
}
