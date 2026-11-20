-- MA-377: 商户经营日报（微信）调度登记
-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: scheduled_task（单行 INSERT … ON CONFLICT DO NOTHING）
-- LOCK_RISK: low
-- ROLLBACK: 不回滚 —— 删行会让运营台看不见该任务；真要停用请把 enabled 置 false
-- NOTES: tryBegin("merchant-daily-report") 需登记行，否则运营台不可见/不可启停（V352 同因）。
--   为什么独立于 merchant-daily-income-notify（00:20）：站内信是静默入库，微信订阅消息
--   会真弹通知——凌晨弹窗只会换来商户关掉提醒，故日报单独在 07:30 推（竞品同为早间）。

INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('merchant-daily-report', '商户经营日报（微信）', 'TRADE', '每日 07:30',
 '每日 07:30 把「昨日销售额 / 成交笔数 / 客单价」按商户推微信订阅消息（MA-377）：不扣费、不写站内信，扣费与站内信仍由 00:20 的 merchant-daily-income-notify 负责；收件人=ops_user_merchant 绑定的操作员，按「操作员 × 日期」幂等，重跑只补发未收到的人；未配模板（WECHAT_MERCHANT_DAILY_REPORT_TEMPLATE）或 notify.wechat.enabled=false 时静默跳过')
ON CONFLICT (task_key) DO NOTHING;
