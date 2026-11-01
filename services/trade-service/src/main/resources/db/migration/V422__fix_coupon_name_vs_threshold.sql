-- MIGRATION_KIND: backfill
-- R9 批 3（2026-11-01，MA-028）：3 张积分兑换券的「券名门槛」与实际 `min_spend_cents` 不符，按门槛改名。
--
-- 实测（dev 库 `coupon_definition`，面额/门槛单位＝分）：
--   id 4  「满 100 减 20 券」 min_spend_cents=5000 ⇒ 实际满 ¥50 才能用
--   id 5  「满 50 减 10 券」  min_spend_cents=2000 ⇒ 实际满 ¥20
--   id 6  「满 20 减 5 券」   min_spend_cents=0    ⇒ 实际无门槛
-- 消费者端「积分兑换」页把券名与「满 X 可用」并排显示（`pages/points/redeem`），于是同一张卡上
-- 出现两个门槛；用户按名字理解必然算错可用条件。结算执行的是 `min_spend_cents`
-- （`CouponService.resolveDiscount` 只读门槛，不看名字），所以**名字服从门槛**。
--
-- 处置：只改 `coupon_name`，不动门槛/面额/有效期/已发量，也不删任何行；已发到用户手上的券
-- 显示的是券定义名（`toDto` 每次按 def 现取），改名即刻生效，历史 `user_coupon` 行不受影响。
-- 防复发：`CouponService.validateNameMatchesThreshold` 在新增/编辑券的写入口拦住新的名字⇔门槛错配
-- （只认「满 N 减」这种带数字的明确承诺，「满减券 ¥5」这类不含数字门槛的名字不受约束）。
--
-- 爆炸半径（本轮查证）：这三张券是 **V163 播的演示积分兑换券**，且 **V345 已在 `seed_env='none'`
-- （prod/staging）整行删除**，只有 local/dev/uat 还留着 ⇒ 本迁移在生产命中 0 行（幂等且无害），
-- 修的是 dev/uat 演示数据；真正长期有效的是上面那条写入口校验。
-- 匹配条件带 `coupon_name = '<旧名>'` ⇒ 幂等：改过一次后再命中 0 行，重放安全。
-- LOCK_RISK: low
-- TABLES: coupon_definition (~6 rows，dev/uat 改 3 行名；prod 已被 V345 删除 ⇒ 0 行)
-- NOTES: 纯 DML 改名；无 DDL、无外键变更；种子来源 V163（演示券）与 V345（生产清除）一律不改

UPDATE coupon_definition
   SET coupon_name = '满 50 减 20 券'
 WHERE coupon_def_id = 4
   AND coupon_name = '满 100 减 20 券';

UPDATE coupon_definition
   SET coupon_name = '满 20 减 10 券'
 WHERE coupon_def_id = 5
   AND coupon_name = '满 50 减 10 券';

UPDATE coupon_definition
   SET coupon_name = '无门槛减 5 券'
 WHERE coupon_def_id = 6
   AND coupon_name = '满 20 减 5 券';
