-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: warehouse_transfer_line（补 4 列：在途损耗）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = ALTER TABLE warehouse_transfer_line
--           DROP COLUMN loss_qty, received_qty, loss_reason, loss_note
-- NOTES: 跨仓调拨在途损耗（2026-10-07，仓储补货域缺口 #3）。
--
--   背景：取证 `WarehouseTransferService.doReceive`（`:154-159`）——
--   收货时**按发货时的 quantity 全量入库**（`line.getQuantity()`），
--   `warehouse_transfer_line` 也只有 quantity 一个数量字段。
--   ⇒ 「发出 100、实际到 95」这 5 件的差额**无处记录**：
--   · 入库账是 100（虚增5 件库存）
--   · 也无处归因（谁该赔：物流？仓库？）
--   ⇒ 调拨成了损耗的**黑洞**。
--
--   🔴 与 V311 `inventory_write_off` 的分工：
--   - 本表记录「**发运途中**的损耗」（货已离A 仓、未到 B 仓），
--     差额是 A 仓已扣、B 仓未收的量；
--   - `inventory_write_off` 记录「**已入库后**的核销」。
--   两段链路不能混：混了会重复计损耗。
--
--   设计取舍：
--   1) `received_qty` 与 `loss_qty` **并存**而非只存损耗 ——
--      收货时要记「实收多少」（哪怕全对，0 也是有意义的实测量），
--      只存损耗就丢失了「已确认收货」这个事实。
--   2) CHECK 约束 `received + loss = quantity` 是**本次的关键护栏**：
--      没有它，运营把「实收 90、损耗 0」填进去，系统就以为 10 件凭空消失，
--      而**损耗恒等于发运量减去实收量，不该让���手填**。
--      ⇒ 用带默认值的 CHECK 让数据库兜住算术错误。
--   3) `received_qty` 默认 = quantity：历史行与「全部到齐」场景零改动。
--      收货时未填即视为全到（向后兼容旧的只发不收流程）。
--   4) `loss_reason` 不加枚举 CHECK：V311 已有 `reason_category` 的分类体系
--      （EXPIRED/DAMAGED/LOST…），在途损耗复用同一套语义，此处只存原始值。

ALTER TABLE warehouse_transfer_line
    ADD COLUMN IF NOT EXISTS received_qty  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS loss_qty      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS loss_reason   VARCHAR(64),
    ADD COLUMN IF NOT EXISTS loss_note     VARCHAR(256);

-- 🔴 回填：历史行一律视为「全部到齐、零损耗」。
--   ⚠️ 这是**假设**而不是事实 —— 但它只影响历史行的报表口径
--   （不显示损耗），不会让库存错账（quantity 未动）。
--   若反推成别的值，等于凭空制造损耗记录。
UPDATE warehouse_transfer_line
SET received_qty = quantity, loss_qty = 0
WHERE received_qty = 0 AND loss_qty = 0;

-- 算术护栏：实收 + 损耗 = 发运量
ALTER TABLE warehouse_transfer_line
    ADD CONSTRAINT chk_wh_transfer_loss CHECK (received_qty + loss_qty = quantity);

-- 非负
ALTER TABLE warehouse_transfer_line
    ADD CONSTRAINT chk_wh_transfer_loss_nonneg CHECK (received_qty >= 0 AND loss_qty >= 0);

-- 🔴 有损耗的行必须写原因（否则又是一笔「说不清」的损耗）
ALTER TABLE warehouse_transfer_line
    ADD CONSTRAINT chk_wh_transfer_loss_reason CHECK (
        loss_qty = 0 OR (loss_reason IS NOT NULL AND TRIM(loss_reason) <> '')
    );

-- 按损耗查询（追责/统计）
CREATE INDEX IF NOT EXISTS idx_wh_transfer_loss
    ON warehouse_transfer_line (transfer_id) WHERE loss_qty > 0;

COMMENT ON COLUMN warehouse_transfer_line.received_qty IS 'V314：实收数量。默认 0，靠回填置为 quantity。CHECK 保证 received+loss=quantity';
COMMENT ON COLUMN warehouse_transfer_line.loss_qty IS 'V314：在途损耗数量（发运减实收）。>0 时 loss_reason 必填';
COMMENT ON COLUMN warehouse_transfer_line.loss_reason IS 'V314：在途损耗原因（自由文本；分类语义与 WriteOffReasonCategory 一致）';
COMMENT ON COLUMN warehouse_transfer_line.loss_note IS 'V314：在途损耗备注（理赔/说明）';