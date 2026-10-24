/**
 * 防绕过自测：`check-config-properties-binding` 必须**会红**（字段缺 yml 键）且
 * **不假红**（Spring 认可的 relaxed 写法、注解默认值、集合/嵌套 record）。
 * Run: node scripts/config-properties-binding.test.mjs
 *
 * 为什么必须有这个文件：门禁的全部判据是「正则解析 Java + 解析 yml + 拼键」，
 * 任何一环写错都会退化成恒绿/恒红，而门禁自己看不出。这里用真实目录结构的临时
 * fixture（services/<mod>/src/main/{java,resources}）跑两个方向，并单独钉住解析器。
 */
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  camelToKebab,
  kebabVariants,
  parseYamlKeys,
  checkProject
} from './check-config-properties-binding.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TMP_BASE = join(root, '.tmp');
const TAG = '[config-properties-binding.test]';
const failures = [];
let tmpDirs = [];
let checks = 0;

function expect(name, actual, expected) {
  checks++;
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) failures.push(`${name}: 期望 ${e}，实际 ${a}`);
}

function fixture(files) {
  mkdirSync(TMP_BASE, { recursive: true });
  const dir = mkdtempSync(join(TMP_BASE, 'config-binding-'));
  tmpDirs.push(dir);
  for (const [rel, content] of Object.entries(files)) {
    const abs = join(dir, rel);
    mkdirSync(dirname(abs), { recursive: true });
    writeFileSync(abs, content, 'utf8');
  }
  return dir;
}

const AUTH_JAVA = `package demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "demo.auth")
public record AuthProperties(
        String jwtSecret,
        int expirationSeconds,
        /** 注释里有 (括号, 逗号) 和 "引号" —— 不能把 record 参数切坏 */
        Sms sms,
        @DefaultValue("true") boolean relaxed,
        java.util.List<String> allowedOrigins,
        int maxDisputesPer7Days
) {
    public static final long DEFAULT_TTL = 1800;
    public static int ignoredStatic;
    public record Sms(String provider, String webhookUrl) {}
}
`;

const OSS_JAVA = `package demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.oss")
public class OssProperties {
    private String accessKeyId;
    private String region = "cn-shenzhen";
    private static final String CONST = "x";

    public String getAccessKeyId() { return accessKeyId; }
}
`;

const SHARED_JAVA = `package demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.shared")
public record SharedProperties(String key) {}
`;

// ── 1. 纯解析器断言 ─────────────────────────────────────────────────────────
expect('camelToKebab appId', camelToKebab('appId'), 'app-id');
expect('camelToKebab apiV3Key', camelToKebab('apiV3Key'), 'api-v3-key');
expect('camelToKebab mchId', camelToKebab('mchId'), 'mch-id');
expect(
  'camelToKebab maxDisputesPer7Days',
  camelToKebab('maxDisputesPer7Days'),
  'max-disputes-per7-days'
);
expect('kebabVariants 数字边界两种写法', kebabVariants('demo.a.max-disputes-per7-days').sort(), [
  'demo.a.max-disputes-per-7-days',
  'demo.a.max-disputes-per7-days'
]);

const yamlKeys = parseYamlKeys(
  [
    '# 注释行',
    'demo:',
    '  auth:',
    '    jwt-secret: ${JWT_SECRET:}',
    '    "quoted-key": "x:y"',
    "    'single-key': 1",
    '    banner: |',
    '      not-a-key: value',
    '  listMap:',
    '    - name: a',
    '      enabled: true',
    '  dotted.style.key: 3',
    '---',
    'other: 1'
  ].join('\n')
);
expect('yml 普通键', yamlKeys.has('demo.auth.jwt-secret'), true);
expect('yml 双引号键', yamlKeys.has('demo.auth.quoted-key'), true);
expect('yml 单引号键', yamlKeys.has('demo.auth.single-key'), true);
expect('yml 块标量内容不算键', yamlKeys.has('demo.auth.not-a-key'), false);
expect('yml 块标量后的键仍可见', yamlKeys.has('demo.listMap'), true);
// 列表元素内的键被记为 parent.key（本门禁把集合字段当叶子，不做逐元素校验）
expect('yml list of map 元素键', yamlKeys.has('demo.listMap.name'), true);
expect('yml list of map 元素子键', yamlKeys.has('demo.listMap.enabled'), true);
expect('yml dotted key', yamlKeys.has('demo.dotted.style.key'), true);
expect('yml 多文档', yamlKeys.has('other'), true);

// ── 2. 绿：全部字段都有绑定（含嵌套 record / 集合 / 数字边界写法）────────────
const green = fixture({
  'services/svc-a/src/main/java/demo/AuthProperties.java': AUTH_JAVA,
  'services/svc-a/src/main/java/demo/OssProperties.java': OSS_JAVA,
  'services/common/src/main/java/demo/SharedProperties.java': SHARED_JAVA,
  'services/svc-a/src/main/resources/application.yml': [
    'demo:',
    '  auth:',
    '    jwt-secret: ${JWT_SECRET:}',
    '    expiration-seconds: 1800',
    '    sms:',
    '      provider: webhook',
    '      webhook-url: ""',
    '    allowed-origins:',
    '      - http://localhost',
    '    max-disputes-per-7-days: 3',
    '  oss:',
    '    access-key-id: ${OSS_AK:}',
    '  shared:',
    '    key: ${SHARED_KEY:}'
  ].join('\n')
});
let res = checkProject(green, { minClasses: 0, minRequiredKeys: 0 });
expect('绿：无缺失', res.realMissing, []);
expect('绿：必绑字段数（relaxed 豁免 + region 有初始值）', res.requiredTotal, 8);
expect('绿：ok', res.ok, true);

