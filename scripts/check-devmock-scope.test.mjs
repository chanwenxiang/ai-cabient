#!/usr/bin/env node
/**
 * `check-devmock-scope.mjs` 的自测：解析器 + 红/绿 + 锚点，全部在临时 fixture 里跑。
 *
 * 门禁自身最容易坏在「正则悄悄不匹配了 ⇒ 永远绿」。所以这里既验证判据的正方向
 * （合规写法必须过），也逐条验证反方向（缺开关、错前缀、matchIfMissing、锚点失效必须红），
 * 跑完清理 `.tmp/devmock-scope-*`。
 *
 *   node scripts/check-devmock-scope.test.mjs
 */
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  checkProject,
  hasMockGuard,
  mappingPaths,
  parseClassAnnotations,
  stripJavaComments
} from './check-devmock-scope.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-devmock-scope.test]';
let checks = 0;

function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`断言失败：${message}`);
}

const VALIDATOR = [
  'package com.aicabinet.trade.config;',
  'public class ProductionStartupValidator {',
  '    private void rejectMockFlagsInStrictProfile() {',
  '        if (securityProperties.mockEnabled()) {',
  '            throw new IllegalStateException("Production/staging profile cannot run with aicabinet.security.mock-enabled=true");',
  '        }',
  '    }',
  '}'
].join('\n');

function controllerSource({ name, mappings = [], conditional = true, extra = [] }) {
  const lines = ['package demo;'];
  for (const m of mappings) lines.push(`@RequestMapping(${m})`);
  if (conditional) {
    lines.push(
      '@ConditionalOnProperty(name = "aicabinet.security.mock-enabled", havingValue = "true")'
    );
  }
  lines.push(...extra);
  lines.push('@RestController');
  lines.push(`public class ${name} {`);
  lines.push('    @PostMapping("/x")');
  lines.push('    public void x() {}');
  lines.push('}');
  return `${lines.join('\n')}\n`;
}

function writeTree(root, spec) {
  rmSync(root, { recursive: true, force: true });
  const javaDir = join(root, 'services', 'demo', 'src', 'main', 'java', 'demo');
  mkdirSync(javaDir, { recursive: true });
  for (const c of spec.controllers || []) {
    writeFileSync(join(javaDir, `${c.name}.java`), controllerSource(c));
  }
  const validatorPath = join(
    root,
    'services',
    'trade-service',
    'src',
    'main',
    'java',
    'com',
    'aicabinet',
    'trade',
    'config',
    'ProductionStartupValidator.java'
  );
  if (spec.validator !== false) {
    mkdirSync(dirname(validatorPath), { recursive: true });
    writeFileSync(validatorPath, spec.validatorText ?? VALIDATOR);
  }
}

const baseControllers = [
  { name: 'DevMockPaymentController', mappings: ['"/api/v2/dev/payment/wechat"'] },
  { name: 'DevMockRechargeController', mappings: ['value = "/api/v2/dev/payment"'] },
  { name: 'DevMockAlipayPaymentController', mappings: ['path = "/api/v2/dev/payment/alipay"'] }
];

function expectOk(root, message) {
  const r = checkProject(root);
  assert(r.ok, `${message}：期望绿，实际 ${JSON.stringify(r.findings)}`);
  return r;
}

function expectFinding(root, needle, message) {
  const r = checkProject(root);
  assert(!r.ok, `${message}：期望红，实际绿`);
  assert(
    r.findings.some((f) => f.includes(needle)),
    `${message}：期望命中「${needle}」，实际 ${JSON.stringify(r.findings)}`
  );
  return r;
}

function parserChecks() {
  const src = [
    'package demo;',
    'import a.b.C; // trailing comment',
    '/* block @RestController */',
    '@RequestMapping(',
    '  value = {"/api/v2/dev/a", "/api/v2/dev/b"},',
    '  produces = "application/json"',
    ')',
    '@ConditionalOnProperty(name = "aicabinet.security.mock-enabled", havingValue = "true")',
    '@RestController',
    'public class DemoController {',
    '  @GetMapping("/inner") void inner() {}',
    '}'
  ].join('\n');
  const parsed = parseClassAnnotations(src);
  assert(parsed.className === 'DemoController', '类名解析');
  assert(
    parsed.annotations.filter((a) => a.name === 'RequestMapping').length === 1,
    '类级 @RequestMapping 只应解析到一次'
  );
  assert(
    parsed.annotations.filter((a) => a.name === 'GetMapping').length === 0,
    '方法级注解不应出现在类级注解里'
  );
  assert(
    mappingPaths(parsed.annotations.find((a) => a.name === 'RequestMapping').args).join('|') ===
      '/api/v2/dev/a|/api/v2/dev/b',
    'value = {…} 应解析出全部路由'
  );
  assert(hasMockGuard(parsed.annotations), '标准 mock 开关应判为已守卫');
  assert(
    !hasMockGuard(
      parseClassAnnotations(
        '@ConditionalOnProperty(name = "aicabinet.security.mock-enabled", havingValue = "true", matchIfMissing = true)\nclass X {}'
      ).annotations
    ),
    'matchIfMissing=true 必须判为未守卫'
  );
  assert(
    !hasMockGuard(
      parseClassAnnotations(
        '@ConditionalOnProperty(name = "aicabinet.security.mock-enabled", havingValue = "false")\nclass X {}'
      ).annotations
    ),
    'havingValue=false 必须判为未守卫'
  );
  assert(
    !hasMockGuard(
      parseClassAnnotations('@ConditionalOnProperty(name = "other.flag")\nclass X {}').annotations
    ),
    '键名不符必须判为未守卫'
  );
  const masked = stripJavaComments('String s = "http://x"; // c\n/* d */ int a;');
  assert(masked.includes('http://x'), '字符串里的 // 不能被当注释吃掉');
  assert(!masked.includes('// c'), '行注释应被清除');
  assert(!masked.includes('/* d */'), '块注释应被清除');
}

