#!/usr/bin/env node
/**
 * `check-replenishment-checkin-contract.mjs` 负向用例（S8③ 销账）：
 * isFulfillableStatus 退化成黑名单写法 ⇒ 红（R20 批A 规则 10 的缺陷本体）。
 *
 *   node scripts/check-replenishment-checkin-contract.test.mjs
 */
import { writeFileSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { createSandbox, expectGreen, expectRed } from './lib/gate-sandbox.mjs';

const TAG = '[check-replenishment-checkin-contract.test]';
/** 门禁读取的全部文件名（从其常量段抄录；路径由沙箱按名自动寻径） */
const GATE_INPUTS = [
  'ReplenishmentService.java',
  'ReplenishmentTimeoutScheduler.java',
  'SystemConfigService.java',
  'DeviceValidationService.java',
  'DeviceLocationSupport.java',
  'MerchantInventoryPortalService.java',
  'MerchantDevicePortalService.java',
  'MerchantWorkbenchQueryService.java',
  'OpsWorkbenchQueryService.java',
  'OpsDeviceAdminService.java',
  'MerchantReplenishmentService.java',
  'SettlementConfirmDisputeService.java',
  'DisputeService.java',
  'MerchantPrepPreviewGroupDto.java',
  'ReplenishmentTaskDto.java',
  'ReplenishmentTaskMapper.java',
  'ApiMessages.java',
  'e2e-replenishment.ps1',
  'e2e-checkin-contract.ps1',
  'e2e-lib.ps1',
  'prep-generate.vue',
  'prep-list.vue',
  'self-replenish-submit.ts'
];
let checks = 0;
try {
  const sb = createSandbox();
  sb.copyByFilenames(GATE_INPUTS);
  await expectGreen('check-replenishment-checkin-contract.mjs', sb.root);
  checks++;

  // 注入：白名单退化成「只排 COMPLETED」的黑名单（P2-1 缺陷本体，CANCELLED 复活）
  const svcFile = findCopied(sb.root, 'ReplenishmentService.java');
  let src = readFileSync(svcFile, 'utf8');
  const anchor =
    'return STATUS_PENDING.equals(status) || STATUS_IN_PROGRESS.equals(status) || PLANNED.equals(status);';
  if (!src.includes(anchor)) throw new Error('锚点缺失：isFulfillableStatus 白名单体（快照过期）');
  src = src.replace(anchor, 'return !STATUS_COMPLETED.equals(status);');
  writeFileSync(svcFile, src);
  await expectRed('check-replenishment-checkin-contract.mjs', sb.root, '黑名单写法');
  checks++;
} finally {
  /* 沙箱留给系统清理 */
}

/** 在沙箱里按文件名定位已复制的文件路径 */
function findCopied(root, name) {
  const walk = (dir) => {
    for (const e of readdirSync(dir)) {
      const p = join(dir, e);
      let st;
      try {
        st = statSync(p);
      } catch {
        continue;
      }
      if (st.isDirectory()) {
        const found = walk(p);
        if (found) return found;
      } else if (e === name) {
        return p;
      }
    }
    return null;
  };
  const found = walk(root);
  if (!found) throw new Error(`沙箱里找不到 ${name}`);
  return found;
}

console.log(`${TAG} OK：${checks} 项断言全过`);
