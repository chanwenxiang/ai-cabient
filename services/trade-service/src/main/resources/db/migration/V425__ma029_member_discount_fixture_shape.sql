-- MIGRATION_KIND: backfill
-- MA-029 方案 A（2026-11-02 拍板）：把「会员折扣当订单级减项」这一历史形状就地改成①的含折扣形状。
--
-- 为什么需要这一笔（V424 之后唯一还没平的会员行）：
--   · `member_discount_cents` 全仓历史无写入者 ⇒ 真实业务单该列恒 0，「去掉减法是恒等变换」成立（§1 步骤 4）。
--   · 唯一例外是 V252 的 UAT/dev 演示种子：它把一条 PAID 单改成
--         unit_price_cents=500、line_amount_cents=500、member_discount_cents=59、total_amount_cents=441
--     ——这正是 ②「行金额不含折扣、会员当订单级减项」的算术。
--   · 巡检折后式自 MA-029 起改成「应收 = 行合计 − 券」（`DataConsistencyService.checkOrderConsistency`），
--     上面那行就会 500 ≠ 441 被永久误报成 ORDER_AMOUNT 不一致，且因为 member>0 而落到
--     「多行明细与头金额不一致，需人工」这条无法自动修的分支 ⇒ 演示环境会挂一条修不掉的红灯。
--   · ①形是：会员折扣含在单价里（unit=line=441），会员档 59 只是「目录 500 − 会员 441」的留档，
--     `gross_amount_cents` 已由 V424 反推成 500 ⇒ 恒等式 500 − 59 − 0 − 0 = 441 成立。
--     所以只需把**明细行**从 500 改成 441，订单头四个金额列一个字都不动。
--
-- 守卫：只命中 V252 的完整指纹（状态/四列金额/行价/数量=1），且 `line_amount_cents = 500`
--   ⇒ 改过一次就不再命中（幂等，重放 0 行）。数量非 1 的行不碰（那类行 441 无法整除，属另一码事）。
-- LOCK_RISK: low（两条按主键/等值条件的 UPDATE，命中行数 ≤ 1；本仓 dev 库现网 0 行命中，
--   仅 fresh-from-zero 与 uat 种子环境命中 V252 那一行）
-- TABLES: cabinet_order_line（改 1 行单价/行金额）；cabinet_order（只在 WHERE 里作指纹，不写）
-- NOTES: 不回填任何真实业务单金额（MA-029 §1 步骤 4）；不改已应用的 V252/V424（校验和红线）

UPDATE cabinet_order_line ol
   SET unit_price_cents = 441,
       line_amount_cents = 441
  FROM cabinet_order o
 WHERE ol.order_id = o.order_id
   AND o.status = 'PAID'
   AND o.member_discount_cents = 59
   AND COALESCE(o.coupon_discount_cents, 0) = 0
   AND o.total_amount_cents = 441
   AND o.gross_amount_cents = 500
   AND ol.quantity = 1
   AND ol.unit_price_cents = 500
   AND ol.line_amount_cents = 500;
