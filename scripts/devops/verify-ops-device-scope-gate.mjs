#!/usr/bin/env node
/**
 * check-ops-device-scope 的沙箱夹具（S1–S6）。
 *
 * 为什么必须独立夹具：本门禁钉的是「机制不被拆掉」。只在真实仓跑一次绿，证明不了
 * 它在机制被拆（删掉范围校验、注册加排除、order 提前、前缀改掉、查询串绕开）时真的会红——
 * 而 MA-068 家族复发四次，靠的正是「没人会发现」。
 *
 *   S1 基线（真实拦截器 + 注册 + 一个运营控制器）        → exit 0
 *   S2 拦截器删掉 requireDeviceAccess 调用               → 红（R1）
 *   S3 注册链加 excludePathPatterns                      → 红（R2）
 *   S4 注册 order(0)（早于 AuthInterceptor 拿身份）      → 红（R2）
 *   S5 OPS_PREFIX 改成 /api/v2/ops2/**                   → 红（R3）
 *   S6 新增运营端点：deviceId 走 @RequestParam 且不校验   → 红（R4）
 *
 * 零残留：全程只写 os.tmpdir() 沙箱；真实仓三个源文件 md5 前后断言一致。
 * 沙箱清理失败只告警（Windows safe-delete 垫片会拦批量删，不能让它把夹具判成假红）。
 */
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
  rmSync
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const TAG = '[verify-ops-device-scope-gate]';

const REL_INTERCEPTOR =
  'services/trade-service/src/main/java/com/aicabinet/trade/auth/OpsDeviceScopeInterceptor.java';
const REL_WEB = 'services/trade-service/src/main/java/com/aicabinet/trade/config/WebConfig.java';
const REL_CTRL =
  'services/trade-service/src/main/java/com/aicabinet/trade/api/AdminJiangyiController.java';
const REL_REPLENISH_SVC =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/OpsReplenishmentAdminService.java';
const REL_GATE = 'scripts/check-ops-device-scope.mjs';
const REL_BASELINE = 'scripts/ops-device-scope-baseline.json';

const md5 = (p) => createHash('md5').update(readFileSync(p)).digest('hex');
const watched = [REL_INTERCEPTOR, REL_WEB, REL_CTRL, REL_REPLENISH_SVC].map((rel) =>
  join(ROOT, rel)
);
const before = watched.map(md5);

const sandbox = mkdtempSync(join(tmpdir(), 'ops-device-scope-'));
const put = (rel) => {
  const dst = join(sandbox, rel);
  mkdirSync(dirname(dst), { recursive: true });
  return dst;
};
// 沙箱必须「足够像真实仓」：门禁的 4 跳解析依赖 service/mapper 源码在场，
// 只放 3 个文件会让「真校验」解析不到而被判成缺陷（首版实测：S1 直接红在
// AdminJiangyiController#orderVideosByDevice，验的是沙箱残缺而不是门禁）。
const copyTree = (relSrc) => {
  const srcRoot = join(ROOT, relSrc);
  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const src = join(dir, e.name);
      const rel = src.slice(ROOT.length + 1);
      if (e.isDirectory()) walk(src);
      else if (e.name.endsWith('.java')) {
        const dst = put(rel);
        writeFileSync(dst, readFileSync(src));
      }
    }
  };
  if (existsSync(srcRoot)) walk(srcRoot);
};
copyTree('services/trade-service/src/main/java');
// R5 要解析 @RequestBody 的 DTO；WriteOffRequest/StocktakeAdjustRequest 等落在 common-core，
// 不复制过来会让「请求体形态」在沙箱里恒无候选（S9/S10 会验出一片假绿）。
copyTree('services/common/common-core/src/main/java');
writeFileSync(put(REL_BASELINE), readFileSync(join(ROOT, REL_BASELINE)));

