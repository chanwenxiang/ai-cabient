#!/usr/bin/env node
/**
 * 门禁（审计 AU-074/AU-080 固化）：占位符/弱密钥黑名单的**双端一致性 + 模板可拦截性**。
 *
 * 检查三件事：
 *  ① 三个密钥执法点（trade ProductionStartupValidator、jiangyi-gateway ProductionStartupValidator
 *     与 scripts/check-env.ps1）的占位符前缀黑名单必须**三端集合相等**——只改一头=另一头静默漏检；
 *  ② infra/*.env.*.example 模板里所有 secret 形态键（*_SECRET/*_PASSWORD/*_API_KEY/*_TOKEN）
 *     的值必须「留空」或「能被黑名单识别」——模板放一个黑名单认不出的示例值，
 *     忘改上生产就是公开已知密钥（这正是 AU-074 的原始事故形态）；
 *  ③ `--self-test` 负向自测：黑名单单边缺失、模板不可识别值、以及合规对照，必须判对。
 *
 * 用法：
 *   node scripts/check-env-placeholders.mjs
 *   node scripts/check-env-placeholders.mjs --self-test
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SECRET_KEY_RE = /_(SECRET|PASSWORD|API_KEY|TOKEN)$/i;
const PEM_OK = /^-{5}/;

/** 从 Java 源码提取 PLACEHOLDER_PREFIXES 数组字面量 */
function prefixesFromJava(src) {
  const m = src.match(/PLACEHOLDER_PREFIXES\s*=\s*\{([\s\S]*?)\}/);
  if (!m) return null;
  return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
}

/** 从 check-env.ps1 提取 $PlaceholderPrefixes 数组 */
function prefixesFromPs1(src) {
  const m = src.match(/\$PlaceholderPrefixes\s*=\s*@\(([\s\S]*?)\)/);
  if (!m) return null;
  return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
}

function isCatchable(value, prefixes) {
  const lowered = value.toLowerCase();
  return prefixes.some((p) => lowered.startsWith(p));
}

/** 解析 .env 文本 → [{key, value}]（跳过注释/空行） */
function parseDotEnv(text) {
  const out = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const i = line.indexOf('=');
    if (i < 1) continue;
    out.push({ key: line.slice(0, i).trim(), value: line.slice(i + 1).trim() });
  }
  return out;
}

/** 核心检查（真实与自测共用）。返回违规信息数组。 */
export function checkSources({ javaSrc, jiangyiSrc, ps1Src, templates }) {
  const violations = [];
  const trade = prefixesFromJava(javaSrc);
  const jiangyi = prefixesFromJava(jiangyiSrc);
  const ps1 = prefixesFromPs1(ps1Src);
  if (!trade)
    violations.push(
      'trade ProductionStartupValidator 缺少 PLACEHOLDER_PREFIXES（黑名单被删/改名？）'
    );
  if (!jiangyi)
    violations.push(
      'jiangyi-gateway ProductionStartupValidator 缺少 PLACEHOLDER_PREFIXES（黑名单被删/改名？）'
    );
  if (!ps1) violations.push('check-env.ps1 缺少 $PlaceholderPrefixes（黑名单被删/改名？）');
  const seen = [];
  for (const [name, list] of [
    ['trade', trade],
    ['jiangyi', jiangyi],
    ['check-env', ps1]
  ]) {
    if (!list) continue;
    const norm = [...new Set(list)].sort().join(',');
    if (seen.length && seen[0].norm !== norm) {
      violations.push(
        `占位符黑名单三端不一致：${seen[0].name}=[${seen[0].norm}] ≠ ${name}=[${norm}]`
      );
    }
    seen.push({ name, norm });
  }
  const prefixes = trade && trade.length ? trade : jiangyi && jiangyi.length ? jiangyi : ps1 || [];
  for (const { file, text } of templates) {
    for (const { key, value } of parseDotEnv(text)) {
      if (!SECRET_KEY_RE.test(key)) continue;
      if (!value || PEM_OK.test(value)) continue;
      if (!isCatchable(value, prefixes)) {
        violations.push(
          `${file} 键 ${key} 的示例值无法被占位符黑名单识别（忘改上生产=公开已知密钥）：${value.slice(0, 24)}…`
        );
      }
    }
  }
  return violations;
}

