-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-044
-- 用户拍板（2026-10-17）：撤机安装费「默认为 0」，并且做成**按柜配置**（运营台登记表单补参数位）。
--
-- 为什么改默认值：V365 把 `install_fee_deduct_cents` 定成 10000 分=100 元（照抄老系统
-- GuaranteeCashService 的 CASH_INSTALL_FEE_DEDUCT=10000 常量），但老系统那是**全局硬编码、不分柜不分商户**，
-- 而行业撤机清算用「违约金/损失抵偿」名目、没有「安装费」这一项（CB-044 结论：仅老系统一家）。
-- 更要紧的是运营台登记表单只传 设备/金额/备注，运营看不到这笔费用，结算时却照扣 ⇒
-- 「结算将返还剩余押金 ¥全额」的弹窗承诺与实扣 100 元不一致（动钱的前后不一致，2026-10-17 复核）。
-- 按本仓既有约定「0/NULL=不收」（2026-10-16 F-2/F-3 拍板），默认归 0 = 开箱不收这笔费，
-- 要收就在登记时逐柜填。
--
-- 只改列默认、不回改存量登记行：登记行的费用是**登记时点**的约定，与「历史 order_revenue_split 不回改」
-- 「押金流水不改写」同一口径；本项目库起点是全新空库 Flyway 从零（无存量生产登记行），
-- 归零默认值即覆盖全部新登记。dev 库既有测试行仍按原值结算，需运营逐柜处置。
-- clawback（倒扣）已由 V376 改为默认 FALSE 并同步了存量行，本迁移不重复触碰。
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: device_rental_deposit (~0 rows in prod-at-rebuild)
-- NOTES: 仅 ALTER COLUMN SET DEFAULT（PG 元数据级，不重写表）；非热表（不在 check-migration-safety HOT_TABLES 名单）。

ALTER TABLE device_rental_deposit
    ALTER COLUMN install_fee_deduct_cents SET DEFAULT 0;

-- 零残留断言：默认值必须真的归 0（照 V376 ④ 的姿势，防「写了迁移却没生效」）。
DO $install_fee_zero_default$
DECLARE
    d text;
BEGIN
    SELECT column_default::text INTO d FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'device_rental_deposit'
      AND column_name = 'install_fee_deduct_cents';
    IF d IS DISTINCT FROM '0' THEN
        RAISE EXCEPTION 'V383 断言失败：install_fee_deduct_cents 列默认值仍是 %（期望 0=不收）', d;
    END IF;
END
$install_fee_zero_default$;
