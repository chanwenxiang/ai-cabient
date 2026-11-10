-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: CB-066（提现收款账户与提现状态机域：终态字典与后端状态写入点对齐）
-- KIND: backfill
-- R6-F3：CANCELLED 是提现状态机的真实终态（MerchantWithdrawService.cancelFailedLocked:453 /
-- LineWithdrawService:255 写入），前端终态数组已收编（R5-D-新1）；三个字典层缺标签 ⇒
-- 钱包页终态行显示「未知状态」。三处同值：shared-dict baseline / SysDictBootstrap / 本迁移。
-- 守卫：(dict_type, dict_value) NOT EXISTS ⇒ 幂等；标签「已取消」在各 type 内唯一（仅本行）。
-- LOCK_RISK: low（sys_dict_data 小表；2 条条件 INSERT，均为字典元数据，不触业务表）
-- TABLES: sys_dict_data
-- NOTES: 与 `packages/shared-dict`（mp 侧静态基线）+ `SysDictBootstrap`（启动补种）同批改，
--   三处必须同值：mp 读基线、admin 读库、bootstrap 补新库 ⇒ 任一漏改就会三端不同名。

-- R6-F3 修订：sys_dict_data.dict_type 带 FK → 先种类型行（V171 先例；从零库迁移时
-- bootstrap 尚未运行，类型不存在）。ON CONFLICT 幂等，dev/uat 已有类型行时 0 行。
INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES
    ('line_withdraw_status', '线长提现状态', 'ACTIVE', 0, 'R6-F3：CANCELLED 终态补种'),
    ('merchant_withdraw_status', '商户提现状态', 'ACTIVE', 0, 'R6-F3：CANCELLED 终态补种')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, 60, 'ACTIVE'
FROM (VALUES
    ('line_withdraw_status', 'CANCELLED', '已取消'),
    ('merchant_withdraw_status', 'CANCELLED', '已取消')
) AS v(dict_type, dict_value, dict_label)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
