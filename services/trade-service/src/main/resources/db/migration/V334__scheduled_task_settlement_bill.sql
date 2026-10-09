-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- NOTES: 仅 INSERT 一行 scheduled_task 种子（ON CONFLICT 幂等），无 DDL、无数据回填。
-- TABLES: scheduled_task (32 rows, 种子 +1)
-- 商户月度结算单调度（CB-020 ②，V330 建表）补 scheduled_task 种子行。
-- 合并后 check:scheduled-task-seed 拦截：MerchantSettlementBillScheduler 调用
-- tryBegin("settlement-bill") 但缺登记行，运营台不可见/不可启停。
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('settlement-bill', '商户月度结算单重建', 'TRADE', '每日 02:40',
 '每日 02:40（对账 01:30 之后错峰，避免与资金域任务抢连接池）重建「上月 + 本月」两张账期：上月为关账终值，本月为滚动快照（merchant×账期月唯一快照，后端幂等重建）')
ON CONFLICT (task_key) DO NOTHING;
