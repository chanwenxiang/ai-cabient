-- MIGRATION_KIND: column
-- MIGRATION_REVIEWED: yes
-- COMPETITOR_REF: CB-074
-- LOCK_RISK: low
-- TABLES: warehouse_outbound_line（dev 实测 12 行；非热表，PG11+ 的 ADD COLUMN + 常量默认值是纯元数据操作，不重写表）
-- NOTES: 只加一列，无索引、无扫描、无 DROP。签收路径从此按 SKU 记现场实收，差额写本列（CB-074）。
--
-- 为什么要这一列（2026-10-29 用户拍板「差异要有台账」）：
--   补货出库行 `quantity` 记的是**领货量**（从分仓带出去多少），而柜机入账只认现场逐项确认的
--   实收量（铁律 78：开门柜真值只在 device_sku_inventory；2026-10-28 真机事故就是把领货量
--   当实收量，凭空给柜机多记 6 件）。两者之差此前**无处可存**：
--   `receiveOutboundHandover` 只好传 `Integer.MAX_VALUE` 把整单伪造成「全量签收」，
--   于是「领了 10 件、只上柜 7 件」这件事在三端都看不见，月结「上柜」列其实是领货量。
--
--   差额**不写成库存流水**：`OUTBOUND_SHIP` 已按领货量扣过一次分仓（铁律 79 三层账），
--   再补一条负 delta 会双扣；写回分仓等于凭空造库存（货可能还在补货员手上）。
--   对照 CB-074：Dynamics 365 BC 部分接收把差额留在**同一张单据的同一行**作为未结数量，
--   Parlevel 现场异常「就地记录、同步回系统」，行业里没有任何一家把退回做成系统默认。
--
-- 历史行：本列一律 0。旧实现把整单记成 RECEIVED，而现场实收从未落库 ⇒ 差额无法回算，
--   2026-10-29 之前的月结「上柜」列继续按领货量口径读，不做假回填。

ALTER TABLE warehouse_outbound_line
    ADD COLUMN IF NOT EXISTS remainder_qty INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN warehouse_outbound_line.remainder_qty IS
    '领而未上件数 = quantity − 现场实收（CB-074）。0=全量上柜；>0=差额未结，'
    '货仍在补货员/站点手上，既不在分仓账也不在柜机账；要退回分仓须另开张单据，不由本列自动发生。';
