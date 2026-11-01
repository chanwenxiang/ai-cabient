-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: CB-082
-- R9（2026-11-01，MA-023② + MA-024）：给订单补一列「全部优惠前毛额」`gross_amount_cents`。
--
-- 为什么必须加列（而不是复用现有字段）：
--   · `cabinet_order` 只有 total / paid / member / activity / coupon 五列，**没有原价列**；
--     实体里的 `originalAmountCents` 带 `@TableField(exist = false)`（CabinetOrder.java:74-75），
--     只在结算内存里活一次（券折前应付），从库里读回来恒为 0。
--   · 于是三端订单「优惠拆解」少一层：消费者看到 商品合计 ¥2.43 / 优惠券 减¥0.00 / 实付 ¥2.43，
--     而这一单其实享了 会员 2%（¥0.07）+ 活动立减（¥1.00）；`pages/result` 有活动行、
--     `pages/order-detail` 没有 ⇒ 同一单两个页面口径不一致（MA-024）。
--   · 会员折扣此前连入账都没有（`member_discount_cents` 全仓无写入者，恒 0）——巡检的折后式
--     还正把它当减数（已在本批前置提交 813bb20e 拆雷）。
--
-- 语义钉死（写入口 `SettlementOrderSupport.applyItemsToOrder`，读侧只展示不再算）：
--     gross = Σ 目录/柜机价 × 数量                    ← 本列
--     line_amount_cents = Σ 会员折后且活动折后单价 × 数量
--     total = lineSum − coupon                        ← 既有
--     ⇒ 恒等式  gross − member_discount − activity_discount − coupon_discount = total
--
-- 回填：历史行按恒等式反推（`member_discount_cents` 历史恒 0 ⇒ 老单 gross = total + activity + coupon，
-- 与真实会员折前价可能差几分，属**已知且刻意接受**的近似：老单没有留会员快照，宁可显示可减平的
-- 账面值，也不要一个算不平的数）。守卫 `gross_amount_cents = 0` ⇒ 幂等，重放不改已回填行。
-- 已知边界：部分退款后 `total` 变小而 gross 不重算 ⇒ 已退款单的本列仍是原毛额（页面据此显示
-- 「原价合计」是正确语义；差额由 refunded_cents 一行交代），本轮不引入重算，避免碰退款链。
--
-- LOCK_RISK: low（`ADD COLUMN ... NOT NULL DEFAULT 0` 在 PG 11+ 为元数据操作，不重写表；
--   本仓 dev 库 cabinet_order 13 行，UAT/prod 亦为小表。回填 UPDATE 命中全表但行数小、单列。）
-- TABLES: cabinet_order（加 1 列 + 全行回填）
-- NOTES: 无索引变更；不改历史迁移；读模型新增 grossAmountCents 字段随本批代码一起出

ALTER TABLE cabinet_order
    ADD COLUMN IF NOT EXISTS gross_amount_cents integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN cabinet_order.gross_amount_cents IS
    '全部优惠前毛额（分）：目录/柜机价 × 数量之和；gross − member − activity − coupon = total';

UPDATE cabinet_order
   SET gross_amount_cents = total_amount_cents
                             + COALESCE(member_discount_cents, 0)
                             + COALESCE(activity_discount_cents, 0)
                             + COALESCE(coupon_discount_cents, 0)
 WHERE gross_amount_cents = 0;
