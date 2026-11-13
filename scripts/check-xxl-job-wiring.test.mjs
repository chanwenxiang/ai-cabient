#!/usr/bin/env node
/**
 * `check-xxl-job-wiring.mjs` 负向用例（S8③ 销账）：seed cron 日/周同时限定 ⇒ 红（规则 3.10）。
 *
 *   node scripts/check-xxl-job-wiring.test.mjs
 */
import { writeFileSync, readFileSync } from 'node:fs';
import { createSandbox, expectGreen, expectRed } from './lib/gate-sandbox.mjs';

const TAG = '[check-xxl-job-wiring.test]';
const GATE_INPUTS = [
  'XxlJobManagedTasks.java',
  'ScheduledTaskXxlJobHandler.java',
  'ScheduledTaskRegistry.java',
  'ScheduledTaskStaleMonitor.java',
  'SchedulingPoolCapacitySelfCheck.java',
  'XxlJobWiringSelfCheck.java',
  'ScheduleZones.java'
];
let checks = 0;
try {
  const sb = createSandbox();
  sb.copyByFilenames(GATE_INPUTS);
  sb.copyTree('infra', ['.yml', '.yaml', '.sql']);
  sb.copy('services/trade-service/src/main/resources/application.yml');
  await expectGreen('check-xxl-job-wiring.mjs', sb.root);
  checks++;

  // 注入：cron「日」与「周」同时限定（Quartz 直接拒绝 ⇒ job 永不触发，规则 3.10）
  const seed = sb.root + '/infra/xxl-job/seed_aicabinet_jobs.sql';
  let src = readFileSync(seed, 'utf8');
  if (!src.includes("'0 0/15 * * * ?'")) throw new Error('锚点缺失：0 0/15 * * * ?（快照过期）');
  src = src.replace("'0 0/15 * * * ?'", "'0 0/15 1 * 1 ?'");
  writeFileSync(seed, src);
  await expectRed('check-xxl-job-wiring.mjs', sb.root, '接线不一致');
  checks++;
} finally {
  /* 沙箱留给系统清理 */
}
console.log(`${TAG} OK：${checks} 项断言全过`);
