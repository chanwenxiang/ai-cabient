-- MIGRATION_KIND: backfill（仅更新 scheduled_task 的展示频率，无 DDL、无业务数据）
-- 2026-11-23 实证：01:30 拉「昨天」的微信 tradebill 返回 500（账单次日 9 点后才生成）。
-- 代码侧主跑改 10:30、14:30 重试；这里同步运营台显示的频率，避免「页面写 01:30、实际 10:30」。
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: scheduled_task (1 row)
-- NOTES: 按主键 task_key 更新单行 schedule_desc/updated_at，非热表、无长锁。

UPDATE scheduled_task
SET schedule_desc = '每日 10:30（14:30 重试）',
    updated_at = NOW()
WHERE task_key = 'reconciliation';