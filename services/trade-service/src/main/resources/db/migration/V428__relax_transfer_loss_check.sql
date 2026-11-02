-- MIGRATION_KIND: backfill
-- R11 B1（2026-11-02，MA-037）：跨仓调拨创建必然 500 —— 行级约束与创建期写入语义冲突。
--
-- 现场：`POST /api/v2/ops/admin/warehouse/transfers` ⇒ 500「系统繁忙」，traceId `f46acef186cc`，
--   `new row for relation "warehouse_transfer_line" violates check constraint "chk_wh_transfer_loss"`，
--   Failing row `(5, 2, 100013, '', null, 1, 0, 0, null, null)` ⇒ quantity=1 而 received=0/loss=0。
-- 成因：原约束 `(received_qty + loss_qty) = quantity` 是**收货后**的不变式，但创建调拨时
--   实收/损耗根本还不知道（服务写 0/0），于是任何正常调拨在 INSERT 阶段就违反约束。
--
-- 处置（MA-029 同思路：把不变式拆到"能在该阶段成立"的位置，而不是放宽到没人管）：
--   · DB 只保证**不会超发/超收**：`received_qty + loss_qty <= quantity`（任何阶段都成立）；
--   · 「收齐时必须相等」由应用层保证：`TransferLossValidator.validate` 强制
--     `loss == shipped − received`，且收货接口只允许 SHIPPED→RECEIVED 时调用；
--     实收缺省值即"全部到齐"。
--   ⇒ 约束从"创建期就不可能满足"变成"创建期恒真、收货后由服务端钉死"，无需改写入路径。
--
-- 兼容：既有行都满足旧（更强）约束 ⇒ 必然满足新约束，无需 NOT VALID / 数据修复。
-- 幂等：DROP IF EXISTS + 重建同名约束；重放安全。
-- LOCK_RISK: low（`warehouse_transfer_line` 小表；加 CHECK 会扫表，行数可忽略）
-- TABLES: warehouse_transfer_line
-- NOTES: 纯 DDL；不改写入代码、不改 TransferLossValidator；MA-037 的「500 应改 4xx」另行评估
--（DB 异常仍会冒 500；本迁移先让正常路径不再触发它）

ALTER TABLE warehouse_transfer_line
    DROP CONSTRAINT IF EXISTS chk_wh_transfer_loss;

ALTER TABLE warehouse_transfer_line
    ADD CONSTRAINT chk_wh_transfer_loss CHECK (received_qty + loss_qty <= quantity);

COMMENT ON CONSTRAINT chk_wh_transfer_loss ON warehouse_transfer_line IS
    'MA-037：只保证不超发/超收；"收齐时 received+loss=quantity" 由 TransferLossValidator 在收货时强制';
