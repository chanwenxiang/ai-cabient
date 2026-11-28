#!/usr/bin/env node
/** 通知可靠性：事务边界、语义幂等与渠道重试不得退化。 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const JAVA = join(ROOT, 'services/trade-service/src/main/java/com/aicabinet/trade');
export const FILES = {
  dispatcher: join(JAVA, 'service/OpsAlertDispatcher.java'),
  payment: join(JAVA, 'service/PaymentService.java'),
  orderPayment: join(JAVA, 'service/OrderPaymentService.java'),
  notification: join(JAVA, 'service/NotificationService.java'),
  outbox: join(JAVA, 'service/NotificationDispatchOutboxService.java'),
  producer: join(JAVA, 'messaging/NotificationDispatchProducer.java'),
  listener: join(JAVA, 'messaging/NotificationDispatchListener.java'),
  migration: join(
    ROOT,
    'services/trade-service/src/main/resources/db/migration/V521__notification_inapp_semantic_idempotency.sql'
  )
};

export function maskComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/^(\s*)\/\/[^\n]*$/gm, (m, lead) => lead + ' '.repeat(m.length - lead.length));
}

function methodBody(source, signature) {
  const start = source.indexOf(signature);
  if (start < 0) return null;
  const rel = source.slice(start + 1).search(/\n {4}(?=[^\s}])/);
  return source.slice(start, rel < 0 ? source.length : start + 1 + rel);
}

export function notificationReliabilityProblems(src) {
  const dispatcher = maskComments(src.dispatcher.replace(/\r\n/g, '\n'));
  const payment = maskComments(src.payment.replace(/\r\n/g, '\n'));
  const orderPayment = maskComments(src.orderPayment.replace(/\r\n/g, '\n'));
  const notification = maskComments(src.notification.replace(/\r\n/g, '\n'));
  const outbox = maskComments(src.outbox.replace(/\r\n/g, '\n'));
  const producer = maskComments(src.producer.replace(/\r\n/g, '\n'));
  const listener = maskComments(src.listener.replace(/\r\n/g, '\n'));
  const migration = maskComments(src.migration.replace(/\r\n/g, '\n'));
  const problems = [];

  const alertBody = methodBody(
    dispatcher,
    'public void sendAfterCommit(String type, String title, String message,\n                                Map<String, Object> extra, String... extraUrls)'
  );
  if (alertBody === null) {
    problems.push('OpsAlertDispatcher 缺 sendAfterCommit：业务事务仍可能同步外呼');
  } else if (
    !alertBody.includes('registerSynchronization') ||
    !alertBody.includes('afterCommit()')
  ) {
    problems.push('sendAfterCommit 未注册 afterCommit：回滚前会误发告警');
  } else if (!/catch\s*\(RuntimeException/.test(alertBody)) {
    problems.push('sendAfterCommit 回调异常未隔离：已提交业务可能被报成失败');
  }

  for (const [name, source] of [
    ['PaymentService', payment],
    ['OrderPaymentService', orderPayment]
  ]) {
    if (/opsAlertDispatcher\s*\.\s*send\s*\(/.test(source)) {
      problems.push(`${name} 仍有事务内 opsAlertDispatcher.send 直调`);
    }
  }

  if (
    !notification.includes('"inapp:" + clientMsgId') ||
    !notification.includes('logRepository.insertIgnore')
  ) {
    problems.push('站内信未使用稳定语义键 + INSERT ON CONFLICT：重放会重复落库');
  }
  if (!outbox.includes('message.bizId() == null || message.bizId().isBlank()')) {
    problems.push('outbox 幂等键未按业务事件收敛：文案变化会生成新键');
  }
  const dispatchBody = methodBody(outbox, 'private boolean dispatchRow(');
  if (dispatchBody === null) {
    problems.push('outbox dispatchRow 方法缺失，锁内重读判据无法验证');
  } else if (
    !dispatchBody.includes('outboxRepository.findById(id)') ||
    !dispatchBody.includes('"SENT".equals(current.getStatus())')
  ) {
    problems.push('outbox 拿锁后未重读状态：并发回调可能双发');
  }
  if (
    !migration.includes('uk_notification_log_inapp_semantic') ||
    !migration.includes("client_msg_id LIKE 'inapp:%'")
  ) {
    problems.push('V521 站内信语义唯一索引缺失');
  }
  if (!producer.includes('future.get(ackTimeout.toMillis()')) {
    problems.push('Kafka 生产端未等待 Broker ACK：异步失败仍可能假成功');
  }
  if (
    !listener.includes("'|PROCESSING'") ||
    !listener.includes("'|DONE'") ||
    !listener.includes("ARGV[1] .. '|PROCESSING'") ||
    !listener.includes('return 2')
  ) {
    problems.push('Kafka 去重槽缺 owner PROCESSING/DONE 状态机：崩溃或释放失败会永久抑制重投');
  }
  return problems;
}

export function run() {
  const source = Object.fromEntries(
    Object.entries(FILES).map(([key, file]) => [key, readFileSync(file, 'utf8')])
  );
  const problems = notificationReliabilityProblems(source);
  if (problems.length) {
    console.error('[check-notification-reliability] FAIL:\n' + problems.join('\n'));
    process.exit(1);
  }
  console.log(
    '[check-notification-reliability] OK：告警提交后发、站内信语义幂等、outbox 锁内重读、Kafka ACK 与去重状态机均在位'
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  run();
}
