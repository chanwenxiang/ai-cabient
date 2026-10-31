-- MIGRATION_KIND: backfill
-- MA-018 字典历史别名收敛（用户 2026-10-31 拍板：彻底清除；测试阶段无历史包袱）
-- 顺序：先迁数据到规范值 → 再删别名/死值字典行 → 再拆 enable_status 的 DISABLED（改由 bin_status 承担）
-- 说明：
--   1) 各表历史值均为 0 行（2026-10-31 实测），迁移语句保留以便生产库执行时同样收敛；
--   2) 前端 baseline（packages/shared-dict）仍保留别名标签用于「万一还有历史行」的渲染，
--      但下拉选项已在 dictOptions 层排除（MA-018 B 案），且本迁移后库里不再产生新别名行。

-- ── 1) 数据迁移 ─────────────────────────────────────────────────────────────
UPDATE user_feedback SET status = 'HANDLED'
WHERE UPPER(status) = 'REPLIED';

UPDATE replenishment_task_line SET line_type = 'PULL_OFF'
WHERE UPPER(line_type) IN ('PULL', 'REMOVE');

UPDATE inventory_movement SET movement_type = 'MANUAL_INBOUND'
WHERE UPPER(movement_type) = 'INBOUND_MANUAL';

-- 分账：裸 SUBMITTED/FAILED 无任何写入点（仅 WECHAT_* 常量在写），按渠道前缀归并
UPDATE order_revenue_split SET status = 'WECHAT_SUBMITTED'
WHERE UPPER(status) = 'SUBMITTED';
UPDATE order_revenue_split SET status = 'WECHAT_FAILED'
WHERE UPPER(status) = 'FAILED';

-- ── 2) 删除别名 / 死值字典行（种子侧同步见 SysDictBootstrap）────────────────
DELETE FROM sys_dict_data WHERE dict_type = 'feedback_status' AND dict_value = 'REPLIED';
DELETE FROM sys_dict_data WHERE dict_type = 'ops_alert_type' AND dict_value = 'RECONCILIATION_MISMATCH';
DELETE FROM sys_dict_data WHERE dict_type = 'restock_line_type' AND dict_value IN ('PULL', 'REMOVE');
DELETE FROM sys_dict_data WHERE dict_type = 'session_kind' AND dict_value = 'SHOPPING';
DELETE FROM sys_dict_data WHERE dict_type = 'split_status' AND dict_value IN ('FAILED', 'SUBMITTED');
DELETE FROM sys_dict_data WHERE dict_type = 'warehouse_movement_type' AND dict_value = 'INBOUND_MANUAL';

-- ── 3) enable_status 拆分：货位状态独立成 bin_status（DISABLED 仅此处与识别映射渲染在用）──
DELETE FROM sys_dict_data WHERE dict_type = 'enable_status' AND dict_value IN ('ENABLED', 'DISABLED');

INSERT INTO sys_dict_type (dict_type, dict_name, status)
SELECT 'bin_status', '货位状态', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'bin_status');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE'
FROM (VALUES
    ('bin_status'::varchar, 'ACTIVE'::varchar, '启用'::varchar, 1),
    ('bin_status'::varchar, 'DISABLED'::varchar, '停用'::varchar, 2)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'bin_status')
  AND NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
  );