const gatePath = join(ROOT, REL_GATE);
const runGate = () => {
  const r = spawnSync(process.execPath, [gatePath], {
    encoding: 'utf8',
    env: { ...process.env, GATE_ROOT: sandbox, GATE_SKIP_SANITY: '1' }
  });
  return { code: r.status, out: `${r.stdout || ''}${r.stderr || ''}` };
};

const replaceIn = (rel, from, to) => {
  const p = join(sandbox, rel);
  const src = readFileSync(p, 'utf8');
  if (!src.includes(from)) throw new Error(`夹具锚点不唯一/不存在：${rel} :: ${from.slice(0, 60)}`);
  writeFileSync(p, src.replace(from, to), 'utf8');
};

const results = [];
function step(id, desc, fn) {
  try {
    const detail = fn();
    results.push({ id, desc, ok: true, detail });
  } catch (e) {
    results.push({ id, desc, ok: false, detail: e.message });
  }
}
const assert = (cond, msg) => {
  if (!cond) throw new Error(msg);
};

step('S1', '基线：真实机制（拦截器 + 注册 + 控制器）跑绿', () => {
  const r = runGate();
  assert(r.code === 0, `期望 exit 0，实得 ${r.code}：${r.out.trim()}`);
  return 'OK';
});

step('S2', '拦截器不再调用 requireDeviceAccess → R1 红', () => {
  // 回到 S1 现场
  writeFileSync(join(sandbox, REL_INTERCEPTOR), readFileSync(join(ROOT, REL_INTERCEPTOR)));
  replaceIn(
    REL_INTERCEPTOR,
    'merchantScopeService.requireDeviceAccess(operatorId, deviceId.trim());',
    '// 闸被拆（夹具注入）'
  );
  const r = runGate();
  assert(r.code === 1, `期望 exit 1，实得 ${r.code}`);
  assert(/R1 .*requireDeviceAccess/.test(r.out), `未命中 R1 判据：${r.out.trim()}`);
  return 'R1 命中';
});

step('S3', '注册链加 excludePathPatterns → R2 红', () => {
  writeFileSync(join(sandbox, REL_INTERCEPTOR), readFileSync(join(ROOT, REL_INTERCEPTOR)));
  writeFileSync(join(sandbox, REL_WEB), readFileSync(join(ROOT, REL_WEB)));
  replaceIn(
    REL_WEB,
    '.addPathPatterns(OpsDeviceScopeInterceptor.OPS_PREFIX)',
    '.addPathPatterns(OpsDeviceScopeInterceptor.OPS_PREFIX)\n                .excludePathPatterns("/api/v2/ops/admin/jiangyi/**")'
  );
  const r = runGate();
  assert(r.code === 1, `期望 exit 1，实得 ${r.code}`);
  assert(/R2 .*excludePathPatterns/.test(r.out), `未命中 R2 判据：${r.out.trim()}`);
  return 'R2 命中（排除口）';
});

step('S4', '注册 order(0)（与 AuthInterceptor 同序）→ R2 红', () => {
  writeFileSync(join(sandbox, REL_WEB), readFileSync(join(ROOT, REL_WEB)));
  replaceIn(REL_WEB, '.order(10)', '.order(0)');
  const r = runGate();
  assert(r.code === 1, `期望 exit 1，实得 ${r.code}`);
  assert(/R2 .*order=0/.test(r.out), `未命中 R2 判据：${r.out.trim()}`);
  return 'R2 命中（顺序）';
});

step('S5', 'OPS_PREFIX 改成 /api/v2/ops2/** → R3 红', () => {
  writeFileSync(join(sandbox, REL_WEB), readFileSync(join(ROOT, REL_WEB)));
  replaceIn(
    REL_INTERCEPTOR,
    'public static final String OPS_PREFIX = "/api/v2/ops/**";',
    'public static final String OPS_PREFIX = "/api/v2/ops2/**";'
  );
  const r = runGate();
  assert(r.code === 1, `期望 exit 1，实得 ${r.code}`);
  assert(/R3 OPS_PREFIX/.test(r.out), `未命中 R3 判据：${r.out.trim()}`);
  return 'R3 命中';
});

