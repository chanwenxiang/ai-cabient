/**
 * 防绕过自测：`check-permission-matrix` 必须**会红**（注解放行、服务层必 403）、
 * **不假红**（对齐/AND 双门），并且**不许偷偷漏**（解析退化锚、豁免清单缺字段/腐烂/超上限）。
 *
 * Run: node scripts/check-permission-matrix.test.mjs
 *
 * 为什么必须有这个文件：本门禁首版有两个只会「安静地绿」或「安静地错」的坑——
 *  1) 注解与方法签名之间夹着 `@Operation(...)` 时，向后找签名会把注解绑到**下一个**方法上
 *     （假阳性一堆，真缺陷被淹没）；
 *  2) CRLF 文件里用 `lines[k].length + 1` 累加行首偏移会整体前移，方法体切片错位
 *     ⇒ 服务层校验被读丢，`两侧都有校验` 从 196 掉到 119（真缺陷静默消失）。
 * 两条都各配一个用例钉住。
 *
 * 🔴 沙箱纪律：夹具一律写在 `.tmp` 下的合成树里跑，绝不往真实树写探针；
 *    收尾断言门禁脚本与豁免清单逐字节未变。
 */
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runAsync } from './lib/async-spawn.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-permission-matrix.test]';
const GATE = join(ROOT, 'scripts', 'check-permission-matrix.mjs');
const EXEMPT = join(ROOT, 'scripts', 'permission-matrix-exemptions.json');
const SANDBOX = join(ROOT, '.tmp', `perm-matrix-selftest-${process.pid}`);

let failures = 0;
function check(name, cond, extra = '') {
  if (cond) console.log(`  ok   ${name}`);
  else {
    failures++;
    console.error(`  RED  ${name}${extra ? ` —— ${extra}` : ''}`);
  }
}

function ctrl(codes, body = 'return fooService.create();', logical = 'OR') {
  const arg =
    codes.length === 1
      ? `"${codes[0]}"`
      : `value = {${codes.map((c) => `"${c}"`).join(', ')}}` +
        (logical === 'AND' ? ', logical = RequiresPermissions.Logical.AND' : '');
  return `package com.aicabinet.trade.api;

public class FooController {
    private final FooService fooService;

    @Operation(summary = "中间隔一个注解，考察绑定是否会串到下一个方法")
    @RequiresPermissions(${arg})
    public String create() {
        ${body}
    }

    public String unrelated() {
        return "other";
    }
}
`;
}

function svc(literal, useConst = false, branch = false) {
  const code = useConst ? 'PERM_CREATE' : `"${literal}"`;
  const check = `permissionService.requirePermission(1L, ${code});`;
  return `package com.aicabinet.trade.service;

public class FooService {
    ${useConst ? 'private static final String PERM_CREATE = "ops:doc:create";' : ''}

    public String create() {
        ${branch ? `if (priceCents != null) {\n            ${check}\n        }` : check}
        return "ok";
    }
}
`;
}

function mkTree({ annotation, service, crlf = false }) {
  rmSync(SANDBOX, { recursive: true, force: true });
  mkdirSync(join(SANDBOX, 'api'), { recursive: true });
  mkdirSync(join(SANDBOX, 'service'), { recursive: true });
  const eol = crlf ? '\r\n' : '\n';
  writeFileSync(join(SANDBOX, 'api', 'FooController.java'), annotation.replace(/\n/g, eol));
  writeFileSync(join(SANDBOX, 'service', 'FooService.java'), service.replace(/\n/g, eol));
}

async function runGate({
  exempt = null,
  anchors = true,
  maxExemptions = null,
  extraEnv = {},
  rule1Anchors = true
} = {}) {
  const env = {
    ...process.env,
    PERM_MATRIX_ROOT: SANDBOX,
    PERM_MIN_JAVA_FILES: '1',
    PERM_MIN_ANNOTATIONS: rule1Anchors ? '1' : '0',
    PERM_MIN_BOUND: rule1Anchors ? '1' : '0',
    PERM_MIN_BOTH: rule1Anchors ? '1' : '0',
    PERM_MIN_WRITE: '0', // 规则 1 夹具里没有写映射，别让 write 锚点把用例拦在规则 2 之前
    ...extraEnv
  };
  if (!anchors) {
    delete env.PERM_MIN_JAVA_FILES;
    delete env.PERM_MIN_ANNOTATIONS;
    delete env.PERM_MIN_BOUND;
    delete env.PERM_MIN_BOTH;
    delete env.PERM_MIN_WRITE;
  }
  if (maxExemptions != null) env.PERM_MAX_EXEMPTIONS = String(maxExemptions);
  if (exempt) env.PERM_MATRIX_EXEMPTIONS = join(SANDBOX, 'exemptions.json');
  else env.PERM_MATRIX_EXEMPTIONS = join(SANDBOX, 'no-such-file.json');
  // 🔴 规则 2 的基线一律指到沙箱文件：指到真实清单的话，夹具跑出来的永远是
  // 「真树 37 条全腐烂」，该绿的用例会被基线噪声判红。
  env.PERM_UNCLASSIFIED = join(SANDBOX, 'unclassified.json');
  if (!existsSync(env.PERM_UNCLASSIFIED)) {
    writeFileSync(env.PERM_UNCLASSIFIED, '[]\n');
  }
  if (env.PERM_UNCLASSIFIED_CAP == null) env.PERM_UNCLASSIFIED_CAP = '99';
  const r = await runAsync(process.execPath, [GATE], { env, cwd: ROOT });
  return { code: r.status ?? 1, out: `${r.stdout || ''}${r.stderr || ''}` };
}

