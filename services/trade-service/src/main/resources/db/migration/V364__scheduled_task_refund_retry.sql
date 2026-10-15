-- MIGRATION_KIND: backfill
-- 渠道退款重试闭环调度登记（RefundRetryScheduler，B1/G1）。
-- tryBegin("refund-retry") 需登记行，否则运营台不可见/不可启停（V334/V352 同因）。
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('refund-retry', '渠道退款重试闭环', 'FINANCE', '每 60 秒',
 '分钟级闭环 REFUND_INITIATED 痕迹：微信查单优先（SUCCESS 即闭环不重发；ABNORMAL 不自动重发交人工），PROCESSING/无结果按同号 out_refund_no 重发（渠道侧幂等不双退）；created_at 超 30 分钟无终态置 REFUND_FAILED 并发 HIGH 告警转人工；另复用对账器 advanceProcessingRefunds 推进 PROCESSING 单')
ON CONFLICT (task_key) DO NOTHING;
