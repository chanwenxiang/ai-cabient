#!/usr/bin/env node
/**
 * 门禁：出站 HTTP 客户端只能由 `OutboundHttp` 构造（结构化清单 S1）。
 *
 * 缺陷类（同族修了四轮，每轮换一个落点）：
 *   - R19（jiangyi-gateway 零超时）、R20-4（`WeChatPayV3Client` / `AlipayOpenApiClient` 零超时）、
 *     R20-10（设备侧 `TradeServiceClient` 零超时）、R21-1（扩到 6 处，含微信登录主链）；
 *   - 2026-11-12 做本门禁时全量清点，又挖出**从未进过任何清单**的两处裸
 *     `RestClient.create()`：`WeChatPublisherStatClient`、`WeChatWebOAuthClient`。
 *     ⇒ 证明「逐处补 setTimeout」不是收口：每轮的清单本身就可能不全。
 *
 * 判据（五条，全部 fail-closed）：
 *   ① `RestClient.create()` 出现即红——**零容忍**，连 OutboundHttp 自己也不许用
 *      （`create()` 的默认工厂不带读超时＝无限等待）；
 *   ② `RestClient.builder()` / `new SimpleClientHttpRequestFactory(` /
 *      `new BufferingClientHttpRequestFactory(` 只允许出现在 allowlist 文件里，
 *      且**每个文件的命中数必须与基线相等**（双向棘轮：多命中＝有人绕过，少命中＝闸门被拆）；
 *   ③ OutboundHttp 必须仍然带 `requirePositive` 正数校验、`SimpleClientHttpRequestFactory`
 *      的 connect/read 双设——把闸门拆成空壳也判红；
 *   ④ **第二条出站栈同样纳管**（`java.net.http.HttpClient`，高德/DevOps Hub/Ollama/XXL 自检）：
 *      业务侧只许写 `OutboundHttp.jdk(...)`，且必须落在 final 字段/常量初始化上——
 *      每次调用新建 HttpClient 会各带一套 selector/executor 线程，不 close 只能等 GC
 *      （该形态原封不动存在于 MerchantAiInsightService，2026-11-12 收口）。
 *      不纳管这条，「出站超时已单源」这句话就是假的。
 *   ⑤ **第三条栈同样纳管**（裸 `HttpURLConnection`，短信发送两处）：出现 `openConnection(`
 *      就必须 connect + read 双设，缺哪一行点哪一行。两处现状已合规，这条守的是
 *      「下一段复制这段写法的人少抄一行」——只设 connect 时响应阶段仍是无限等待。
 *
 * 扫描面只含 `src/main/java`：测试里自建 stub client 不会造成生产线程挂死，纳进来只会逼人加白名单。
 * 扫描根缺失同样判红（不写「无输入即跳过」，那正是静默假绿的通道）。
 *
 *   node scripts/check-outbound-client-factory.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-outbound-client-factory]';
const ALLOWLIST_PATH = join('scripts', 'outbound-client-factory-allowlist.json');
const FACTORY_FILE =
  'services/common/common-core/src/main/java/com/aicabinet/common/http/OutboundHttp.java';

const BARE_CREATE = /RestClient\.create\s*\(/;
const SHARED_PATTERNS = [
  { key: 'builder', re: /RestClient\.builder\s*\(/g },
  { key: 'simpleFactory', re: /new\s+SimpleClientHttpRequestFactory\s*\(/g },
  { key: 'bufferingFactory', re: /new\s+BufferingClientHttpRequestFactory\s*\(/g },
  // java.net.http.HttpClient 是本仓**第二条**出站构造栈（高德/地理编码、GitHub Hub、Ollama、
  // XXL 自检），完全不走 RestClient，故必须单独纳入判据。
  { key: 'jdkClient', re: /HttpClient\.newBuilder\s*\(/g },
  // 第三条：裸 `HttpURLConnection`（短信发送两处）。这两处本身已合规（connect+read 双设），
  // 纳管是为了让「下一段复制这段写法的人漏一行」当场红，而不是靠人眼。
  { key: 'urlConnection', re: /\.openConnection\s*\(/g }
];
const JDK_FIELD_ASSIGN_REST =
  /(?:private|protected|public)?\s*(?:static\s+)?final\s+HttpClient\s+\w+\s*=\s*$/;

/**
 * JDK HttpClient 的两条专项判据（S1 第 ④ 条）。
 *
 * 迁移后业务侧只写 `OutboundHttp.jdk(...)`，真构造点只剩闸门本体一处，
 * 所以这里守的是「**用了档位也必须放对位置**」：
 *   ① `OutboundHttp.jdk(` 必须出现在 **final 字段/常量初始化**上——
 *      每次调用新建会各带一套 selector/executor 线程，不 close 只能等 GC
 *      （历史形态来自 MerchantAiInsightService，2026-11-12 收口）；
 *   ② 闸门本体里的 `HttpClient.newBuilder(` 必须带 `.connectTimeout(`（不设＝连接无限等待）。
 *
 * @param file 相对路径（闸门本体豁免①）
 * @param code 去注释后的源码
 * @returns {string[]} 违规说明（空数组＝合规）
 */