// ── 3. 红：少一个键必须报出来（含 file:line）──────────────────────────────
const red = fixture({
  'services/svc-a/src/main/java/demo/AuthProperties.java': AUTH_JAVA,
  'services/svc-a/src/main/resources/application.yml': [
    'demo:',
    '  auth:',
    '    jwt-secret: ${JWT_SECRET:}',
    '    expiration-seconds: 1800',
    '    sms:',
    '      provider: webhook',
    '    allowed-origins: []',
    '    max-disputes-per-7-days: 3'
  ].join('\n')
});
res = checkProject(red, { minClasses: 0, minRequiredKeys: 0 });
expect(
  '红：缺 webhook-url',
  res.realMissing.map((m) => m.path),
  ['demo.auth.sms.webhook-url']
);
expect('红：ok=false', res.ok, false);

// ── 4. 红：无 yml 的模块不能靠「别的模块有键」蒙混 —— 但 common-core 例外 ──
const fallback = fixture({
  'services/common/src/main/java/demo/SharedProperties.java': SHARED_JAVA,
  'services/svc-a/src/main/resources/application.yml': 'demo:\n  shared:\n    key: x\n'
});
res = checkProject(fallback, { minClasses: 0, minRequiredKeys: 0 });
expect('common-core 无 yml 时回退全仓并集', res.realMissing, []);

const wrongModule = fixture({
  'services/svc-a/src/main/java/demo/SharedProperties.java': SHARED_JAVA,
  'services/svc-a/src/main/resources/application.yml': 'demo:\n  auth:\n    x: 1\n',
  'services/svc-b/src/main/resources/application.yml': 'demo:\n  shared:\n    key: x\n'
});
res = checkProject(wrongModule, { minClasses: 0, minRequiredKeys: 0 });
expect(
  '有 yml 的模块不能借用邻模块的键',
  res.realMissing.map((m) => m.path),
  ['demo.shared.key']
);

// ── 5. 豁免：有理由才放行；失效/无理由都判红 ───────────────────────────────
const waiverDir = fixture({
  'services/svc-a/src/main/java/demo/SharedProperties.java': SHARED_JAVA,
  'services/svc-a/src/main/resources/application.yml': 'demo:\n  auth:\n    x: 1\n',
  'scripts/config-binding-waivers.json': JSON.stringify({
    'SharedProperties#key': '仅由测试注入，生产不走绑定'
  })
});
res = checkProject(waiverDir, { minClasses: 0, minRequiredKeys: 0 });
expect('豁免生效', res.ok, true);

const staleWaiver = fixture({
  'services/svc-a/src/main/java/demo/SharedProperties.java': SHARED_JAVA,
  'services/svc-a/src/main/resources/application.yml': 'demo:\n  shared:\n    key: x\n',
  'scripts/config-binding-waivers.json': JSON.stringify({ 'SharedProperties#key': '过期' })
});
res = checkProject(staleWaiver, { minClasses: 0, minRequiredKeys: 0 });
expect('失效豁免判红', res.ok, false);

const emptyWaiver = fixture({
  'services/svc-a/src/main/java/demo/SharedProperties.java': SHARED_JAVA,
  'services/svc-a/src/main/resources/application.yml': 'demo:\n  auth:\n    x: 1\n',
  'scripts/config-binding-waivers.json': JSON.stringify({ 'SharedProperties#key': '  ' })
});
res = checkProject(emptyWaiver, { minClasses: 0, minRequiredKeys: 0 });
expect('无理由豁免判红', res.ok, false);

// ── 6. 锚点：扫描面失效必须红（0 命中不是绿）────────────────────────────────
const emptyRepo = fixture({ 'README.md': 'no services here' });
res = checkProject(emptyRepo, { minClasses: 1, minRequiredKeys: 0 });
expect('锚点：无类判红', res.ok, false);
const noYml = fixture({
  'services/svc-a/src/main/java/demo/SharedProperties.java': SHARED_JAVA
});
res = checkProject(noYml, { minClasses: 0, minRequiredKeys: 0 });
expect('锚点：无 yml 判红', res.ok, false);

// ── 7. 真仓实跑：当前工作树必须绿（门禁自身的活体检查）────────────────────
res = checkProject(root);
expect('真仓：已落地绑定', res.ok, true);

// ── 收尾：清临时目录 ────────────────────────────────────────────────────────
for (const dir of tmpDirs) {
  try {
    rmSync(dir, { recursive: true, force: true });
  } catch (e) {
    failures.push(`临时目录清理失败 ${dir}: ${e.message}`);
  }
}
tmpDirs = [];

if (failures.length) {
  console.error(`${TAG} FAIL (${failures.length})`);
  for (const f of failures) console.error(`- ${f}`);
  process.exit(1);
}
console.log(`${TAG} OK（${checks} 个解析/红绿/豁免/锚点用例方向全对，且不残留临时目录）`);
