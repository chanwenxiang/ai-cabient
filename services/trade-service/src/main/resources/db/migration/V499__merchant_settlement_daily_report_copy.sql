-- V499: 商户日结通知文案改「经营日报」口径（补订单数与客单价）
-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: notification_template（单行 UPDATE：merchant_settlement）
-- LOCK_RISK: low（配置表单行 UPDATE，无 DDL、无全表扫描）
-- ROLLBACK: 不回滚 —— 新文案里的 {orders}/{avg} 是本轮渲染参数（MA-377），
--   回滚文案而不回滚代码只会让商户看到「本期结算 X 元已入账」，无数据风险。
-- NOTES: 真机验收反馈「商户要每天快捷知道今天卖了多少」——原模板只有
--   「本期结算 {amount} 元已入账，请注意查收。」，用户看完只知道钱到了、不知道卖了几单，
--   也无从判断客单高低。此轮把站内信文案与微信日报（MerchantDailyReportNotifier）统一为
--   同一组数字：销售额 / 笔数 / 客单价 / 费用 / 净入账，两条渠道口径一致，避免「站内信
--   1901.23、微信 1901.2」这种对不上账。
--   ⚠️ 调用方（MerchantDailyIncomeNotifyScheduler）已同步传 orders/avg；若将来有其他人用
--   本模板只传 amount，{orders}/{avg} 会原样留在文案里（render 只替换收到的键）——
--   check-merchant-daily-report 门禁钉住「本模板的渲染参数必须含 orders/avg」。
--   V163 用 ON CONFLICT DO NOTHING 种的行，这里按 template_code 精确定位，可安全重放。

UPDATE notification_template
SET title_template = '经营日报',
    body_template = '{period} 销售额 {amount} 元、{orders} 笔订单、客单价 {avg} 元；'
                    || '费用 {fee} 元，净入账 {net} 元。'
WHERE template_code = 'merchant_settlement';
