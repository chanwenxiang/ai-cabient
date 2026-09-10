#!/usr/bin/env node
/**
 * CI / 本地：校验 OpenAPI 生成类型已提交且订单读模型别名齐全。
 * 若提供 OPENAPI_FILE 或可访问的 OPENAPI_URL，则重新生成并要求 working tree 无 diff。
 *
 *   node scripts/check-openapi-types.mjs
 *   OPENAPI_FILE=.tmp/live-openapi.json node scripts/check-openapi-types.mjs
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const generatedDir = join(root, 'packages', 'shared-types', 'src', 'generated');
const openapiTs = join(generatedDir, 'openapi.ts');
const orderModels = join(generatedDir, 'order-models.ts');
const replenishmentModels = join(generatedDir, 'replenishment-models.ts');
const memberCouponModels = join(generatedDir, 'member-coupon-models.ts');
const notifyMarketingModels = join(generatedDir, 'notify-marketing-models.ts');
const merchantFinanceModels = join(generatedDir, 'merchant-finance-models.ts');
const merchantOpsModels = join(generatedDir, 'merchant-ops-models.ts');
const adminModels = join(generatedDir, 'admin-models.ts');
const indexTs = join(root, 'packages', 'shared-types', 'src', 'index.ts');

function fail(msg) {
  console.error(`[check-openapi-types] ${msg}`);
  process.exit(1);
}

for (const f of [
  openapiTs,
  orderModels,
  replenishmentModels,
  memberCouponModels,
  notifyMarketingModels,
  merchantFinanceModels,
  merchantOpsModels,
  adminModels,
  indexTs
]) {
  if (!existsSync(f)) fail(`missing ${f}`);
}

const orderModelsSrc = readFileSync(orderModels, 'utf8');
for (const name of [
  'OpenApiOrderReadModel',
  'OpenApiOrderReadModelAdmin',
  'OpenApiOrderReadModelMerchant',
  'OpenApiOrderReadModelConsumer',
  'OpenApiOrderLineDto'
]) {
  if (!orderModelsSrc.includes(name)) fail(`order-models.ts missing export ${name}`);
}

const replenishmentModelsSrc = readFileSync(replenishmentModels, 'utf8');
for (const name of [
  'OpenApiReplenishmentSuggestDto',
  'OpenApiMerchantReplenishmentEfficiencyDto',
  'OpenApiDeviceInventoryDto',
  'OpenApiSlotDiscrepancyAlertDto',
  'OpenApiSlotReplenishmentSuggestDto',
  'OpenApiMerchantReplenishmentRequestDto',
  'OpenApiMerchantReplenishmentRequestLineDto',
  'OpenApiCreateMerchantReplenishmentRequest',
  'OpenApiCreateMerchantReplenishmentRequestLine',
  'OpenApiReplenishmentTaskDto',
  'OpenApiReplenishmentTaskLineDto',
  'OpenApiSubmitReplenishmentLinesRequest',
  'OpenApiPullOffTaskDto',
  'OpenApiReplenishmentCheckInRequest',
  'OpenApiMerchantReplenishmentDeviceAccessDto',
  'OpenApiMerchantReplenishmentDoorSessionDto',
  'OpenApiSessionDto'
]) {
  if (!replenishmentModelsSrc.includes(name)) {
    fail(`replenishment-models.ts missing export ${name}`);
  }
}

const memberCouponModelsSrc = readFileSync(memberCouponModels, 'utf8');
for (const name of [
  'OpenApiMemberProfileDto',
  'OpenApiMemberLevelRuleDto',
  'OpenApiMemberPointsSummaryDto',
  'OpenApiMemberPointsLogDto',
  'OpenApiPointsRedeemItemDto',
  'OpenApiCouponDto'
]) {
  if (!memberCouponModelsSrc.includes(name)) {
    fail(`member-coupon-models.ts missing export ${name}`);
  }
}

const notifyMarketingModelsSrc = readFileSync(notifyMarketingModels, 'utf8');
for (const name of [
  'OpenApiNotificationDto',
  'OpenApiNotifyPrefDto',
  'OpenApiMerchantNotifyPrefDto',
  'OpenApiMarketingBannerDto',
  'OpenApiMarketingCampaignDto'
]) {
  if (!notifyMarketingModelsSrc.includes(name)) {
    fail(`notify-marketing-models.ts missing export ${name}`);
  }
}

const merchantFinanceModelsSrc = readFileSync(merchantFinanceModels, 'utf8');
for (const name of [
  'OpenApiMerchantWalletLedgerDto',
  'OpenApiMerchantWithdrawRequestDto',
  'OpenApiMerchantWalletOverviewDto',
  'OpenApiLineWalletLedgerDto',
  'OpenApiLineWithdrawRequestDto',
  'OpenApiLineWalletOverviewDto',
  'OpenApiMerchantDisputeSummaryDto',
  'OpenApiDisputeTicketDto',
  'OpenApiDisputeMessageDto',
  'OpenApiMerchantDisputeDetailDto'
]) {
  if (!merchantFinanceModelsSrc.includes(name)) {
    fail(`merchant-finance-models.ts missing export ${name}`);
  }
}

const merchantOpsModelsSrc = readFileSync(merchantOpsModels, 'utf8');
for (const name of [
  'OpenApiMerchantDeviceReportDto',
  'OpenApiUpdateMerchantProfileRequest',
  'OpenApiMerchantTrendDto',
  'OpenApiMerchantDailyTrendDto',
  'OpenApiMerchantDashboardStatsDto',
  'OpenApiOpsExceptionDto',
  'OpenApiSalesReportRowDto',
  'OpenApiMerchantDto',
  'OpenApiMerchantTaxProfileDto'
]) {
  if (!merchantOpsModelsSrc.includes(name)) {
    fail(`merchant-ops-models.ts missing export ${name}`);
  }
}

const adminModelsSrc = readFileSync(adminModels, 'utf8');
for (const name of [
  'OpenApiAdminDeviceDto',
  'OpenApiPageResultAdminDeviceDto',
  'OpenApiCouponDefinitionDto',
  'OpenApiPromotionActivityDto',
  'OpenApiPageResultPromotionActivityDto',
  'OpenApiAnnouncement',
  'OpenApiPageResultAnnouncement',
  'OpenApiPageResultOf'
]) {
  if (!adminModelsSrc.includes(name)) {
    fail(`admin-models.ts missing export ${name}`);
  }
}

const openapiSrc = readFileSync(openapiTs, 'utf8');
if (!openapiSrc.includes('OrderReadModel')) {
  fail('openapi.ts missing OrderReadModel schema');
}

const indexSrc = readFileSync(indexTs, 'utf8');
if (!indexSrc.includes('OpenApiOrderReadModelMerchant')) {
  fail('shared-types index.ts must re-export OpenApiOrderReadModelMerchant');
}
if (!indexSrc.includes('OpenApiMerchantWalletOverviewDto')) {
  fail('shared-types index.ts must re-export OpenApiMerchantWalletOverviewDto');
}
if (!indexSrc.includes('OpenApiMerchantDisputeSummaryDto')) {
  fail('shared-types index.ts must re-export OpenApiMerchantDisputeSummaryDto');
}
if (!indexSrc.includes('OpenApiMerchantDeviceReportDto')) {
  fail('shared-types index.ts must re-export OpenApiMerchantDeviceReportDto');
}
if (!indexSrc.includes('OpenApiMerchantReplenishmentRequestDto')) {
  fail('shared-types index.ts must re-export OpenApiMerchantReplenishmentRequestDto');
}
if (!indexSrc.includes('OpenApiAdminDeviceDto')) {
  fail('shared-types index.ts must re-export OpenApiAdminDeviceDto');
}

const openApiFile = process.env.OPENAPI_FILE
  ? resolve(root, process.env.OPENAPI_FILE)
  : join(root, '.tmp', 'live-openapi.json');
const shouldRegen =
  process.env.OPENAPI_CHECK_REGEN === '1' || process.env.OPENAPI_CHECK_REGEN === 'true';

if (shouldRegen) {
  console.log('[check-openapi-types] regenerating from OpenAPI…');
  const env = { ...process.env };
  if (existsSync(openApiFile)) {
    env.OPENAPI_FILE = openApiFile;
  }
  const gen = spawnSync('node', [join(root, 'scripts', 'gen-openapi-types.mjs')], {
    cwd: root,
    env,
    stdio: 'inherit',
    shell: true
  });
  if (gen.status !== 0) fail('gen:api-types failed');
  const diff = spawnSync(
    'git',
    ['diff', '--exit-code', '--', 'packages/shared-types/src/generated/'],
    { cwd: root, stdio: 'inherit', shell: true }
  );
  if (diff.status !== 0) {
    fail('generated OpenAPI types are stale; run pnpm gen:api-types and commit');
  }
} else {
  console.log('[check-openapi-types] structural OK (set OPENAPI_CHECK_REGEN=1 to regenerate+diff)');
}

console.log('[check-openapi-types] OK');