function main() {
  parserChecks();

  const tmpBase = join(ROOT, '.tmp');
  mkdirSync(tmpBase, { recursive: true });
  const fixture = mkdtempSync(join(tmpBase, 'devmock-scope-'));

  try {
    // 1) 合规 fixture：3 个 DevMock + 生产启动校验
    writeTree(fixture, { controllers: baseControllers });
    const green = expectOk(fixture, '合规 fixture');
    assert(green.devMock.length === 3, '合规 fixture 应解析出 3 个 DevMock');
    assert(green.devPath.length === 3, '合规 fixture 应解析出 3 个 dev 路由类');

    // 2) 缺 mock 开关
    writeTree(fixture, {
      controllers: [
        { ...baseControllers[0], conditional: false },
        baseControllers[1],
        baseControllers[2]
      ]
    });
    expectFinding(fixture, 'DevMockPaymentController', '缺 mock 开关');

    // 3) mock 路由跑到业务前缀
    writeTree(fixture, {
      controllers: [
        { name: 'DevMockPaymentController', mappings: ['"/api/v2/payment/wechat"'] },
        baseControllers[1],
        baseControllers[2]
      ]
    });
    expectFinding(fixture, '必须在 /api/v2/dev/ 下', '业务前缀');

    // 4) 缺类级 @RequestMapping
    writeTree(fixture, {
      controllers: [
        { name: 'DevMockPaymentController', mappings: [] },
        baseControllers[1],
        baseControllers[2]
      ]
    });
    expectFinding(fixture, '没有类级 @RequestMapping', '缺路由');

    // 5) 换个类名，仍把路由留在 dev 前缀且不守卫
    writeTree(fixture, {
      controllers: [
        ...baseControllers,
        { name: 'DevEchoController', mappings: ['"/api/v2/dev/echo"'], conditional: false }
      ]
    });
    expectFinding(fixture, '路由落在 /api/v2/dev 下', '非 DevMock 命名的 dev 路由');

    // 6) 类名含 Mock、业务路径、无守卫
    writeTree(fixture, {
      controllers: [
        ...baseControllers,
        { name: 'PaymentMockController', mappings: ['"/api/v2/payment"'], conditional: false }
      ]
    });
    expectFinding(fixture, '类名含 Mock', 'Mock 命名类');

    // 7) matchIfMissing=true
    writeTree(fixture, {
      controllers: [
        {
          name: 'DevMockPaymentController',
          mappings: ['"/api/v2/dev/payment"'],
          conditional: false,
          extra: [
            '@ConditionalOnProperty(name = "aicabinet.security.mock-enabled", havingValue = "true", matchIfMissing = true)'
          ]
        },
        baseControllers[1],
        baseControllers[2]
      ]
    });
    expectFinding(fixture, 'DevMockPaymentController', 'matchIfMissing');

    // 8) 生产启动校验被删
    writeTree(fixture, {
      controllers: baseControllers,
      validatorText:
        'package com.aicabinet.trade.config;\npublic class ProductionStartupValidator {}\n'
    });
    expectFinding(fixture, '找不到「prod/staging', '启动校验缺失');

    // 9) 锚点：一个 DevMock 都没有
    writeTree(fixture, {
      controllers: [{ name: 'PaymentController', mappings: ['"/api/v2/payment"'] }]
    });
    const zero = expectFinding(fixture, '锚点失效：只找到 0 个 DevMock', 'DevMock 锚点');
    assert(
      zero.findings.some((f) => f.includes('/api/v2/dev 控制器')),
      'dev 路由锚点也应失效'
    );

    // 10) 真仓必须绿
    const live = checkProject(ROOT);
    assert(live.ok, `真仓应绿，实际 ${JSON.stringify(live.findings)}`);
    assert(live.devMock.length === 3, `真仓 DevMock 应为 3，实际 ${live.devMock.length}`);
    assert(live.devPath.length === 3, `真仓 dev 路由应为 3，实际 ${live.devPath.length}`);
    assert(live.validatorOk, '真仓生产启动校验应命中');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }

  const leftovers = existsSync(tmpBase)
    ? readdirSync(tmpBase).filter((n) => n.startsWith('devmock-scope-'))
    : [];
  assert(leftovers.length === 0, `临时目录未清理：${leftovers.join(',')}`);

  console.log(
    `${TAG} OK（${checks} 项：解析器 / 红绿 / matchIfMissing / 锚点 / 真仓，且不残留临时目录）`
  );
}

main();
