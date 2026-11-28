#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { maskJavaComments } from './check-scheduled-task-watched.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const FILES = {
  notificationMigration:
    'services/trade-service/src/main/resources/db/migration/V508__notification_log_failed_status.sql',
  sharedDict: 'packages/shared-dict/src/index.ts',
  sharedDictDist: 'packages/shared-dict/dist/index.js',
  bootstrap:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/SysDictBootstrap.java',
  scheduled:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/ScheduledTaskService.java',
  alerts: 'infra/prometheus/alert_rules.yml',
  prometheus: 'infra/monitoring/prometheus.compose.yml',
  backup: 'scripts/backup-database.sh',
  deploy: 'scripts/deploy-prod.sh',
  lb: 'infra/gateway/trade-lb.cfg',
  nginxFull: 'infra/gateway/nginx-full.conf',
  nginxCompose: 'infra/gateway/nginx.compose.conf',
  apps: 'infra/docker-compose.apps.yml',
  full: 'infra/docker-compose.full.yml',
  paySupplierRequest:
    'services/common/common-core/src/main/java/com/aicabinet/common/dto/PaySupplierRequest.java',
  supplierPayable: 'services/trade-service/src/main/java/com/aicabinet/trade/service/SupplierPayableService.java',
  supplierPaymentMapper:
    'services/trade-service/src/main/java/com/aicabinet/trade/mapper/SupplierPaymentMapper.java',
  warehouseDialogs: 'clients/admin-vue/src/composables/warehouse/useWarehouseEntityDialogs.ts',
  inventoryLot: 'services/trade-service/src/main/java/com/aicabinet/trade/service/InventoryLotService.java',
  lotMapper: 'services/trade-service/src/main/java/com/aicabinet/trade/mapper/DeviceSkuLotMapper.java',
  dataConsistency: 'services/trade-service/src/main/java/com/aicabinet/trade/service/DataConsistencyService.java'
};

function requireAll(problems, id, source, literals) {
  for (const literal of literals) {
    if (!source.includes(literal)) problems.push(`${id}: 缺少 ${literal}`);
  }
}

