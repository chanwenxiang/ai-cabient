-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: CB-020
-- 商户结算通知对齐老系统「收入结算通知」三字段样式（用户 2026-10-14 提供老程序截图：
-- 商户名称/结算周期/收入金额）。V163 原模板只有 {amount} 单参数，升级为三参数；
-- 接线见 MerchantSettlementBillService（新建结算单且 settled>0 时发送，重算不重发）。
UPDATE notification_template
SET title_template = '收入结算通知',
    body_template  = '商户名称：{merchantName}
结算周期：{period}
收入金额：{amount} 元'
WHERE template_code = 'merchant_settlement';