step('S6', '新增运营端点用 @RequestParam deviceId 且不校验 → R4 红', () => {
  writeFileSync(join(sandbox, REL_INTERCEPTOR), readFileSync(join(ROOT, REL_INTERCEPTOR)));
  replaceIn(
    REL_CTRL,
    '    /** 幂等 upsert 映射',
    [
      '    /** 夹具注入：查询串设备筛选，未过范围判据。 */',
      '    @RequiresPermissions("ops:device:list")',
      '    @GetMapping("/fixture/device-probe")',
      '    public ApiResponse<String> fixtureProbe(',
      '            @RequestParam(name = "deviceId", required = false) String deviceId) {',
      '        return ApiResponse.ok(String.valueOf(deviceId));',
      '    }',
      '',
      '    /** 幂等 upsert 映射'
    ].join('\n')
  );
  const r = runGate();
  assert(r.code === 1, `期望 exit 1，实得 ${r.code}`);
  assert(r.out.includes('fixtureProbe'), `未命中 R4 判据（输出未提夹具端点）：${r.out.trim()}`);
  return 'R4 命中';
});

step('S7', '隐式命名的 @RequestParam(required=false) String deviceId 且不校验 → R4 红', () => {
  // 2026-11-15：R4 只认 `name = "deviceId"` 那种写法，隐式命名（Spring 按形参名取参）
  // 整批跳过了判定面——首版实测 OpsReplenishmentController#inventory 等 6 个端点因此恒绿。
  writeFileSync(join(sandbox, REL_CTRL), readFileSync(join(ROOT, REL_CTRL)));
  replaceIn(
    REL_CTRL,
    '    /** 幂等 upsert 映射',
    [
      '    /** 夹具注入：隐式命名的查询串设备筛选，未过范围判据。 */',
      '    @RequiresPermissions("ops:device:list")',
      '    @GetMapping("/fixture/device-probe-implicit")',
      '    public ApiResponse<String> fixtureProbeImplicit(',
      '            @RequestParam(required = false) String deviceId) {',
      '        return ApiResponse.ok(String.valueOf(deviceId));',
      '    }',
      '',
      '    /** 幂等 upsert 映射'
    ].join('\n')
  );
  const r = runGate();
  assert(r.code === 1, `期望 exit 1，实得 ${r.code}`);
  assert(/fixtureProbeImplicit/.test(r.out), `未命中 R4 判据（输出未提夹具端点）：${r.out.trim()}`);
  return 'R4 命中（隐式命名）';
});

step('S8', '把已修端点的范围下推退回 null → R4 红（证明修复是承重的）', () => {
  writeFileSync(join(sandbox, REL_CTRL), readFileSync(join(ROOT, REL_CTRL)));
  writeFileSync(join(sandbox, REL_REPLENISH_SVC), readFileSync(join(ROOT, REL_REPLENISH_SVC)));
  replaceIn(
    REL_REPLENISH_SVC,
    [
      '        return replenishmentService.listRoutesPage(',
      '                merchantScopeService.pushdownDeviceIds(operatorId, deviceId), page, size);'
    ].join('\n'),
    '        return replenishmentService.listRoutesPage(null, page, size);'
  );
  const r = runGate();
  assert(r.code === 1, `期望 exit 1，实得 ${r.code}`);
  assert(
    /OpsReplenishmentController#routes/.test(r.out),
    `未命中 R4 判据（应点名 routes）：${r.out.trim()}`
  );
  return 'R4 命中（C43 回退）';
});

// ── R5（2026-11-17 F2）：请求体 deviceId 形态 ────────────────────────────────
// 这一族此前必然假绿：候选集只从「路径变量 + @RequestParam」建，deviceId 一旦进 body
// 就整批落在判定面之外——补货代开门跨商户物理开门、报损/盘调改别家库存都是这么漏的。
const REL_FIXTURE_DTO =
  'services/trade-service/src/main/java/com/aicabinet/trade/domain/FixtureDeviceBodyRequest.java';