function runReal() {
  const javaSrc = readFileSync(
    join(
      ROOT,
      'services/trade-service/src/main/java/com/aicabinet/trade/config/ProductionStartupValidator.java'
    ),
    'utf8'
  );
  const jiangyiSrc = readFileSync(
    join(
      ROOT,
      'services/jiangyi-gateway/src/main/java/com/aicabinet/jiangyi/config/ProductionStartupValidator.java'
    ),
    'utf8'
  );
  const ps1Src = readFileSync(join(ROOT, 'scripts/check-env.ps1'), 'utf8');
  const templates = readdirSync(join(ROOT, 'infra'))
    // 只检「会上生产/staging」的模板；sandbox/vision-dev 等刻意公开 dev 默认凭据不在范围
    .filter((f) => /^\.env\.(production|staging)\.example$/.test(f))
    .map((f) => ({ file: `infra/${f}`, text: readFileSync(join(ROOT, 'infra', f), 'utf8') }));
  const violations = checkSources({ javaSrc, jiangyiSrc, ps1Src, templates });
  if (violations.length) {
    console.error('[check-env-placeholders] FAIL:');
    for (const v of violations) console.error(`  - ${v}`);
    process.exit(1);
  }
  console.log(
    `[check-env-placeholders] OK：三端黑名单一致，${templates.length} 个模板的 secret 键示例值全部可拦截或留空`
  );
}

function runSelfTest() {
  const goodJava = 'x PLACEHOLDER_PREFIXES = { "change-me", "your-" };';
  const goodPs1 = '$PlaceholderPrefixes = @("change-me", "your-")';
  const cases = [
    {
      name: '①trade 黑名单缺失',
      input: { javaSrc: 'class A {}', jiangyiSrc: goodJava, ps1Src: goodPs1, templates: [] },
      expectHit: true
    },
    {
      name: '②jiangyi 黑名单缺失（新执法点漏接线）',
      input: { javaSrc: goodJava, jiangyiSrc: 'class J {}', ps1Src: goodPs1, templates: [] },
      expectHit: true
    },
    {
      name: '③三端黑名单不一致（jiangyi 单边少前缀）',
      input: {
        javaSrc: goodJava,
        jiangyiSrc: 'PLACEHOLDER_PREFIXES = { "change-me" };',
        ps1Src: goodPs1,
        templates: []
      },
      expectHit: true
    },
    {
      name: '④模板 secret 值黑名单认不出',
      input: {
        javaSrc: goodJava,
        jiangyiSrc: goodJava,
        ps1Src: goodPs1,
        templates: [
          { file: 'infra/x.env.a.example', text: 'A=1\nMY_SECRET=totally-random-looking-value\n' }
        ]
      },
      expectHit: true
    },
    {
      name: '⑤合规对照：三端一致+留空/可识别占位/PEM',
      input: {
        javaSrc: goodJava,
        jiangyiSrc: goodJava,
        ps1Src: goodPs1,
        templates: [
          {
            file: 'infra/ok.env.example',
            text: 'EMPTY_SECRET=\nCHANGE_SECRET=CHANGE-ME-xxx\nPEM_KEY=-----BEGIN PRIVATE KEY-----\nNOT_SECRET_TIMEOUT=30\n'
          }
        ]
      },
      expectHit: false
    }
  ];
  let failed = 0;
  for (const c of cases) {
    const hits = checkSources(c.input);
    if (c.expectHit && hits.length === 0) {
      console.error(`✗ 自测失败（违规未被识别）：${c.name}`);
      failed++;
    }
    if (!c.expectHit && hits.length > 0) {
      console.error(`✗ 自测失败（合规对照被误报）：${c.name} -> ${hits[0]}`);
      failed++;
    }
  }
  if (failed) {
    console.error(`check-env-placeholders --self-test: ${failed} 项失败`);
    process.exit(1);
  }
  console.log(
    'check-env-placeholders --self-test: 五形态用例全部判对（含 jiangyi 执法点漏接线/单边漂移）'
  );
}

if (process.argv.includes('--self-test')) {
  runSelfTest();
} else {
  runReal();
}