export function findOperationalContractViolations(input) {
  const sources = { ...input };
  sources.bootstrap = maskJavaComments(sources.bootstrap || '');
  sources.scheduled = maskJavaComments(sources.scheduled || '');
  sources.paySupplierRequest = maskJavaComments(sources.paySupplierRequest || '');
  sources.supplierPayable = maskJavaComments(sources.supplierPayable || '');
  sources.supplierPaymentMapper = maskJavaComments(sources.supplierPaymentMapper || '');
  sources.inventoryLot = maskJavaComments(sources.inventoryLot || '');
  sources.lotMapper = maskJavaComments(sources.lotMapper || '');
  sources.dataConsistency = maskJavaComments(sources.dataConsistency || '');
  const problems = [];
  for (const [key, rel] of Object.entries(FILES)) {
    if (!(key in sources)) problems.push(`${rel}: 未读取`);
  }

  requireAll(problems, 'MA-422/迁移', sources.notificationMigration || '', [
    'DROP CONSTRAINT IF EXISTS chk_notification_log_status',
    "CHECK (status IN ('SENT', 'FAILED'))"
  ]);
  requireAll(problems, 'MA-422/字典', sources.sharedDict || '', ["FAILED: '投递失败'"]);
  requireAll(problems, 'MA-422/产物', sources.sharedDictDist || '', ["FAILED: '投递失败'"]);

  const lockAt = (sources.bootstrap || '').indexOf('pg_advisory_xact_lock');
  const countAt = (sources.bootstrap || '').indexOf('typeRepository.count()');
  if (lockAt < 0 || countAt < 0 || lockAt >= countAt) {
    problems.push('MA-460: 数据库 advisory lock 必须在空集判断之前');
  }

  requireAll(problems, 'MA-431/失败计数', sources.scheduled || '', [
    'aicabinet.scheduled.task.failures',
    'meterRegistry.counter(FAILURE_COUNTER, "task_key", taskKey).increment()'
  ]);
  requireAll(problems, 'MA-431/告警消费', sources.alerts || '', [
    'alert: ScheduledTaskRecordedFailure',
    'aicabinet_scheduled_task_failures_total'
  ]);

  const tradeScrape =
    (sources.prometheus || '').split('- job_name: trade-service')[1]?.split('- job_name:')[0] || '';
  requireAll(problems, 'MA-465/多副本抓取', tradeScrape, [
    'dns_sd_configs:',
    'names: ["trade-service"]',
    'type: A',
    'port: 8080'
  ]);
  if (tradeScrape.includes('static_configs:')) {
    problems.push('MA-465/多副本抓取: trade-service 不得使用单服务名 static_configs');
  }

  requireAll(problems, 'MA-430/异地钩子', sources.backup || '', [
    '/bin/bash "$BACKUP_REMOTE_CMD" "$FINAL_FILE"',
    'BACKUP_ALERT_CMD',
    '.last-success-epoch'
  ]);
  requireAll(problems, 'MA-430/部署权限', sources.deploy || '', [
    'chmod +x scripts/backup-database.sh scripts/backup-offsite-oss.sh',
    'test -x scripts/backup-offsite-oss.sh'
  ]);

  requireAll(problems, 'MA-464/监控挂载刷新', sources.deploy || '', [
    'force-recreate prometheus alertmanager',
    'wait_monitoring',
    'ScheduledTaskRecordedFailure 已加载'
  ]);

  requireAll(problems, 'MA-429/镜像与副本', sources.deploy || '', [
    'export IMAGE_TAG="$SHA"',
    'build trade-service device-service jiangyi-gateway',
    'wait_healthy trade-service "$TRADE_REPLICAS"',
    'wait_healthy device-service "$DEVICE_REPLICAS"',
    'docker image inspect "$image"'
  ]);

  requireAll(problems, 'MA-434/主动探测', sources.lb || '', [
    'option httpchk',
    'uri /actuator/health',
    'server-template trade 4 trade-service:8080',
    'resolvers docker',
    'inter 500ms fall 1 rise 1',
    'retries 0'
  ]);
  for (const key of ['nginxFull', 'nginxCompose']) {
    requireAll(problems, `MA-434/${key}`, sources[key] || '', [
      'set $trade_upstream trade-lb:8080'
    ]);
    if ((sources[key] || '').includes('set $trade_upstream trade-service:8080')) {
      problems.push(`MA-434/${key}: 不得绕过 trade-lb 直连副本`);
    }
  }
  for (const key of ['apps', 'full']) {
    requireAll(problems, `MA-434/${key}`, sources[key] || '', [
      'trade-lb:',
      'condition: service_healthy'
    ]);
  }

  requireAll(problems, 'MA-475/请求契约', sources.paySupplierRequest || '', [
    '@NotBlank @Size(max = 64) String idempotencyKey'
  ]);
  requireAll(problems, 'MA-475/前端稳定键', sources.warehouseDialogs || '', [
    'paymentIdempotencyKey.value = `supplier-pay-${uuid}`',
    'idempotencyKey: paymentIdempotencyKey.value'
  ]);
  requireAll(problems, 'MA-475/服务端幂等', sources.supplierPayable || '', [
    'requireIdempotencyKey(request.idempotencyKey())',
    'paymentRepository.insertIfAbsent(payment)',
    'return replayPayment(raced, payableId, request.amountCents())',
    '付款幂等键已用于不同应付单或金额'
  ]);
  requireAll(problems, 'MA-475/并发冲突不抛异常', sources.supplierPaymentMapper || '', [
    'ON CONFLICT (idempotency_key)',
    'DO NOTHING'
  ]);

  requireAll(problems, 'MA-476/已付退货拒绝', sources.supplierPayable || '', [
    'payable.getPaidAmountCents() > 0',
    '请先走供应商退款或人工资金处理'
  ]);

  requireAll(problems, 'MA-478/稳定游标', sources.lotMapper || '', [
    'LocalDate afterExpiryDate',
    'String afterLotId',
    'DeviceSkuLot::getLotId',
    '.gt(DeviceSkuLot::getLotId, afterLotId)'
  ]);
  requireAll(problems, 'MA-478/连续追平', sources.inventoryLot || '', [
    'while (true)',
    'cursorExpiry = last.getExpiryDate()',
    'cursorLotId = last.getLotId()'
  ]);

  requireAll(problems, 'MA-480/设备锁', sources.dataConsistency || '', [
    'InventoryService.deviceLockKey(deviceId)',
    'distributedLockService.tryLock(key, 30, 5)',
    'afterCompletion(int status)'
  ]);
  requireAll(problems, 'MA-480/锁内新鲜值', sources.dataConsistency || '', [
    'currentLotQty != parseRecordCents(consistencyRecord.getActualValue())',
    'lotQty != parseRecordCents(consistencyRecord.getActualValue())',
    '巡检后在架批次合计已变化，请重新巡检后再修复'
  ]);
  return problems;
}

function main() {
  const sources = {};
  for (const [key, rel] of Object.entries(FILES)) {
    const full = path.join(ROOT, rel);
    if (!fs.existsSync(full)) {
      console.error(`[check-operational-p0-p1-contracts] FAIL: 缺少 ${rel}`);
      process.exit(1);
    }
    sources[key] = fs.readFileSync(full, 'utf8');
  }
  const problems = findOperationalContractViolations(sources);
  if (problems.length) {
    for (const problem of problems) {
      console.error(`[check-operational-p0-p1-contracts] FAIL: ${problem}`);
    }
    process.exit(1);
  }
  console.log(
    '[check-operational-p0-p1-contracts] OK: MA-422/429/430/431/434/460/464/465/475/476/478/480 契约在位'
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
