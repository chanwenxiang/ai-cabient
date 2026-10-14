-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: CB-020
-- 日结通知升级净额口径（用户 2026-10-14 拍板「净额展示+每日扣余额」）：周期费用
-- （租金/流量费，每柜月额按天摊销）每日从商户余额扣，通知展示 收入/平台费用/净入账。
-- 对齐老系统「收入结算通知」多字段形态 + 补上老系统点位费用「只记账不扣款」的缺失闭环。
UPDATE notification_template
SET title_template = '收入结算通知',
    body_template  = '商户名称：{merchantName}
结算周期：{period}
收入金额：{amount} 元
平台费用：{fee} 元
净入账：{net} 元'
WHERE template_code = 'merchant_settlement';
