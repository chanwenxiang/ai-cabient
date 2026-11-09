-- 将邑 §7.10 商品状态轮询定时任务种子（审核中/柜内审核中的挂接行逐分钟刷新）
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark)
VALUES ('jiangyi-product-state-poll', '将邑商品状态轮询', 'TRADE', '每 60 秒', '刷新 REVIEWING/CABINET_REVIEWING 商品状态与驳回原因')
ON CONFLICT (task_key) DO NOTHING;