const REL_INV_SVC =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/InventoryOpsService.java';

step('S9', '新增写端点：deviceId 在 @RequestBody DTO 且不校验 → R5 红', () => {
  // S8 把 routes 的范围下推拆掉了；不还原的话本步红的是残留缺陷，验不到 R5。
  writeFileSync(join(sandbox, REL_REPLENISH_SVC), readFileSync(join(ROOT, REL_REPLENISH_SVC)));
  writeFileSync(join(sandbox, REL_CTRL), readFileSync(join(ROOT, REL_CTRL)));
  writeFileSync(
    put(REL_FIXTURE_DTO),
    [
      'package com.aicabinet.trade.domain;',
      '',
      '/** 夹具注入：请求体带设备字段的 DTO（R5 判定面）。 */',
      'public record FixtureDeviceBodyRequest(String deviceId, int quantity) {}',
      ''
    ].join('\n')
  );
  replaceIn(
    REL_CTRL,
    '    /** 幂等 upsert 映射',
    [
      '    /** 夹具注入：deviceId 藏在请求体里，未过范围判据。 */',
      '    @RequiresPermissions("ops:device:edit")',
      '    @PostMapping("/fixture/device-body-probe")',
      '    public ApiResponse<String> fixtureBodyProbe(',
      '            HttpServletRequest request,',
      '            @RequestBody com.aicabinet.trade.domain.FixtureDeviceBodyRequest body) {',
      '        return ApiResponse.ok(String.valueOf(body.deviceId()));',
      '    }',
      '',
      '    /** 幂等 upsert 映射'
    ].join('\n')
  );
  const r = runGate();
  assert(r.code === 1, `期望 exit 1，实得 ${r.code}`);
  assert(/fixtureBodyProbe/.test(r.out), `未命中 R5 判据（输出未提夹具端点）：${r.out.trim()}`);
  return 'R5 命中（请求体 deviceId）';
});

step('S10', '把报损的设备侧范围闸退回（N-06 回退）→ R5 红', () => {
  writeFileSync(join(sandbox, REL_REPLENISH_SVC), readFileSync(join(ROOT, REL_REPLENISH_SVC)));
  writeFileSync(join(sandbox, REL_CTRL), readFileSync(join(ROOT, REL_CTRL)));
  writeFileSync(join(sandbox, REL_INV_SVC), readFileSync(join(ROOT, REL_INV_SVC)));
  replaceIn(
    REL_INV_SVC,
    '            merchantScopeService.requireDeviceAccess(operatorId, request.deviceId());',
    '            // 闸被拆（夹具注入）'
  );
  const r = runGate();
  assert(r.code === 1, `期望 exit 1，实得 ${r.code}`);
  assert(
    /OpsReplenishmentController#writeOff/.test(r.out),
    `未命中 R5 判据（应点名 writeOff）：${r.out.trim()}`
  );
  return 'R5 命中（N-06 回退）';
});

// ── 零残留 ──
const after = watched.map(md5);
const residueOk = before.every((h, i) => h === after[i]);

try {
  rmSync(sandbox, { recursive: true, force: true });
} catch (e) {
  console.log(`${TAG} WARN 沙箱清理失败（不影响结论）：${e.message}`);
}

let failed = 0;
for (const r of results) {
  console.log(`${TAG} ${r.ok ? 'PASS' : 'FAIL'} ${r.id} ${r.desc}${r.ok ? '' : ` —— ${r.detail}`}`);
  if (!r.ok) failed++;
}
if (!residueOk) {
  console.log(`${TAG} FAIL 真实仓文件被改动（md5 前后不一致）—— 夹具必须零残留`);
  failed++;
}
if (failed) {
  console.error(`${TAG} ${failed} 项失败`);
  process.exit(1);
}
console.log(`${TAG} 全部通过（${results.length} 项），零残留`);
