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
  backup: 'scripts/backup-database.sh',
  deploy: 'scripts/deploy-prod.sh',
  lb: 'infra/gateway/trade-lb.cfg',
  nginxFull: 'infra/gateway/nginx-full.conf',
  nginxCompose: 'infra/gateway/nginx.compose.conf',
  apps: 'infra/docker-compose.apps.yml',
  full: 'infra/docker-compose.full.yml'
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

  requireAll(problems, 'MA-430/异地钩子', sources.backup || '', [
    '/bin/bash "$BACKUP_REMOTE_CMD" "$FINAL_FILE"',
    'BACKUP_ALERT_CMD',
    '.last-success-epoch'
  ]);
  requireAll(problems, 'MA-430/部署权限', sources.deploy || '', [
    'chmod +x scripts/backup-database.sh scripts/backup-offsite-oss.sh',
    'test -x scripts/backup-offsite-oss.sh'
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
  console.log('[check-operational-p0-p1-contracts] OK: MA-422/429/430/431/434/460 契约在位');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
