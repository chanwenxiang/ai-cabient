#!/usr/bin/env node
/**
 * `check-write-endpoint-attribution.mjs`（S9 第二层：端点级归因快照棘轮）的夹具。
 *
 * 覆盖三组：
 *   A. evaluate 棘轮（合成快照）：归因退化红 / 新端点未归因红 / 对平绿 / 陈旧红 /
 *      归因虚增红 / 端点漂移红（总量双向对平）；
 *   B. reachable 真实仓面：2-hop 委托链可达（OpsOtaAdminService.publishOta ⇒
 *      OtaService.publishRelease 有 appendLog）/ 失败路径审计不计（AccessDeniedAudit）/
 *      真缺口端点不可达（AccountController.updateNickname 无审计链）/
 *      **行为级负向**：失败审计类换类名 / 换 action 都自动纳入，逐调用点排除
 *      ACCESS_DENIED（2026-11-14 第五轮：旧人工名单改类名即 18 处假归因复活）；
 *   C. controllerEndpoints 真实仓面：端点抽取数量与快照一致。
 *
 *   node scripts/check-write-endpoint-attribution.test.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  evaluate,
  reachable,
  controllerEndpoints,
  deriveFailurePathClasses,
  hasBusinessAuditCall
} from './check-write-endpoint-attribution.mjs';

const TAG = '[check-write-endpoint-attribution.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function walkJava(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walkJava(p, out);
    else if (n.endsWith('.java')) out.push(p);
  }
  return out;
}

// ── A. evaluate 棘轮（合成快照） ──

// A1) 对平 ⇒ 绿
{
  const snap = [{ file: 'a/XController.java', endpoints: 3, attributed: 2 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  assert(evaluate(snap, base).length === 0, '对平应绿');
}

// A2) 归因退化（2→1）⇒ 红
{
  const snap = [{ file: 'a/XController.java', endpoints: 3, attributed: 1 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  const p = evaluate(snap, base);
  assert(p.length === 1 && p[0].includes('归因退化'), `归因退化须红：${p.join('|')}`);
}

// A3) 新端点未归因（endpoints 3→4，attributed 不变）⇒ 红
{
  const snap = [{ file: 'a/XController.java', endpoints: 4, attributed: 2 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  const p = evaluate(snap, base);
  assert(p.length === 1 && p[0].includes('新增'), `新端点未归因须红：${p.join('|')}`);
}

// A4) 新端点且归因同步增 ⇒ 绿
{
  const snap = [{ file: 'a/XController.java', endpoints: 4, attributed: 3 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  assert(evaluate(snap, base).length === 0, '新端点归因同步增应绿');
}

// A5) 基线陈旧（控制器没了）⇒ 红
{
  const p = evaluate([], { 'gone/XController.java': { endpoints: 1, attributed: 1 } });
  assert(p[0].includes('基线陈旧'), `陈旧须红：${p.join('|')}`);
}

// A6) 归因虚增（240→258 假归因复活形态）⇒ 总量对平红
//     逐控制器判据只防下降，这条专门钉住「解析链放宽」方向。
{
  const snap = [{ file: 'a/XController.java', endpoints: 3, attributed: 3 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  const p = evaluate(snap, base, { totalEndpoints: 3, totalAttributed: 2 });
  assert(
    p.some((x) => x.includes('归因总量')),
    `归因虚增必须红：${p.join('|')}`
  );
}

// A7) 端点数下降（逐控制器判据不检查下降）⇒ 总量对平红
{
  const snap = [{ file: 'a/XController.java', endpoints: 2, attributed: 2 }];
  const base = { 'a/XController.java': { endpoints: 3, attributed: 2 } };
  const p = evaluate(snap, base, { totalEndpoints: 3, totalAttributed: 2 });
  assert(
    p.some((x) => x.includes('端点总量')),
    `端点漂移必须红：${p.join('|')}`
  );
}

// A8) totals 不传（旧夹具调用形态）⇒ 不受影响
assert(
  evaluate([{ file: 'a/XController.java', endpoints: 3, attributed: 3 }], {
    'a/XController.java': { endpoints: 3, attributed: 2 }
  }).length === 0,
  '不传 totals 时保持旧行为'
);

// ── B. reachable 真实仓面 ──

// B1) 2-hop 委托链可达：OpsOtaAdminService.publishOta ⇒ otaService.publishRelease ⇒ appendLog
assert(
  reachable('OpsOtaAdminService', 'publishOta', 1, new Set()) === true,
  'OpsOtaAdminService.publishOta 应 2-hop 可达 OtaService.publishRelease 的审计'
);

// B2) 拒绝路径黑名单：AccessDeniedAudit.persistDenied 的 appendLog 不计
assert(
  reachable('AccessDeniedAudit', 'persistDenied', 1, new Set()) === false,
  'AccessDeniedAudit（拒绝路径审计）应被黑名单排除'
);

// B3) 真缺口端点不可达：AccountController.updateNickname 委托链无审计
assert(
  reachable('AccountService', 'updateNickname', 1, new Set()) === false,
  'AccountService.updateNickname 委托链无审计（真缺口）'
);

// B4) 失败路径类自动派生（行为级负向，替代旧「黑名单存在性」静态断言）
//     旧式 new Set(['AccessDeniedAudit'])：把类名改成 TmpDeniedAudit 即 240→258 假绿。
{
  const byClassLike = new Map([
    ['TmpDeniedAudit', 'x.java'],
    ['RenamedAudit', 'y.java'],
    ['PlainAudit', 'z.java']
  ]);
  const rawOfLike = new Map([
    [
      'x.java',
      'class TmpDeniedAudit { void denied() { audit.appendLog(1, "SOME_ACTION", "USER", "1", ""); } }'
    ],
    [
      'y.java',
      'class RenamedAudit { void log() { audit.appendLog(1, "ACCESS_DENIED", "USER", "1", ""); } }'
    ],
    [
      'z.java',
      'class PlainAudit { void log() { audit.appendLog(1, "OTA_PUBLISH", "OTA", "1", ""); } }'
    ]
  ]);
  const derived = deriveFailurePathClasses(byClassLike, rawOfLike);
  assert(derived.has('TmpDeniedAudit'), '类名带 denied 语义的失败审计类须自动纳入');
  assert(derived.has('RenamedAudit'), '写 ACCESS_DENIED 的类即使换成无关类名也须自动纳入');
  assert(!derived.has('PlainAudit'), '普通业务审计类不该被误纳入');
}

// B6) 逐调用点排除失败 action（不能只看方法体有没有 appendLog）
assert(
  hasBusinessAuditCall('void x() { audit.appendLog(1, "ACCESS_DENIED", "USER", "1", ""); }') ===
    false,
  '只写 ACCESS_DENIED 的方法体不该算业务归因'
);
assert(
  hasBusinessAuditCall('void x() { audit.appendLog(1, "OTA_PUBLISH", "OTA", "1", ""); }') === true,
  '正常业务 appendLog 应算归因'
);
assert(
  hasBusinessAuditCall(
    'void x() { if (denied) { audit.appendLog(1, "ACCESS_DENIED", "USER", "1", ""); } ' +
      'else { audit.appendLog(1, "OTA_PUBLISH", "OTA", "1", ""); } }'
  ) === true,
  '同一方法体内失败路径与成功路径并存时应算归因'
);
assert(
  hasBusinessAuditCall(
    'void x() { audit.appendLog(1, "OTA_PUBLISH", "OTA", "1", Map.of("k", "v")); }'
  ) === true,
  '参数含嵌套括号时不得截断 action 判定'
);
assert(
  hasBusinessAuditCall(
    'void x() { audit.appendLog(1, "ACCESS_DENIED", "USER", "1", ""); }',
    'AccessDeniedAudit'
  ) === false,
  '失败路径类的整个方法体直接不算'
);

// B5) AdminAuditService.appendLog 定义到达 = 归因（2026-11-14 修复的正向用例）
assert(
  reachable('AdminAuditService', 'appendLog', 1, new Set()) === true,
  'AdminAuditService.appendLog 定义应被判为归因终点'
);
assert(
  reachable('AdminAuditService', 'appendLogIndependent', 1, new Set()) === true,
  'AdminAuditService.appendLogIndependent 定义应被判为归因终点'
);

// ── C. controllerEndpoints 真实仓面 ──

// C1) OpsOtaController：2 写端点全归因（自身 appendLog + service 链）
{
  const src = readFileSync(
    resolve(
      ROOT,
      'services/trade-service/src/main/java/com/aicabinet/trade/api/OpsOtaController.java'
    ),
    'utf8'
  );
  const { cls, endpoints } = controllerEndpoints(src);
  assert(cls === 'OpsOtaController', `cls 提取错：${cls}`);
  assert(endpoints.length === 2, `端点数错：${endpoints.length}`);
  assert(
    endpoints.every((e) => e.attributed),
    'OpsOta 端点应全归因'
  );
}

// C2) 端点级快照与基线对平（真实仓面全量）
{
  const baseline = JSON.parse(
    readFileSync(resolve(ROOT, 'scripts/write-endpoint-attribution-baseline.json'), 'utf8')
  );
  const baselineEntries = baseline.controllers || {};
  const entryCount = Object.keys(baselineEntries).length;
  assert(entryCount >= 90, `基线条目应 ≥90，实际 ${entryCount}`);
  assert((baseline.totalEndpoints || 0) >= 400, `基线端点应 ≥400，实际 ${baseline.totalEndpoints}`);
  // C3) maxUnattributed 棘轮与快照口径一致：未归因存量 = 天花板（冻结只减，
  //     2026-11-15 解析面纠偏后 215→222，此后只许减）
  {
    const unattr = (baseline.totalEndpoints || 0) - (baseline.totalAttributed || 0);
    assert(
      typeof baseline.maxUnattributed === 'number' && baseline.maxUnattributed === unattr,
      `maxUnattributed(${baseline.maxUnattributed}) 应等于未归因存量(${unattr})——重种快照后须同步`
    );
  }

  // C4) 声明解析盲区（C06/MA-267）：限定名返回类型 + 注解与签名同行 + 路径值内含正则，
  //     三种形态都必须被解析出来。旧判据（`/public\s+[\w<>,\s]+?\s+\w+\s*\(/`、窗口从 i+1 起）
  //     对这三种一律 sig<0 ⇒ 端点被**静默跳过**：不进快照、不受棘轮约束、基线由坏解析器自证。
  {
    const src = [
      '@RestController',
      'public class SynthParseController {',
      '    @PostMapping("/payscore/sign")',
      '    public ApiResponse<com.aicabinet.common.dto.PayContractDto> signPayScore(HttpServletRequest r) {',
      '        return ApiResponse.ok(svc.sign(r));',
      '    }',
      '    @PostMapping("/{id}/retry") public ApiResponse<OpsExceptionDto> retry(HttpServletRequest r) {',
      '        return ApiResponse.ok(svc.retry(r));',
      '    }',
      '    @DeleteMapping("/{configKey:.+}")',
      '    public ApiResponse<Void> drop(@PathVariable String k) {',
      '        return ApiResponse.ok(svc.drop(k));',
      '    }',
      '    /** 注释里的 @PostMapping("/fake") 不算端点 */',
      '    @GetMapping("/read")',
      '    public ApiResponse<Void> read() { return ApiResponse.ok(); }',
      '}'
    ].join('\n');
    const { cls, endpoints } = controllerEndpoints(src);
    assert(cls === 'SynthParseController', `合成类名提取错：${cls}`);
    assert(
      endpoints.length === 3,
      `限定名返回类型/同行签名必须解析出来（旧判据漏解析）：实际 ${endpoints.length}`
    );
    const names = endpoints.map((e) => e.name).join(',');
    assert(
      names === 'signPayScore,retry,drop',
      `方法名不得被注解名顶替（@PostMapping( 会先命中）：实际 ${names}`
    );
    assert(
      endpoints.every((e) => e.attributed === false),
      '合成源码无审计链，不该判成归因'
    );
  }

  // C6) 路径占位符不得截断方法体（2026-11-16 S9）：
  //     `@PostMapping("/{id}/retry")` 的 `{id}` 若参与花括号计数，签名行 depth 归零，
  //     方法体只剩一行，体内 appendLog 看不到。此夹具在体内放真实审计。
  {
    const src = [
      '@RestController',
      'public class SynthPathAuditController {',
      '    @PostMapping("/{id}/retry") public ApiResponse<Void> retry() {',
      '        auditService.appendLog(1L, "OPS_EXCEPTION_RETRY", "OPS_EXCEPTION", "1", "ok");',
      '        return ApiResponse.ok();',
      '    }',
      '}'
    ].join('\n');
    const { cls, endpoints } = controllerEndpoints(src);
    assert(cls === 'SynthPathAuditController', `路径占位符夹具类名错：${cls}`);
    assert(endpoints.length === 1, `路径占位符夹具端点数错：${endpoints.length}`);
    assert(endpoints[0].attributed, '路径占位符 {id} 不得截断方法体，体内 appendLog 必须被识别');
  }

  // C7) provider 访问器链（2026-11-16 S9）：support.xService().method() 必须穿透。
  //     旧解析器只按字段表找 support.xService，找不到方法名 ⇒ 端点误判未归因；
  //     这里用真实 MerchantPortalControllerSupport.disputeService() -> DisputeService.replyAsMerchant
  //     （服务层成功路径有 appendLog）钉住。
  {
    const src = [
      '@RestController',
      'public class SynthProviderController {',
      '    private final MerchantPortalControllerSupport support;',
      '    @PostMapping("/x")',
      '    public void x() { support.disputeService().replyAsMerchant(1L, 2L); }',
      '}'
    ].join('\n');
    const { endpoints } = controllerEndpoints(src);
    assert(endpoints.length === 1, `provider 夹具端点数错：${endpoints.length}`);
    assert(
      endpoints[0].attributed,
      'provider 访问器链 support.disputeService().replyAsMerchant 必须归因'
    );
  }

  // C8) 显式人工归因（2026-11-16 S9）：两条 OpsRbac 锁 lambda/重载盲区的业务审计已人工复核，
  //     名单必须精确命中这两个端点，不能成为泛化豁免。
  {
    const src = readFileSync(
      resolve(
        ROOT,
        'services/trade-service/src/main/java/com/aicabinet/trade/api/OpsRbacController.java'
      ),
      'utf8'
    );
    const { endpoints } = controllerEndpoints(src);
    for (const n of ['assignRoles', 'assignMerchants']) {
      const ep = endpoints.find((e) => e.name === n);
      assert(ep && ep.attributed, `显式人工归因端点 ${n} 必须归因`);
    }
  }

  // C5) 真实仓面全量不变量：写注解数 == 写端点数（每个控制器逐个对账）。
  //     这是 C06 这类「解析器盲区」的类级判据——只要有一个签名解析不出来就红，
  //     不依赖基线（基线是解析器的输出，自己产自己验会自证）。
  {
    const WRITE = /@(?:Post|Put|Patch|Delete)Mapping/g;
    const files = [
      'trade-service',
      'device-service',
      'common/common-core',
      'jiangyi-gateway'
    ].flatMap((s) => walkJava(resolve(ROOT, 'services', s, 'src/main/java')));
    let anno = 0;
    let parsed = 0;
    const bad = [];
    for (const f of files) {
      const src = readFileSync(f, 'utf8');
      const n = (src.match(WRITE) || []).length;
      if (n === 0) continue;
      anno += n;
      const got = controllerEndpoints(src).endpoints.length;
      parsed += got;
      if (got !== n) bad.push(`${f.split(/[\\/]/).pop()} ${n}/${got}`);
    }
    assert(anno >= 470, `写注解总数异常偏低（扫描面破了）：${anno}`);
    assert(bad.length === 0, `写注解数与解析端点数不等（解析盲区）：${bad.join(' | ')}`);
    assert(parsed === anno, `端点解析 ${parsed} ≠ 注解 ${anno}`);
  }
}

console.log(`${TAG} OK：${checks} 项断言全过`);
