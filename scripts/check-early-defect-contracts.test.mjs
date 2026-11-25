import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { FILES, findEarlyDefectViolations } from './check-early-defect-contracts.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sources = Object.fromEntries(
  Object.entries(FILES).map(([key, rel]) => [key, fs.readFileSync(path.join(root, rel), 'utf8')])
);
assert.deepEqual(findEarlyDefectViolations(sources), [], '真实源码必须满足全部早期缺陷契约');

const cases = [
  [
    'MA-423',
    'consent',
    (s) =>
      s.replace(
        'self.recordPrivacyConsentInNewTransaction(userId)',
        'recordPrivacyConsentInNewTransaction(userId)'
      )
  ],
  [
    'MA-424',
    'fundBill',
    (s) =>
      s.replace(
        'return WeChatFundBillCsvParser.parse("", date);',
        'throw new IllegalStateException();'
      )
  ],
  [
    'MA-424',
    'scheduler',
    (s) => s.replace('weChatRefundReconciler.advanceProcessingRefunds()', '0')
  ],
  [
    'MA-425',
    'merchantConfig',
    (s) =>
      s.replace(
        'const cache = ref<Record<string, string> | null>(null);',
        'let cache: Record<string, string> | null = null;'
      )
  ],
  [
    'MA-426',
    'refund',
    (s) => s.replace('findByUserIdAndIdemKey(userId, idemKey)', 'noReplayLookup')
  ],
  [
    'MA-427',
    'adminCss',
    (s) => s.replace(/(\.crud-table__table\s*\{[^}]*?)scrollbar-gutter: stable;/, '$1')
  ],
  ['MA-433', 'nginx', (s) => s.replace('real_ip_header X-Forwarded-For;', '')],
  ['MA-437', 'ha', (s) => s.replace('-f infra/docker-compose.prod-managed.yml', '')],
  ['MA-442', 'flowService', (s) => s.replace('countByFlowTypeAndRefId(flowType, refId)', '0')],
  ['MA-442', 'flowMigration', (s) => s.replace('uq_platform_fund_payout_ref', 'no_unique_index')],
  [
    'MA-443',
    'payscore',
    (s) => s.replace('isRuntimeChannelReady(channel) ? channel : PayChannels.BALANCE', 'channel')
  ],
  ['MA-444', 'cors', (s) => s.replace('"https".equalsIgnoreCase(uri.getScheme())', 'true')],
  ['MA-445', 'official', (s) => s.replaceAll('maskOpenId(openId)', 'openId')]
];
for (const [id, key, mutate] of cases) {
  const changed = { ...sources, [key]: mutate(sources[key]) };
  assert.ok(
    findEarlyDefectViolations(changed).some((problem) => problem.startsWith(id)),
    `${id} 负向夹具必须报错`
  );
}
console.log(`check-early-defect-contracts.test: 1 正向 + ${cases.length} 负向通过`);
