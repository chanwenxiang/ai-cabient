import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { FILES, findOperationalContractViolations } from './check-operational-p0-p1-contracts.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sources = Object.fromEntries(
  Object.entries(FILES).map(([key, rel]) => [key, fs.readFileSync(path.join(root, rel), 'utf8')])
);
assert.deepEqual(findOperationalContractViolations(sources), [], '真实源码必须满足全部运维契约');

const cases = [
  ['MA-422', 'notificationMigration', (s) => s.replace("'SENT', 'FAILED'", "'SENT'")],
  ['MA-422', 'sharedDict', (s) => s.replace("FAILED: '投递失败'", '')],
  ['MA-460', 'bootstrap', (s) => s.replace('pg_advisory_xact_lock', 'pg_advisory_lock_noop')],
  [
    'MA-431',
    'scheduled',
    (s) => s.replace('meterRegistry.counter(FAILURE_COUNTER', 'noop.counter(FAILURE_COUNTER')
  ],
  ['MA-431', 'alerts', (s) => s.replace('aicabinet_scheduled_task_failures_total', 'no_metric')],
  [
    'MA-465',
    'prometheus',
    (s) =>
      s.replace(
        /dns_sd_configs:\r?\n\s+- names: \["trade-service"\]\r?\n\s+type: A\r?\n\s+port: 8080/,
        'static_configs:\n      - targets:\n          - trade-service:8080'
      )
  ],
  ['MA-430', 'backup', (s) => s.replace('/bin/bash "$BACKUP_REMOTE_CMD"', '"$BACKUP_REMOTE_CMD"')],
  ['MA-430', 'deploy', (s) => s.replace('test -x scripts/backup-offsite-oss.sh', 'true')],
  [
    'MA-464',
    'deploy',
    (s) => s.replace('force-recreate prometheus alertmanager', 'force-recreate trade-service')
  ],
  [
    'MA-429',
    'deploy',
    (s) => s.replace('build trade-service device-service jiangyi-gateway', 'build trade-service')
  ],
  ['MA-434', 'lb', (s) => s.replace('inter 500ms fall 1 rise 1', 'inter 5s fall 3 rise 1')],
  [
    'MA-434',
    'nginxFull',
    (s) => s.replace('set $trade_upstream trade-lb:8080', 'set $trade_upstream trade-service:8080')
  ],
  ['MA-434', 'apps', (s) => s.replaceAll('trade-lb:', 'trade-lb-disabled:')],
  [
    'MA-475',
    'paySupplierRequest',
    (s) => s.replace('@NotBlank @Size(max = 64) String idempotencyKey', 'String idempotencyKey')
  ],
  [
    'MA-475',
    'warehouseDialogs',
    (s) => s.replace('idempotencyKey: paymentIdempotencyKey.value', "idempotencyKey: ''")
  ],
  [
    'MA-476',
    'supplierPayable',
    (s) => s.replaceAll('payable.getPaidAmountCents() > 0', 'false')
  ],
  [
    'MA-478',
    'lotMapper',
    (s) => s.replace('.gt(DeviceSkuLot::getLotId, afterLotId)', '.gt(DeviceSkuLot::getSkuId, afterLotId)')
  ],
  [
    'MA-480',
    'dataConsistency',
    (s) => s.replace('currentLotQty != parseRecordCents(consistencyRecord.getActualValue())', 'false')
  ]
];
for (const [id, key, mutate] of cases) {
  const changed = { ...sources, [key]: mutate(sources[key]) };
  assert.ok(
    findOperationalContractViolations(changed).some((problem) => problem.startsWith(id)),
    `${id} 负向夹具必须报错`
  );
}
console.log(`check-operational-p0-p1-contracts.test: 1 正向 + ${cases.length} 负向通过`);
