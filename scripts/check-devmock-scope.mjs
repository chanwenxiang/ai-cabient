#!/usr/bin/env node
/**
 * DevMock 控制器范围门禁：**mock 路由只能活在 `/api/v2/dev/` 下，且必须由 mock 开关装配**。
 *
 * 缺陷类（lessons #25，已真实复现）：mock 控制器与正式支付控制器共用 `/payment` 前缀，
 * 被前端/联调当成正式契约调用；一旦部署到 staging/prod，mock 路径还在，
 * 等于给「不付钱也能到账」留了一个正式入口。正确形态是双保险：
 *   1. 路由前缀 `/api/v2/dev/`，和业务契约在 URL 上就分开；
 *   2. `@ConditionalOnProperty(aicabinet.security.mock-enabled=true)`，开关关掉连 Bean 都不注册；
 *   3. `ProductionStartupValidator` 在 prod/staging 直接拒绝 `mock-enabled=true`（兜底，防配错）。
 *
 * 判据（缺一即红）：
 *   1. `DevMock*Controller` 必须是 `@RestController`，`@RequestMapping` 值全部以
 *      `/api/v2/dev/` 开头，且带 mock 开关条件；
 *   2. 任何 `@RestController` 的类级路由落在 `/api/v2/dev` 下（不论类名），都必须带同一条件
 *      —— 防「换个类名就把 mock 端点留在正式前缀外」；
 *   3. 类名含 `Mock` 的控制器（不论路由）都必须带该条件 —— 防「类名换了但仍是 mock」；
 *   4. 条件必须是 `havingValue = "true"` 且**没有** `matchIfMissing = true`（否则默认开启）；
 *   5. 锚点：DevMock 类 ≥ 3、dev 路由类 ≥ 3、启动校验器存在且确实抛出该拒绝；
 *      任一枚举为空都按失败处理 —— 那是门禁自己瞎了，不是「没有问题」。
 *
 *   node scripts/check-devmock-scope.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-devmock-scope]';
const MOCK_KEY = 'aicabinet.security.mock-enabled';
const DEV_PREFIX = '/api/v2/dev/';
const VALIDATOR = join(
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
const MIN_DEV_MOCK_CLASSES = 3;
const MIN_DEV_PATH_CLASSES = 3;

/** 注释掩成空格（保留换行与列），字符串字面量原样保留 —— 路由/键名都在字符串里。 */
export function stripJavaComments(source) {
  const out = [];
  let state = 'code';
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    const n = source[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') {
        out.push('  ');
        i++;
        state = 'line';
      } else if (c === '/' && n === '*') {
        out.push('  ');
        i++;
        state = 'block';
      } else if (c === '"' && source.startsWith('"""', i)) {
        out.push('   ');
        i += 2;
        state = 'text';
      } else if (c === '"') {
        out.push('"');
        state = 'str';
      } else if (c === "'") {
        out.push("'");
        state = 'char';
      } else {
        out.push(c);
      }
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        out.push('\n');
        state = 'code';
      } else {
        out.push(' ');
      }
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') {
        out.push('  ');
        i++;
        state = 'code';
      } else {
        out.push(c === '\n' ? '\n' : ' ');
      }
      continue;
    }
    if (state === 'text') {
      out.push(c);
      if (c === '"' && source.startsWith('"""', i)) {
        out.push('""');
        i += 2;
        state = 'code';
      }
      continue;
    }
    // str / char：保留转义，遇到结束引号回代码态
    if (c === '\\') {
      out.push(c);
      if (i + 1 < source.length) {
        out.push(source[++i]);
      }
      continue;
    }
    out.push(c);
    if ((state === 'str' && c === '"') || (state === 'char' && c === "'")) {
      state = 'code';
    }
  }
  return out.join('');
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

/** 取一段文本里的字符串字面量（未反转义，路由/键名不含转义）。 */
export function stringLiterals(text) {
  const out = [];
  const re = /"((?:[^"\\]|\\.)*)"/g;
  let m;
  while ((m = re.exec(text))) out.push(m[1]);
  return out;
}

/** 扫描类声明之前的类级注解（跳过 import；括号配对，容忍多行）。 */
export function parseClassAnnotations(source) {
  const src = stripJavaComments(source);
  const decl = /\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)/.exec(src);
  if (!decl) return null;
  const header = src.slice(0, decl.index).replace(/^\s*import\s[^;]*;/gm, ' ');
  const annotations = [];
  const re = /@([A-Za-z_$][\w$.]*)/g;
  let m;
  while ((m = re.exec(header))) {
    const name = m[1].split('.').pop();
    let i = re.lastIndex;
    while (i < header.length && /\s/.test(header[i])) i++;
    let args = '';
    if (header[i] === '(') {
      let depth = 0;
      let inStr = false;
      let j = i;
      for (; j < header.length; j++) {
        const c = header[j];
        if (inStr) {
          if (c === '\\') {
            j++;
            continue;
          }
          if (c === '"') inStr = false;
          continue;
        }
        if (c === '"') {
          inStr = true;
          continue;
        }
        if (c === '(') depth++;
        else if (c === ')') {
          depth--;
          if (depth === 0) {
            j++;
            break;
          }
        }
      }
      args = header.slice(i + 1, j - 1);
      re.lastIndex = j;
    } else {
      re.lastIndex = i;
    }
    annotations.push({ name, args });
  }
  return { className: decl[1], annotations };
}

