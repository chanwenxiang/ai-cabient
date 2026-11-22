#!/usr/bin/env node
/**
 * `check-jiangyi-receipt-offload.mjs` 的负向用例（S8④ 自证）。
 *
 * 真实树必须绿；四种退化各注入一次必须红；全程只操作内存字符串，不写盘（零污染）。
 *
 *   node scripts/check-jiangyi-receipt-offload.test.mjs
 */
import { readFileSync } from 'node:fs';
import {
  MAPPER_FILE,
  SERVICE_FILE,
  mapperProblems,
  receiptProblems,
  revokeProblems,
  schedulerProblems
} from './check-jiangyi-receipt-offload.mjs';

const TAG = '[check-jiangyi-receipt-offload.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const service = readFileSync(SERVICE_FILE, 'utf8');
const mapper = readFileSync(MAPPER_FILE, 'utf8');

// 1) 真实树：四条判据全绿
{
  assert(receiptProblems(service).length === 0, `真实树回执判据应绿：${receiptProblems(service)}`);
  assert(schedulerProblems(service).length === 0, `真实树调度判据应绿：${schedulerProblems(service)}`);
  assert(revokeProblems(service).length === 0, `真实树撤戳判据应绿：${revokeProblems(service)}`);
  assert(mapperProblems(mapper).length === 0, `真实树 mapper 判据应绿：${mapperProblems(mapper)}`);
}

// 2) 注入①：回执线程内直调 revokeVerificationIfModelDrifted ⇒ 红
{
  const injected = service.replace(
    'scheduleDriftCheck(deviceId, reported);',
    'revokeVerificationIfModelDrifted(deviceId, reported);'
  );
  assert(injected !== service, '注入①未生效（找不到 scheduleDriftCheck 调用点）');
  const p = receiptProblems(injected);
  assert(
    p.some((x) => x.includes('直接调 revokeVerificationIfModelDrifted')),
    `回执线程内同步拉 OSS 必须红：${p.join('|')}`
  );
}

// 3) 注入②：回执不调 scheduleDriftCheck（改成什么都不做）⇒ 红
{
  const injected = service.replace('scheduleDriftCheck(deviceId, reported);', 'return null;');
  const p = receiptProblems(injected);
  assert(
    p.some((x) => x.includes('未调 scheduleDriftCheck')),
    `漂移检测被摘掉必须红：${p.join('|')}`
  );
}

// 4) 注入③：调度方法改成同步执行（不经 executor）⇒ 红
{
  const injected = service.replace(
    'driftCheckExecutor.execute(() -> {',
    '((Runnable) () -> {'
  );
  const p = schedulerProblems(injected);
  assert(
    p.some((x) => x.includes('没有把检测交给 driftCheckExecutor')),
    `绕过 executor 必须红：${p.join('|')}`
  );
}

// 5) 注入④：去重键不在 finally 里摘 ⇒ 红（漏摘＝该柜机此后永不检测漂移）
{
  const injected = service.replace(
    /finally\s*\{\s*\/\/[^\n]*\n\s*driftChecksInFlight\.remove\(deviceId\);\s*\}/,
    '}'
  );
  const p = schedulerProblems(injected);
  assert(
    p.some((x) => x.includes('未在 finally 里摘掉同柜去重键')),
    `漏摘去重键必须红：${p.join('|')}`
  );
}

// 6) 注入⑤：漂移撤戳回到无条件 clearMappingVerification ⇒ 红
{
  const injected = service.replace(
    'clearMappingVerificationIfStill(deviceId, verifiedVersion, now)',
    'clearMappingVerification(deviceId, now)'
  );
  const p = revokeProblems(injected);
  assert(
    p.some((x) => x.includes('未用 clearMappingVerificationIfStill')),
    `漂移撤戳回到无条件清空必须红：${p.join('|')}`
  );
}

// 7) 注入⑥：mapper 条件更新丢掉快照态谓词 ⇒ 红
{
  const injected = mapper.replace(
    /\n\s*\.eq\(JiangyiDevice::getMappingVerifiedVersion, expectedVersion\)/,
    ''
  );
  assert(injected !== mapper, '注入⑥未生效（找不到谓词）');
  const p = mapperProblems(injected);
  assert(
    p.some((x) => x.includes('缺 eq(getMappingVerifiedVersion, expectedVersion)')),
    `条件更新丢谓词必须红：${p.join('|')}`
  );
}

// 8) 注入⑦：mapper 去掉空快照早退 ⇒ 红
{
  const injected = mapper.replace(
    /if \(expectedVersion == null \|\| expectedVersion\.isBlank\(\)\) \{[\s\S]*?\n {8}\}/,
    ''
  );
  assert(injected !== mapper, '注入⑦未生效（找不到早退块）');
  const p = mapperProblems(injected);
  assert(
    p.some((x) => x.includes('缺「空快照直接放弃」早退')),
    `空快照早退丢掉必须红：${p.join('|')}`
  );
}

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 + 7 注入红）`);
