/**
 * OpenAPI 前端别名组：单一数据源。
 * gen-openapi-types.mjs 生成 *.ts；check-openapi-types.mjs 校验导出齐全。
 * 增删契约别名只改本文件，避免脚本内双处硬编码漂移。
 *
 * @typedef {{ name: string, schema?: string, comment?: string, raw?: string }} AliasExport
 * @typedef {{ file: string, title: string, exports: AliasExport[], footer?: string }} AliasGroup
 */

/** @type {AliasGroup[]} */
export const OPENAPI_ALIAS_GROUPS = [
  {
    file: 'order-models.ts',
    title: '精简订单读模型别名，便于业务侧对照契约而不整包 import paths。',
    exports: [
      { name: 'OpenApiOrderReadModel', schema: 'OrderReadModel' },
      { name: 'OpenApiOrderReadModelAdmin', schema: 'OrderReadModel_Admin' },
      { name: 'OpenApiOrderReadModelMerchant', schema: 'OrderReadModel_Merchant' },
      { name: 'OpenApiOrderReadModelConsumer', schema: 'OrderReadModel_Consumer' },
      { name: 'OpenApiOrderLineDto', schema: 'OrderLineDto' }
    ]
  },
  {
    file: 'replenishment-models.ts',
    title: '补货/库存读模型别名（商户端本地投影迁出用）。',
    exports: [
      { name: 'OpenApiReplenishmentSuggestDto', schema: 'ReplenishmentSuggestDto', comment: '补货建议（商户/运营）' },
      {
        name: 'OpenApiMerchantReplenishmentEfficiencyDto',
        schema: 'MerchantReplenishmentEfficiencyDto',
        comment: '商户补货效率看板'
      },
      { name: 'OpenApiDeviceInventoryDto', schema: 'DeviceInventoryDto', comment: '设备库存行（含低库存列表）' },
      { name: 'OpenApiSlotDiscrepancyAlertDto', schema: 'SlotDiscrepancyAlertDto', comment: '货道账实差异告警' },
      {
        name: 'OpenApiSlotReplenishmentSuggestDto',
        schema: 'SlotReplenishmentSuggestDto',
        comment: '货道补货建议'
      },
      {
        name: 'OpenApiMerchantReplenishmentRequestDto',
        schema: 'MerchantReplenishmentRequestDto',
        comment: '商户补货申请'
      },
      {
        name: 'OpenApiMerchantReplenishmentRequestLineDto',
        schema: 'MerchantReplenishmentRequestLineDto',
        comment: '商户补货申请行'
      },
      {
        name: 'OpenApiCreateMerchantReplenishmentRequest',
        schema: 'CreateMerchantReplenishmentRequest',
        comment: '创建商户补货申请'
      },
      {
        name: 'OpenApiCreateMerchantReplenishmentRequestLine',
        schema: 'CreateMerchantReplenishmentRequestLine',
        comment: '创建商户补货申请行（独立 schema，避免与仓配 Line 冲突）'
      },
      { name: 'OpenApiReplenishmentTaskDto', schema: 'ReplenishmentTaskDto', comment: '补货任务' },
      {
        name: 'OpenApiReplenishmentTaskLineDto',
        schema: 'ReplenishmentTaskLineDto',
        comment: '补货任务明细行'
      },
      {
        name: 'OpenApiSubmitReplenishmentLinesRequest',
        schema: 'SubmitReplenishmentLinesRequest',
        comment: '提交补货明细'
      },
      { name: 'OpenApiPullOffTaskDto', schema: 'PullOffTaskDto', comment: '临期/下架任务（商户 expiry-alerts）' },
      {
        name: 'OpenApiReplenishmentCheckInRequest',
        schema: 'ReplenishmentCheckInRequest',
        comment: '补货签到请求'
      },
      {
        name: 'OpenApiMerchantReplenishmentDeviceAccessDto',
        schema: 'MerchantReplenishmentDeviceAccessDto',
        comment: '柜机 FIELD 管辖校验'
      },
      {
        name: 'OpenApiMerchantReplenishmentDoorSessionDto',
        schema: 'MerchantReplenishmentDoorSessionDto',
        comment: '补货开门会话状态'
      },
      { name: 'OpenApiSessionDto', schema: 'SessionDto', comment: '开门会话（补货开门等）' }
    ]
  },
  {
    file: 'member-coupon-models.ts',
    title: '会员/积分/优惠券读模型别名。',
    exports: [
      { name: 'OpenApiMemberProfileDto', schema: 'MemberProfileDto', comment: '消费者会员档案' },
      { name: 'OpenApiMemberLevelRuleDto', schema: 'MemberLevelRuleDto', comment: '会员等级规则行' },
      { name: 'OpenApiMemberPointsSummaryDto', schema: 'MemberPointsSummaryDto', comment: '积分汇总' },
      { name: 'OpenApiMemberPointsLogDto', schema: 'MemberPointsLogDto', comment: '积分流水' },
      { name: 'OpenApiPointsRedeemItemDto', schema: 'PointsRedeemItemDto', comment: '积分兑换货架项' },
      { name: 'OpenApiCouponDto', schema: 'CouponDto', comment: '消费者优惠券' }
    ]
  },
  {
    file: 'notify-marketing-models.ts',
    title: '通知/营销读模型别名。',
    exports: [
      { name: 'OpenApiNotificationDto', schema: 'NotificationDto', comment: '站内通知（消费者/商户共用结构）' },
      { name: 'OpenApiNotifyPrefDto', schema: 'NotifyPrefDto', comment: '消费者通知偏好' },
      { name: 'OpenApiMerchantNotifyPrefDto', schema: 'MerchantNotifyPrefDto', comment: '商户告警订阅偏好' },
      { name: 'OpenApiMarketingBannerDto', schema: 'MarketingBannerDto', comment: '营销 Banner' },
      { name: 'OpenApiMarketingCampaignDto', schema: 'MarketingCampaignDto', comment: '营销活动' }
    ]
  },
  {
    file: 'merchant-finance-models.ts',
    title: '商户钱包/提现/争议读模型别名。',
    exports: [
      { name: 'OpenApiMerchantWalletLedgerDto', schema: 'MerchantWalletLedgerDto', comment: '商户钱包流水' },
      {
        name: 'OpenApiMerchantWithdrawRequestDto',
        schema: 'MerchantWithdrawRequestDto',
        comment: '商户提现申请'
      },
      {
        name: 'OpenApiMerchantWalletOverviewDto',
        schema: 'MerchantWalletOverviewDto',
        comment: '商户钱包总览'
      },
      { name: 'OpenApiLineWalletLedgerDto', schema: 'LineWalletLedgerDto', comment: '线路经理钱包流水' },
      { name: 'OpenApiLineWithdrawRequestDto', schema: 'LineWithdrawRequestDto', comment: '线路经理提现申请' },
      { name: 'OpenApiLineWalletOverviewDto', schema: 'LineWalletOverviewDto', comment: '线路经理钱包总览' },
      {
        name: 'OpenApiMerchantDisputeSummaryDto',
        schema: 'MerchantDisputeSummaryDto',
        comment: '商户端争议列表摘要'
      },
      { name: 'OpenApiDisputeTicketDto', schema: 'DisputeTicketDto', comment: '争议工单（认领/详情 ticket）' },
      { name: 'OpenApiDisputeMessageDto', schema: 'DisputeMessageDto', comment: '争议消息' },
      {
        name: 'OpenApiMerchantDisputeDetailDto',
        schema: 'MerchantDisputeDetailDto',
        comment: '商户端争议详情'
      }
    ]
  },
  {
    file: 'merchant-ops-models.ts',
    title: '商户运营读模型别名（设备报表/资料/趋势/异常等）。',
    exports: [
      {
        name: 'OpenApiMerchantDeviceReportDto',
        schema: 'MerchantDeviceReportDto',
        comment: '商户设备经营报表行'
      },
      {
        name: 'OpenApiUpdateMerchantProfileRequest',
        schema: 'UpdateMerchantProfileRequest',
        comment: '更新商户资料'
      },
      { name: 'OpenApiMerchantTrendDto', schema: 'MerchantTrendDto', comment: '商户趋势' },
      { name: 'OpenApiMerchantDailyTrendDto', schema: 'MerchantDailyTrendDto', comment: '商户日趋势点' },
      {
        name: 'OpenApiMerchantDashboardStatsDto',
        schema: 'MerchantDashboardStatsDto',
        comment: '商户工作台统计'
      },
      { name: 'OpenApiOpsExceptionDto', schema: 'OpsExceptionDto', comment: '运营/商户异常工单' },
      { name: 'OpenApiSalesReportRowDto', schema: 'SalesReportRowDto', comment: '销售报表行（商户 analytics）' },
      { name: 'OpenApiMerchantDto', schema: 'MerchantDto', comment: '商户实体（资料更新等）' },
      { name: 'OpenApiMerchantTaxProfileDto', schema: 'MerchantTaxProfileDto', comment: '商户开票资料' }
    ]
  },
  {
    file: 'admin-models.ts',
    title: '运营后台读模型别名（设备/优惠券/营销/公告等）。',
    exports: [
      { name: 'OpenApiAdminDeviceDto', schema: 'AdminDeviceDto' },
      { name: 'OpenApiPageResultAdminDeviceDto', schema: 'PageResultAdminDeviceDto' },
      { name: 'OpenApiCouponDefinitionDto', schema: 'CouponDefinitionDto' },
      { name: 'OpenApiPromotionActivityDto', schema: 'PromotionActivityDto' },
      { name: 'OpenApiPageResultPromotionActivityDto', schema: 'PageResultPromotionActivityDto' },
      { name: 'OpenApiAnnouncement', schema: 'Announcement' },
      { name: 'OpenApiPageResultAnnouncement', schema: 'PageResultAnnouncement' },
      {
        name: 'OpenApiPageResultOf',
        raw:
          '/** 与 springdoc PageResult* 结构对齐的泛型分页壳（Java 泛型擦除） */\n' +
          'export type OpenApiPageResultOf<T> = {\n' +
          '  items?: T[];\n' +
          '  page?: number;\n' +
          '  size?: number;\n' +
          '  total?: number;\n' +
          '};'
      }
    ]
  }
];

/** index.ts 必须再导出的别名（抽检，防漏 re-export）。 */
export const OPENAPI_INDEX_REEXPORTS = [
  'OpenApiOrderReadModelMerchant',
  'OpenApiMerchantWalletOverviewDto',
  'OpenApiMerchantDisputeSummaryDto',
  'OpenApiMerchantDeviceReportDto',
  'OpenApiMerchantReplenishmentRequestDto',
  'OpenApiAdminDeviceDto'
];

/**
 * @param {string} banner
 * @param {AliasGroup} group
 */
export function renderAliasGroupFile(banner, group) {
  const lines = [`${banner}import type { components } from './openapi';`, ''];
  if (group.title) {
    lines.push(`/** ${group.title} */`);
  }
  for (const item of group.exports) {
    if (item.raw) {
      lines.push(item.raw);
      lines.push('');
      continue;
    }
    if (item.comment) {
      lines.push(`/** ${item.comment} */`);
    }
    lines.push(`export type ${item.name} = components['schemas']['${item.schema}'];`);
  }
  if (group.footer) {
    lines.push(group.footer);
  }
  return lines.join('\n').replace(/\n{3,}/g, '\n\n') + '\n';
}