/** 规则 2 夹具：一个没有任何授权痕迹的写端点，可换基路径与类注解 */
function bareCtrl(opts = {}) {
  const { verb = 'Post', path = '/api/v2/things', classAnn = '', body = 'return "ok";' } = opts;
  return `package com.aicabinet.trade.api;

@RestController
@RequestMapping("/api/v2")
${classAnn}
public class FooWriteController {
    @${verb}Mapping("${path.replace('/api/v2', '')}")
    public String doIt() {
        ${body}
    }
}
`;
}

/** 规则 2 用：往沙箱里只放一组控制器/服务文件 */
function mkFiles(files) {
  rmSync(SANDBOX, { recursive: true, force: true });
  mkdirSync(join(SANDBOX, 'api'), { recursive: true });
  mkdirSync(join(SANDBOX, 'service'), { recursive: true });
  for (const [name, content] of Object.entries(files)) {
    const sub = name.startsWith('api/') ? 'api' : 'service';
    writeFileSync(join(SANDBOX, sub, name.split('/')[1]), content.replace(/\n/g, '\n'));
  }
}

const gateSrc = readFileSync(GATE, 'utf8');
const exemptSrc = readFileSync(EXEMPT, 'utf8');

try {
  // 1) 真实树：当前必须绿（存量已登记），且绿不是靠「读不到豁免清单」
  const liveExempt = JSON.parse(readFileSync(EXEMPT, 'utf8')).length;
  const real = await runAsync(process.execPath, [GATE], { cwd: ROOT });
  const realOut = `${real.stdout || ''}${real.stderr || ''}`;
  check(
    `真实树 exit=0 且报「豁免 ${liveExempt}」（与清单条数一致，不是靠读不到清单）`,
    (real.status ?? 1) === 0 && realOut.includes('豁免 ' + liveExempt),
    realOut.slice(0, 200)
  );

  // 2) 幽灵码必红：注解 ops:doc:edit，服务只认 ops:doc:create
  mkTree({
    annotation: ctrl(['ops:doc:edit']),
    service: svc('ops:doc:create')
  });
  const ghost = await runGate();
  check(
    '注解放行、服务层必 403 ⇒ 红并点名端点',
    ghost.code === 1 &&
      /FooController\.java#create/.test(ghost.out) &&
      /ops:doc:edit/.test(ghost.out),
    ghost.out.slice(0, 300)
  );

  // 3) 对齐 ⇒ 不假红
  mkTree({ annotation: ctrl(['ops:doc:create']), service: svc('ops:doc:create') });
  const ok = await runGate();
  check('注解与服务层同码 ⇒ 绿', ok.code === 0 && /OK/.test(ok.out), ok.out.slice(0, 300));

  // 4) 常量引用也要认（服务层写 PERM_CREATE 而非裸串）
  mkTree({ annotation: ctrl(['ops:doc:edit']), service: svc(null, true) });
  const constCase = await runGate();
  check(
    '服务层用常量 ⇒ 仍判红（常量被解析成 ops:doc:create）',
    constCase.code === 1 && /ops:doc:create/.test(constCase.out),
    constCase.out.slice(0, 300)
  );

  // 5) 有注解但服务层查不到校验 ⇒ 不许当成幽灵码（「看不见」不能变成「红」，也不能变成假绿：它记在 no-service-check）
  mkTree({
    annotation: ctrl(['ops:doc:create']).replace(
      'public String unrelated() {',
      '@RequiresPermissions("ops:doc:edit")\n    public String unrelated() {'
    ),
    service: svc('ops:doc:create')
  });
  const binding = await runGate();
  check(
    '无服务层校验的端点不被误报为幽灵（且对齐的那条不红）',
    binding.code === 0 && /OK/.test(binding.out),
    binding.out.slice(0, 400)
  );

  // 5b) 签名跨行仍要绑对（首版就是在这里漏掉 246 个端点 ⇒ 静默假绿）
  mkTree({
    annotation: ctrl(['ops:doc:edit']).replace(
      'public String create() {',
      'public String create(\n            @RequestBody String a,\n            @RequestHeader String b) {'
    ),
    service: svc('ops:doc:create')
  });
  const multiline = await runGate();
  check(
    '跨行签名 + 多参数的方法体仍能解析 ⇒ 幽灵码报出来',
    multiline.code === 1 && /FooController\.java#create/.test(multiline.out),
    multiline.out.slice(0, 400)
  );

  // 6) Logical.AND 双门 ⇒ 全码满足者能过服务层，不算幽灵（不误报）
  mkTree({
    annotation: ctrl(['ops:rbac:assign', 'ops:doc:create'], 'return fooService.create();', 'AND'),
    service: svc('ops:doc:create')
  });
  const andCase = await runGate();
  check('Logical.AND 满足者含服务层码 ⇒ 绿', andCase.code === 0, andCase.out.slice(0, 300));

  // 7) CRLF 回归：同一夹具换成 CRLF 必须与 LF 同判（钉住行首偏移漂移）
  mkTree({
    annotation: ctrl(['ops:doc:edit']),
    service: svc('ops:doc:create'),
    crlf: true
  });
  const crlfCase = await runGate();
  check(
    'CRLF 树结果与 LF 一致（方法体切片不错位）',
    crlfCase.code === 1 && /FooController\.java#create/.test(crlfCase.out),
    crlfCase.out.slice(0, 300)
  );

  // 7b) 分支权限不算恒需：require 包在 `if (...)` 里 ⇒ 只在该分支生效，不得判成幽灵码
  //     （批次 Q 实测纠正：`JiangyiShelfService.shelve` 按 priceCents/quantity 分权，首版被我误判）
  mkTree({ annotation: ctrl(['ops:doc:edit']), service: svc('ops:doc:create', false, true) });
  const branchCase = await runGate();
  check(
    'if 包裹的服务层校验 ⇒ 不判红（按字段分权是合法形态）',
    branchCase.code === 0 && /OK/.test(branchCase.out),
    branchCase.out.slice(0, 300)
  );

  // 7c) 同一把校验从 if 里拿出来 ⇒ 必须立刻判红（证明 7b 不是「看不见所以绿」）
  mkTree({ annotation: ctrl(['ops:doc:edit']), service: svc('ops:doc:create', false, false) });
  const unbranched = await runGate();
  check(
    '把同一道校验移出 if ⇒ 判红（分支判据可失败）',
    unbranched.code === 1 && /FooController\.java#create/.test(unbranched.out),
    unbranched.out.slice(0, 300)
  );

  // 8) 解析退化锚：关掉 env 下调、用只含 1 个注解的合成树 ⇒ 必须红
  const degraded = await runGate({ anchors: false });
  check(
    '锚点生效：合成树达不到真实树锚点 ⇒ 拒绝给绿（防解析退化假绿）',
    degraded.code === 1 && /锚点/.test(degraded.out),
    degraded.out.slice(0, 300)
  );

  // 9) 豁免清单缺字段 ⇒ fail-closed
  writeFileSync(join(SANDBOX, 'exemptions.json'), JSON.stringify([{ ep: 'x#y' }], null, 2));
  const badExempt = await runGate({ exempt: true });
  check(
    '豁免条目缺 family/reason/ask ⇒ 红（不许空壳豁免）',
    badExempt.code === 1 && /缺 family/.test(badExempt.out),
    badExempt.out.slice(0, 300)
  );

  // 10) 豁免超上限 ⇒ 红（只许减不许加）
  writeFileSync(
    join(SANDBOX, 'exemptions.json'),
    JSON.stringify(
      [
        { ep: 'a#b', family: 'f', reason: 'r', ask: 'q' },
        { ep: 'c#d', family: 'f', reason: 'r', ask: 'q' }
      ],
      null,
      2
    )
  );
  const overCap = await runGate({ exempt: true, maxExemptions: 1 });
  const overCapOut = overCap.out;
  check(
    '豁免条数 > 上限 ⇒ 红（幽灵按钮只能修不能登记）',
    overCap.code === 1 && /上限/.test(overCapOut),
    overCapOut.slice(0, 300)
  );

  // 11) 清单腐烂：登记的端点其实不冲突 ⇒ 红
  mkTree({ annotation: ctrl(['ops:doc:create']), service: svc('ops:doc:create') });
  writeFileSync(
    join(SANDBOX, 'exemptions.json'),
    JSON.stringify(
      [{ ep: 'services/x/FooController.java#create', family: 'f', reason: 'r', ask: 'q' }],
      null,
      2
    )
  );
  const stale = await runGate({ exempt: true });
  check(
    '豁免清单里有已不冲突的条目 ⇒ 红（防腐烂）',
    stale.code === 1 && /腐烂/.test(stale.out),
    stale.out.slice(0, 300)
  );

  // 12) 规则 2：裸写端点（没注解、没 require*、不在白名单、不在 /internal）⇒ 必须红
  mkFiles({ 'api/FooWriteController.java': bareCtrl() });
  const bare = await runGate({ rule1Anchors: false });
  check(
    '规则 2：无授权痕迹的 POST ⇒ 红并点名端点与路径',
    bare.code === 1 &&
      /FooWriteController\.java#doIt/.test(bare.out) &&
      /POST \/api\/v2\/things/.test(bare.out),
    bare.out.slice(0, 300)
  );

  // 13) 补了权限注解 ⇒ 归 ops-perm，不再算问题
  mkFiles({
    'api/FooWriteController.java': bareCtrl().replace(
      '    @PostMapping("/things")',
      '    @RequiresPermissions("ops:x:edit")\n    @PostMapping("/things")'
    )
  });
  const gated = await runGate({ rule1Anchors: false });
  check('规则 2：有 @RequiresPermissions 的 POST ⇒ 绿', gated.code === 0, gated.out.slice(0, 300));

  // 14) /internal/** 走 fleet key 拦截器 ⇒ machine-token，不该要求权限码
  mkFiles({
    'api/FooWriteController.java': bareCtrl({ path: '/internal/v1/things' }).replace(
      '@RequestMapping("/api/v2")',
      '@RequestMapping("/internal/v1")'
    )
  });
  const internal = await runGate({ rule1Anchors: false });
  check(
    '规则 2：/internal/** 写端点 ⇒ 绿（machine-token）',
    internal.code === 0,
    internal.out.slice(0, 300)
  );

  // 15) dev/mock 专用 bean（生产不开）⇒ dev-only，不该算没门
  mkFiles({
    'api/FooWriteController.java': bareCtrl({
      classAnn:
        '@ConditionalOnProperty(name = "aicabinet.security.mock-enabled", havingValue = "true")'
    })
  });
  const devOnly = await runGate({ rule1Anchors: false });
  check(
    '规则 2：@ConditionalOnProperty 的 dev bean ⇒ 绿（dev-only）',
    devOnly.code === 0,
    devOnly.out.slice(0, 300)
  );

  // 16) 基线腐烂：登记的端点其实已能自动归类 ⇒ 红
  mkFiles({
    'api/FooWriteController.java': bareCtrl().replace(
      '    @PostMapping("/things")',
      '    @RequiresPermissions("ops:x:edit")\n    @PostMapping("/things")'
    )
  });
  writeFileSync(
    join(SANDBOX, 'unclassified.json'),
    JSON.stringify(
      [{ ep: 'x/y/FooWriteController.java#doIt', category: 'self-service', reason: 'r' }],
      null,
      2
    )
  );
  const staleUncl = await runGate({
    extraEnv: { PERM_UNCLASSIFIED: join(SANDBOX, 'unclassified.json'), PERM_UNCLASSIFIED_CAP: '9' },
    rule1Anchors: false
  });
  check(
    '规则 2：基线里有已可归类的条目 ⇒ 红（腐烂）',
    staleUncl.code === 1 && /腐烂/.test(staleUncl.out),
    staleUncl.out.slice(0, 300)
  );

  // 17) 基线超上限 ⇒ 红（只许减不许加）
  mkFiles({ 'api/FooWriteController.java': bareCtrl() });
  writeFileSync(
    join(SANDBOX, 'unclassified.json'),
    JSON.stringify(
      [
        { ep: '.tmp/a/FooWriteController.java#doIt', category: 'self-service', reason: 'r' },
        { ep: '.tmp/b/FooWriteController.java#doIt', category: 'self-service', reason: 'r' }
      ],
      null,
      2
    )
  );
  const overCapUncl = await runGate({
    extraEnv: { PERM_UNCLASSIFIED_CAP: '1' },
    rule1Anchors: false
  });
  check(
    '规则 2：未分类清单 > 上限 ⇒ 红（新写端点必须自带门或显式声明）',
    overCapUncl.code === 1 && /只能减不能加/.test(overCapUncl.out),
    overCapUncl.out.slice(0, 300)
  );

  // 12) 工件未被测试写坏
  check('门禁脚本未被改动', readFileSync(GATE, 'utf8') === gateSrc);
  check('真实豁免清单未被改动', readFileSync(EXEMPT, 'utf8') === exemptSrc);
} finally {
  rmSync(SANDBOX, { recursive: true, force: true });
  check('沙箱已删', !existsSync(SANDBOX));
}

if (failures) {
  console.error(`${TAG} FAIL：${failures} 个用例红`);
  process.exit(1);
}
console.log(
  `${TAG} OK：负向用例全过（规则 1：CRLF / 跨行签名 / 分支权限三组回归钉；规则 2：裸写端点·internal·dev bean·腐烂·超上限六条）`
);