/** 注解里的路由值：`value=` / `path=` 优先，否则整个参数串里的字面量。 */
export function mappingPaths(args) {
  if (!args || !args.trim()) return [];
  const named = /(?:^|[,(\s])(?:value|path)\s*=\s*(\{[^}]*\}|"(?:[^"\\]|\\.)*")/.exec(args);
  return stringLiterals(named ? named[1] : args);
}

/** mock 开关条件是否到位：键命中 + havingValue=true + 没有 matchIfMissing=true。 */
export function hasMockGuard(annotations) {
  return annotations
    .filter((a) => a.name === 'ConditionalOnProperty')
    .some((a) => {
      if (!stringLiterals(a.args).includes(MOCK_KEY)) return false;
      if (!/havingValue\s*=\s*"true"/.test(a.args)) return false;
      return !/matchIfMissing\s*=\s*true/.test(a.args);
    });
}

function describeGuard() {
  return `@ConditionalOnProperty(name = "${MOCK_KEY}", havingValue = "true")（不得 matchIfMissing）`;
}

export function analyze(root) {
  const findings = [];
  const servicesDir = join(root, 'services');
  const files = existsSync(servicesDir) ? walkJavaFiles(servicesDir) : [];
  const controllers = [];

  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    const parsed = parseClassAnnotations(source);
    if (!parsed) continue;
    if (!parsed.annotations.some((a) => a.name === 'RestController')) continue;
    const mappingPathsList = parsed.annotations
      .filter((a) => a.name === 'RequestMapping')
      .flatMap((a) => mappingPaths(a.args));
    controllers.push({
      file,
      rel: relative(root, file),
      className: parsed.className,
      paths: mappingPathsList,
      guarded: hasMockGuard(parsed.annotations)
    });
  }

  const devMock = controllers.filter((c) => /^DevMock\w*Controller$/.test(c.className));
  const devPath = controllers.filter((c) =>
    c.paths.some((p) => p === '/api/v2/dev' || p.startsWith(DEV_PREFIX))
  );
  const mockNamed = controllers.filter((c) => /mock/i.test(c.className));

  for (const c of devMock) {
    if (!c.paths.length) {
      findings.push(
        `${c.rel} (${c.className})：没有类级 @RequestMapping，mock 路由无法确认落在 ${DEV_PREFIX}`
      );
    } else if (c.paths.some((p) => p !== '/api/v2/dev' && !p.startsWith(DEV_PREFIX))) {
      findings.push(
        `${c.rel} (${c.className})：mock 路由必须在 ${DEV_PREFIX} 下，实际 ${c.paths.map((p) => `"${p}"`).join(', ')}`
      );
    }
  }
  for (const c of devPath) {
    if (!c.guarded) {
      findings.push(
        `${c.rel} (${c.className})：路由落在 /api/v2/dev 下却没有 ${describeGuard()}，生产会保留 mock 入口`
      );
    }
  }
  const alreadyReported = new Set(devPath.filter((c) => !c.guarded).map((c) => c.file));
  for (const c of mockNamed) {
    if (!c.guarded && !alreadyReported.has(c.file)) {
      findings.push(`${c.rel} (${c.className})：类名含 Mock 却没有 ${describeGuard()}`);
    }
  }

  const validatorFile = join(root, VALIDATOR);
  let validatorOk = false;
  if (existsSync(validatorFile)) {
    const masked = stripJavaComments(readFileSync(validatorFile, 'utf8'));
    validatorOk = /throw\s+new\s+IllegalStateException\(\s*"[^"]*mock-enabled=true/.test(masked);
  }
  if (!validatorOk) {
    findings.push(
      `services/trade-service/src/main/java/com/aicabinet/trade/config/ProductionStartupValidator.java：` +
        `找不到「prod/staging 收到 mock-enabled=true 即抛 IllegalStateException」的守卫 —— 开关只能靠部署纪律拦，等于没有`
    );
  }

  return {
    controllerCount: controllers.length,
    devMock,
    devPath,
    mockNamed,
    validatorOk,
    findings
  };
}

export function checkProject(root, options = {}) {
  const minDevMock = options.minDevMock ?? MIN_DEV_MOCK_CLASSES;
  const minDevPath = options.minDevPath ?? MIN_DEV_PATH_CLASSES;
  const result = analyze(root);
  const findings = [...result.findings];

  if (result.devMock.length < minDevMock) {
    findings.push(
      `锚点失效：只找到 ${result.devMock.length} 个 DevMock 控制器（下限 ${minDevMock}）—— ` +
        `类被改名/移动而门禁没跟上时，规则会空转成恒绿`
    );
  }
  if (result.devPath.length < minDevPath) {
    findings.push(
      `锚点失效：只找到 ${result.devPath.length} 个 /api/v2/dev 控制器（下限 ${minDevPath}）`
    );
  }
  return { ok: findings.length === 0, ...result, findings };
}

function main() {
  const { ok, controllerCount, devMock, devPath, mockNamed, findings } = checkProject(ROOT);
  if (!ok) {
    console.error(`${TAG} FAIL: ${findings.length} 处 DevMock 范围问题：`);
    for (const f of findings) console.error(`  - ${f}`);
    console.error(
      `  修法：mock 控制器一律 @RequestMapping("${DEV_PREFIX}…") + ${describeGuard()}；` +
        `确实是正式契约的端点改名去掉 Mock`
    );
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${controllerCount} 个 @RestController，${devMock.length} 个 DevMock / ` +
      `${devPath.length} 个 /api/v2/dev 路由 / ${mockNamed.length} 个 Mock 命名类全部带 mock 开关且前缀隔离；` +
      `生产启动校验仍在拒绝 ${MOCK_KEY}=true`
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main();
}
