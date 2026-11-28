#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { FILES, notificationReliabilityProblems } from './check-notification-reliability.mjs';

const TAG = '[check-notification-reliability.test]';
let checks = 0;
const assert = (condition, message) => {
  checks++;
  if (!condition) throw new Error(`${TAG} 断言失败：${message}`);
};
const md5 = (value) => createHash('md5').update(value).digest('hex');
const real = Object.fromEntries(
  Object.entries(FILES).map(([key, file]) => [key, readFileSync(file, 'utf8')])
);

assert(notificationReliabilityProblems(real).length === 0, '真实仓面必须绿');
assert(
  FILES.migration.endsWith('V521__notification_inapp_semantic_idempotency.sql'),
  '语义幂等门禁必须指向顺延后的 V521'
);
assert(
  notificationReliabilityProblems({
    ...real,
    payment: real.payment.replace(/\r\n/g, '\n'),
    orderPayment: real.orderPayment.replace(/\r\n/g, '\n')
  }).length === 0,
  'CRLF 工作树不得误报'
);

function inject(patch, label, fragment) {
  const problems = notificationReliabilityProblems({ ...real, ...patch });
  assert(problems.length > 0, `${label}：应判红但判绿了`);
  assert(
    problems.some((p) => p.includes(fragment)),
    `${label}：红的原因不对：${problems.join(';')}`
  );
}

inject(
  {
    payment: real.payment.replace('opsAlertDispatcher.sendAfterCommit(', 'opsAlertDispatcher.send(')
  },
  '事务内告警直调复活',
  '事务内 opsAlertDispatcher.send'
);
inject(
  {
    notification: real.notification.replace(
      'logRepository.insertIgnore(logEntry);',
      'logRepository.save(logEntry);'
    )
  },
  '站内信语义去重消失',
  '站内信未使用稳定语义键'
);
inject(
  {
    outbox: real.outbox.replace(
      'NotificationDispatchOutbox current = outboxRepository.findById(id);',
      'NotificationDispatchOutbox current = row;'
    )
  },
  'outbox 锁内不重读',
  '拿锁后未重读状态'
);
inject(
  {
    migration: real.migration.replace(
      'uk_notification_log_inapp_semantic',
      'idx_notification_log_inapp_semantic'
    )
  },
  '唯一索引退化成普通索引',
  '语义唯一索引缺失'
);
inject(
  {
    producer: real.producer.replace(
      'future.get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);',
      'future.whenComplete((result, error) -> {});'
    )
  },
  'Kafka 生产端丢弃 ACK',
  '未等待 Broker ACK'
);
inject(
  {
    listener: real.listener.replaceAll("'|DONE'", "'DONE_LEGACY'")
  },
  'Kafka 去重槽退回单态键',
  'PROCESSING/DONE 状态机'
);

for (const [key, before] of Object.entries(real)) {
  assert(md5(readFileSync(FILES[key], 'utf8')) === md5(before), `${FILES[key]} 真树字节被污染`);
}
console.log(`${TAG} OK：${checks} 项断言全过`);