export function scanJdkClientUsage(code, file) {
  const issues = [];
  if (file.endsWith('OutboundHttp.java')) {
    if (/HttpClient\.newBuilder\s*\(/.test(code) && !/\.connectTimeout\s*\(/.test(code)) {
      issues.push('闸门本体的 HttpClient 未设 connectTimeout（不设＝连接无限等待）');
    }
    if (/HttpClient\.newHttpClient\s*\(/.test(code)) {
      issues.push('闸门本体不得使用 HttpClient.newHttpClient()（等价于零超时构造）');
    }
    return issues;
  }
  for (const m of code.matchAll(/OutboundHttp\.jdk\s*\(/g)) {
    const before = code.slice(Math.max(0, m.index - 90), m.index).replace(/\s+/g, ' ');
    if (!JDK_FIELD_ASSIGN_REST.test(before)) {
      issues.push(
        'OutboundHttp.jdk(...) 不在 final 字段/常量初始化上：每调用一次就新建一个 HttpClient' +
          '（各带 selector/executor 线程），必须复用实例字段'
      );
    }
  }
  return issues;
}

/**
 * 第三条出站栈：裸 `HttpURLConnection`（S1 第 ⑤ 条）。
 *
 * 现状两处都合规（connect+read 双设 + `setFixedLengthStreamingMode` 带 Content-Length +
 * 4xx/5xx 也读响应体，见 H14），所以这条**不是**在报现存缺陷，而是在封「下一段复制这段
 * 写法、少抄一行 setTimeout」——少哪一行都单独判红，因为只设 connect 时
 * **响应阶段仍是无限等待**，正是本族最常见的事故形态。
 *
 * @param code 去注释后的源码
 * @returns {string[]} 违规说明（空数组＝合规）
 */
export function scanUrlConnectionUsage(code) {
  if (!/\.openConnection\s*\(/.test(code)) return [];
  const missing = [];
  if (!/\.setConnectTimeout\s*\(/.test(code)) missing.push('setConnectTimeout');
  if (!/\.setReadTimeout\s*\(/.test(code))
    missing.push('setReadTimeout（只设 connect 时响应阶段仍无限等待）');
  return missing.length > 0
    ? [`HttpURLConnection 未设 ${missing.join(' / ')}：裸 openConnection 必须 connect+read 双设`]
    : [];
}

/** 去注释与字符串字面量：判据只看真代码，注释里的历史说明不该把门禁点红。 */
export function stripCommentsAndStrings(src) {
  return src
    .replace(/"""[\s\S]*?"""/g, '""')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
    .replace(/"(?:[^"\\]|\\.)*"/g, '""');
}

/** 单文件扫描：返回该文件的裸建违规与共享模式命中数（按 key）。 */
export function scanFile(src, file = '') {
  const code = stripCommentsAndStrings(src);
  const bareCreate = (code.match(new RegExp(BARE_CREATE.source, 'g')) || []).length;
  const counts = {};
  for (const { key, re } of SHARED_PATTERNS) {
    counts[key] = (code.match(re) || []).length;
  }
  return {
    bareCreate,
    counts,
    jdkIssues: [...scanJdkClientUsage(code, file), ...scanUrlConnectionUsage(code)]
  };
}

/**
 * 汇总判定。
 * @param findings 文件相对路径 → scanFile 结果
 * @param allowlist 基线：文件 → { [key]: 期望命中数, reason }
 * @returns {{errors: string[], stats: object}}
 */
export function evaluate(findings, allowlist) {
  const errors = [];
  let sanctioned = 0;
  for (const [file, { bareCreate, counts, jdkIssues }] of Object.entries(findings)) {
    for (const issue of jdkIssues || []) {
      errors.push(`${file}: ${issue}`);
    }
    if (bareCreate > 0) {
      errors.push(
        `${file}: 裸建 RestClient.create() ${bareCreate} 处（默认工厂无读超时＝无限等待），必须走 OutboundHttp`
      );
    }
    for (const { key } of SHARED_PATTERNS) {
      const hit = counts[key] || 0;
      const expected = allowlist[file]?.[key] ?? 0;
      if (hit === 0 && expected === 0) continue;
      if (!(file in allowlist)) {
        errors.push(
          `${file}: 出现 ${key} × ${hit}，但不在 allowlist——出站构造必须收敛到 ${FACTORY_FILE}`
        );
        continue;
      }
      if (hit !== expected) {
        errors.push(
          `${file}: ${key} 命中 ${hit} 与基线 ${expected} 不符` +
            (hit < expected ? '（闸门被拆？少命中同样判红）' : '（新增绕过点）')
        );
        continue;
      }
      sanctioned += hit;
    }
  }
  return { errors, stats: { files: Object.keys(findings).length, sanctioned } };
}

function walkJava(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (name === 'node_modules' || name === 'target' || name === 'static') continue;
      walkJava(full, out);
    } else if (name.endsWith('.java')) {
      out.push(full);
    }
  }
  return out;
}

function main() {
  const servicesDir = join(ROOT, 'services');
  if (!existsSync(servicesDir)) {
    console.error(`${TAG} FAIL：services/ 不存在（扫描根缺失，不判通过）`);
    process.exit(1);
  }
  const allowlistFile = join(ROOT, ALLOWLIST_PATH);
  if (!existsSync(allowlistFile)) {
    console.error(`${TAG} FAIL：allowlist 缺失 ${ALLOWLIST_PATH}`);
    process.exit(1);
  }
  const allowlist = JSON.parse(readFileSync(allowlistFile, 'utf8'));

  const javaFiles = walkJava(servicesDir).filter((p) =>
    /(^|[\\/])src[\\/]main[\\/]java[\\/]/.test(p)
  );
  if (javaFiles.length === 0) {
    console.error(`${TAG} FAIL：没有扫到任何 .java（判据退化会让零命中变假绿）`);
    process.exit(1);
  }

  // ③ 闸门自身完整性：OutboundHttp 必须仍在设超时并校验正数
  const factoryPath = join(ROOT, FACTORY_FILE);
  if (!existsSync(factoryPath)) {
    console.error(`${TAG} FAIL：闸门本体缺失 ${FACTORY_FILE}`);
    process.exit(1);
  }
  const factorySrc = readFileSync(factoryPath, 'utf8');
  for (const [label, re] of [
    ['setConnectTimeout', /setConnectTimeout\s*\(/],
    ['setReadTimeout', /setReadTimeout\s*\(/],
    ['正数校验 requirePositive', /requirePositive\s*\(/]
  ]) {
    if (!re.test(stripCommentsAndStrings(factorySrc))) {
      console.error(`${TAG} FAIL：${FACTORY_FILE} 里已找不到 ${label}（闸门被拆空）`);
      process.exit(1);
    }
  }

  const findings = {};
  for (const abs of javaFiles) {
    const rel = relative(ROOT, abs).replace(/\\/g, '/');
    findings[rel] = scanFile(readFileSync(abs, 'utf8'), rel);
  }

  const { errors, stats } = evaluate(findings, allowlist);
  if (errors.length > 0) {
    for (const e of errors) console.error(`${TAG} FAIL ${e}`);
    console.error(
      `${TAG} 共 ${errors.length} 项违规；修法：改成 OutboundHttp.<档位>()，` +
        `新档位请加进 ${FACTORY_FILE} 而不是在业务类里自拼工厂`
    );
    process.exit(1);
  }
  console.log(
    `${TAG} OK：扫 ${stats.files} 个 .java，受管构造点 ${stats.sanctioned} 处全与基线一致（闸门本体 ${FACTORY_FILE} + allowlist 豁免点）`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
