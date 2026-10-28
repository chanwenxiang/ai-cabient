-- MIGRATION_KIND: create
-- MIGRATION_REVIEWED: no
-- COMPETITOR_REF: CB-068,CB-070
-- LOCK_RISK: low
-- TABLES: warehouse_manager（新表）+ warehouse（仅改列注释，无 DDL 变更）
-- NOTES: 纯 CREATE TABLE/INDEX + 幂等 INSERT ... ON CONFLICT DO NOTHING；可重入。
--
-- 分仓负责人从「一人」改为「多人」（2026-10-28 用户拍板）：
--   现实里一个分仓常有多名补货员轮班，单值列逼着运营二选一，换人就断掉另一个人的
--   采购入库/盘点入口（实测 WH-DEMO-001 改绑 13800138007 后 13800138001 的「仓库盘点」
--   `satellite-warehouse` 直接 400「没有已指定您为负责人的分仓」）。
--
-- 真相只有一份：warehouse_manager 是负责人集合的唯一来源，全部权限判据改读它。
-- warehouse.manager_user_id **保留**，但降级为「主责人派生列」（= 最早绑定者），
-- 由 WarehouseService 写入时同步；月结「主责人姓名」「外部渠道人员→仓」这类
-- 单一问责口径继续读它，语义不变。

CREATE TABLE IF NOT EXISTS warehouse_manager (
    manager_id   BIGSERIAL PRIMARY KEY,
    warehouse_id VARCHAR(32) NOT NULL REFERENCES warehouse (warehouse_id) ON DELETE CASCADE,
    user_id      BIGINT      NOT NULL REFERENCES user_info (user_id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 同一仓同一人只允许一行（重复绑定静默去重，而不是抛 500）
CREATE UNIQUE INDEX IF NOT EXISTS uk_warehouse_manager_pair
    ON warehouse_manager (warehouse_id, user_id);

-- 反查「我负责哪些仓」：商户端每次进盘点/采购都要走，必须有索引
CREATE INDEX IF NOT EXISTS idx_warehouse_manager_user
    ON warehouse_manager (user_id);

COMMENT ON TABLE warehouse_manager IS '分仓负责人（多人）：一仓可配多名补货员，均具备该仓采购/盘点权限';
COMMENT ON COLUMN warehouse_manager.warehouse_id IS '分仓编号（warehouse.warehouse_id）';
COMMENT ON COLUMN warehouse_manager.user_id IS '负责人 user_info.user_id';
COMMENT ON COLUMN warehouse_manager.created_at IS '绑定时间：同仓多人时最早者为「主责人」';

COMMENT ON COLUMN warehouse.manager_user_id IS
    '分仓主责人（派生列，= warehouse_manager 最早绑定者，由服务端同步）；单一问责口径（月结/外部渠道导入）读此列，权限判据读 warehouse_manager';

-- 回填现有单值负责人：谁现在是负责人，就进新表
INSERT INTO warehouse_manager (warehouse_id, user_id)
SELECT w.warehouse_id, w.manager_user_id
FROM warehouse w
WHERE w.manager_user_id IS NOT NULL
ON CONFLICT (warehouse_id, user_id) DO NOTHING;
