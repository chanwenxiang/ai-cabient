-- V319: 券可用范围（E10 / 缺口 #10）
--
-- 背景与取证（2026-10-07）
-- ------------------------------------------------------------------
-- 文档写「券无适用商户/柜机范围」，实测比那更严重：
--   coupon_definition.device_scope **已存在但零消费者**
--   —— CouponService 不读它、mapper 不按它筛、库里 5 张券全是 'ALL'。
--   ⇒ 它是一个「看着有、其实不生效」的死字段：运营改了它，用户侧毫无变化。
--
-- 所以本次不是「加一个字段」，而是**让范围真的生效**。
-- 拦点是唯一的：CouponService.evaluateCoupon()（唯一决定「这张券此刻能不能用」的地方）
--
-- 🔴 为什么不在 DB 用 CHECK 硬枚举所有范围？
--   范围类型会随运营需要增长（今天 3 种，明年可能加「指定活动」）。
--   CHECK 每次加类型都要改约束 + 全表校验。
--   ⇒ 类型校验放应用侧（Java enum），DB 只做「非空/长度」与「设备集合自洽」。

-- ------------------------------------------------------------------
-- ① scope_type：范围类型
-- ------------------------------------------------------------------
ALTER TABLE coupon_definition
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(24);

UPDATE coupon_definition
SET scope_type = 'ALL'
WHERE scope_type IS NULL;

-- 不加 NOT NULL：与 V311/V312 同纪律 —— 存量未治理时不该让写入失败。
-- 但**加 CHECK 拦住明显的错值**，否则拼错的 scope_type 会静默失效（同 device_scope 今天的病）。
ALTER TABLE coupon_definition
    DROP CONSTRAINT IF EXISTS ck_coupon_scope_type;

ALTER TABLE coupon_definition
    ADD CONSTRAINT ck_coupon_scope_type CHECK (
        scope_type IS NULL OR scope_type IN (
            'ALL'        -- 全平台通用（等价于旧 device_scope=ALL）
            ,'DEVICE'    -- 指定柜机（配合 scope_device_ids）
            ,'MERCHANT'  -- 指定商户（配合 scope_merchant_id）
        )
    );

COMMENT ON COLUMN coupon_definition.scope_type IS
    'V319 券可用范围类型：ALL=全平台/DEVICE=指定柜机/MERCHANT=指定商户。NULL=未治理（等同 ALL 但不可控，见迁移说明）。';

-- ------------------------------------------------------------------
-- ② scope_merchant_id：指定商户
-- ------------------------------------------------------------------
-- 数据基础已核实：device_info.merchant_id 存在
-- ⇒ 券可用范围能表达「这家商户的柜机都能用」，无需新增绑定关系。
ALTER TABLE coupon_definition
    ADD COLUMN IF NOT EXISTS scope_merchant_id VARCHAR(64);

ALTER TABLE coupon_definition
    DROP CONSTRAINT IF EXISTS fk_coupon_scope_merchant;

ALTER TABLE coupon_definition
    ADD CONSTRAINT fk_coupon_scope_merchant
        FOREIGN KEY (scope_merchant_id) REFERENCES merchant (merchant_id);

COMMENT ON COLUMN coupon_definition.scope_merchant_id IS
    'V319 scope_type=MERCHANT 时生效：该商户名下所有柜机可用。';

-- ------------------------------------------------------------------
-- ③ scope_device_ids：指定柜机集合
-- ------------------------------------------------------------------
-- 用数组而不是子表：柜机数量是**个位数到几十**，数组够用且少一次 JOIN。
-- 子表方案在「范围就是几个柜机」这个量级上是过度设计。
ALTER TABLE coupon_definition
    ADD COLUMN IF NOT EXISTS scope_device_ids VARCHAR(64)[];

COMMENT ON COLUMN coupon_definition.scope_device_ids IS
    'V319 scope_type=DEVICE 时生效：指定柜机 ID 集合（空数组=不限制，退化为 ALL）。';

-- ------------------------------------------------------------------
-- ④ 状态回填：把死字段 device_scope 的语义搬进 scope_type
-- ------------------------------------------------------------------
-- device_scope 若本来写了具体柜机（非 ALL），转成 DEVICE + 集合，
-- 避免「运营之前配的范围在迁移后静默变成全平台通用」。
DO
$$
    BEGIN
        IF EXISTS (SELECT 1
                   FROM coupon_definition
                   WHERE device_scope IS NOT NULL
                     AND UPPER(TRIM(device_scope)) <> 'ALL'
                     AND scope_type = 'ALL') THEN
            UPDATE coupon_definition
            SET scope_type = 'DEVICE',
                scope_device_ids = ARRAY[UPPER(TRIM(device_scope))]
            WHERE device_scope IS NOT NULL
              AND UPPER(TRIM(device_scope)) <> 'ALL'
              AND scope_type = 'ALL';
            RAISE NOTICE 'V319: 已把 device_scope 的具体值迁到 scope_type=DEVICE';
        END IF;
    END
$$;

-- ------------------------------------------------------------------
-- ⑤ 索引：按商户/柜机找券
-- ------------------------------------------------------------------
-- 场景：用户站在某台柜机前，系统要找出「这台柜机当前生效的券定义」。
CREATE INDEX IF NOT EXISTS idx_coupon_scope_merchant
    ON coupon_definition (scope_merchant_id)
    WHERE scope_merchant_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_coupon_scope_type_status
    ON coupon_definition (scope_type, status);

-- ------------------------------------------------------------------
-- 迁移后必须验的（不能只看执行成功）
-- ------------------------------------------------------------------
-- 1. SELECT scope_type, COUNT(*) FROM coupon_definition GROUP BY 1;
--    ⇒ 应全部为 ALL（库里原本只有 ALL）
-- 2. 负向：INSERT scope_type='BOGUS' 应被 ck_coupon_scope_type 拒
-- 3. 负向：scope_type=DEVICE 但 scope_device_ids 为空 ⇒ 应用侧应拒（DB 允许，
--    因为「空数组=不限制」是合法语义，见注释）
-- 4. SELECT column_name FROM information_schema.columns
--    WHERE table_name='coupon_definition' AND column_name LIKE 'scope%';
--    ⇒ 4 列都应存在
