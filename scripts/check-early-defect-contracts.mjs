#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { maskJavaComments } from './check-scheduled-task-watched.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TRADE = 'services/trade-service/src/main/java/com/aicabinet/trade';
export const FILES = {
  consent: `${TRADE}/service/UserConsentLogService.java`,
  refund: `${TRADE}/service/BalanceRefundService.java`,
  fundBill: `${TRADE}/reconciliation/WeChatFundBillService.java`,
  scheduler: `${TRADE}/service/ReconciliationScheduler.java`,
  cors: `${TRADE}/config/ProductionStartupValidator.java`,
  payscore: `${TRADE}/service/PayScoreService.java`,
  orderPayment: `${TRADE}/service/OrderPaymentService.java`,
  official: `${TRADE}/wechat/WeChatOfficialAccountClient.java`,
  flowService: `${TRADE}/service/PlatformFundFlowService.java`,
  flowMigration:
    'services/trade-service/src/main/resources/db/migration/V509__platform_fund_flow_idempotency.sql',
  nginx: 'infra/gateway/nginx-full.conf',
  ha: 'infra/docker-compose.ha.yml',
  merchantConfig: 'clients/merchant-mp/src/utils/merchant-config.ts',
  adminCss: 'clients/admin-vue/src/styles/main.css'
};

function requireAll(problems, id, source, literals) {
  for (const literal of literals) {
    if (!source.includes(literal)) problems.push(`${id}: 缺少 ${literal}`);
  }
}

export function findEarlyDefectViolations(input) {
  const sources = { ...input };
  for (const key of [
    'consent',
    'refund',
    'scheduler',
    'cors',
    'payscore',
    'official',
    'flowService'
  ]) {
    sources[key] = maskJavaComments(sources[key] || '');
  }
  const problems = [];
  for (const [key, rel] of Object.entries(FILES)) {
    if (!(key in sources)) problems.push(`${rel}: 未读取`);
  }

  requireAll(problems, 'MA-423', sources.consent || '', [
    'self.recordPrivacyConsentInNewTransaction(userId)',
    '@Transactional(propagation = Propagation.REQUIRES_NEW)',
    'recordPrivacyConsentInNewTransaction'
  ]);
  const replayAt = (sources.refund || '').indexOf('findByUserIdAndIdemKey(userId, idemKey)');
  const limitAt = (sources.refund || '').indexOf('getBalanceRefundMinCents()');
  if (replayAt < 0 || limitAt < 0 || replayAt >= limitAt) {
    problems.push('MA-426: 幂等重放必须先于动态限额');
  }

  requireAll(problems, 'MA-424', sources.fundBill || '', [
    'return WeChatFundBillCsvParser.parse("", date);'
  ]);
  const failedAt = (sources.scheduler || '').indexOf('scheduled reconciliation failed');
  const advanceAt = (sources.scheduler || '').indexOf(
    'weChatRefundReconciler.advanceProcessingRefunds()'
  );
  if (failedAt < 0 || advanceAt < 0 || failedAt >= advanceAt) {
    problems.push('MA-424: 对账失败后仍必须独立推进退款查单');
  }

  requireAll(problems, 'MA-433', sources.nginx || '', [
    'set_real_ip_from 127.0.0.1;',
    'real_ip_header X-Forwarded-For;',
    'real_ip_recursive on;'
  ]);
  requireAll(problems, 'MA-437', sources.ha || '', [
    '--env-file infra/.env.production',
    '-f infra/docker-compose.production.yml',
    '-f infra/docker-compose.prod-managed.yml'
  ]);

  requireAll(problems, 'MA-442', sources.flowService || '', [
    'findByIdForUpdate(accountId)',
    'countByFlowTypeAndRefId(flowType, refId)',
    'countByRefTypeAndRefId(refType, refId)'
  ]);
  requireAll(problems, 'MA-442/数据库约束', sources.flowMigration || '', [
    'uq_platform_fund_payout_ref',
    'uq_platform_fund_bill_ref',
    "WHERE flow_type = 'PAYOUT'",
    "WHERE ref_type = 'WECHAT_FUND_BILL'"
  ]);

  requireAll(problems, 'MA-443', sources.payscore || '', [
    'isRuntimeChannelReady(channel)',
    'return isRuntimeChannelReady(channel) ? channel : PayChannels.BALANCE;'
  ]);
  requireAll(problems, 'MA-443/调用侧', sources.orderPayment || '', [
    'payScoreService.resolveChargeChannel(user, entryChannel)'
  ]);

  requireAll(problems, 'MA-444', sources.cors || '', [
    '"https".equalsIgnoreCase(uri.getScheme())',
    'uri.getHost() == null',
    'uri.getRawPath()'
  ]);
  requireAll(problems, 'MA-445', sources.official || '', [
    'maskOpenId(openId)',
    'errcode=',
    'requestId='
  ]);
  if ((sources.official || '').includes('body={}')) {
    problems.push('MA-445: 不得把微信上游响应体写入日志');
  }

  requireAll(problems, 'MA-425', sources.merchantConfig || '', [
    "import { ref } from 'vue';",
    'const cache = ref<Record<string, string> | null>(null);',
    'cache.value = cfg || {};'
  ]);
  const crudTableRule = /\.crud-table__table\s*\{([^}]*)\}/.exec(sources.adminCss || '')?.[1] || '';
  if (!crudTableRule.includes('scrollbar-gutter: stable;')) {
    problems.push('MA-427: .crud-table__table 必须使用 scrollbar-gutter: stable');
  }
  return problems;
}

function main() {
  const sources = {};
  for (const [key, rel] of Object.entries(FILES)) {
    const full = path.join(ROOT, rel);
    if (!fs.existsSync(full)) {
      console.error(`[check-early-defect-contracts] FAIL: 缺少 ${rel}`);
      process.exit(1);
    }
    sources[key] = fs.readFileSync(full, 'utf8');
  }
  const problems = findEarlyDefectViolations(sources);
  if (problems.length) {
    for (const problem of problems)
      console.error(`[check-early-defect-contracts] FAIL: ${problem}`);
    process.exit(1);
  }
  console.log(
    '[check-early-defect-contracts] OK: MA-423/424/425/426/427/433/437/442/443/444/445 契约在位'
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
