-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: CB-082
-- 活动限定商品 v1（用户 2026-11-01 拍板）：仅 商品立减/商品折扣/一口价；活动价与券互斥；
-- banner 必选 + 开门前/关门后弹窗二选一；范围 全部/柜机/商户；时间窗必填；不允许 0 元购。
-- SKU 范围沿用 promotion_activity.rule_config 的 JSON 数组（与既有 deviceIds 同约定），不另建表。

-- 1) 活动定价参数（按 activity_type 取其一）
ALTER TABLE promotion_activity
    ADD COLUMN IF NOT EXISTS reduce_cents INT,
    ADD COLUMN IF NOT EXISTS discount_bps INT,
    ADD COLUMN IF NOT EXISTS fixed_price_cents INT;

-- 约束用条件块：迁移重放（先删 history 行再跑）时不炸
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_promo_reduce_nonneg') THEN
        ALTER TABLE promotion_activity ADD CONSTRAINT ck_promo_reduce_nonneg
            CHECK (reduce_cents IS NULL OR reduce_cents >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_promo_bps_range') THEN
        ALTER TABLE promotion_activity ADD CONSTRAINT ck_promo_bps_range
            CHECK (discount_bps IS NULL OR (discount_bps > 0 AND discount_bps < 10000));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_promo_fixed_price_min') THEN
        ALTER TABLE promotion_activity ADD CONSTRAINT ck_promo_fixed_price_min
            CHECK (fixed_price_cents IS NULL OR fixed_price_cents >= 1);
    END IF;
END $$;

-- 2) 展示位：banner 必选（业务校验）；弹窗位每活动最多一个（DB 兜底）
CREATE TABLE IF NOT EXISTS promotion_placement (
    placement_id BIGSERIAL PRIMARY KEY,
    activity_id BIGINT NOT NULL REFERENCES promotion_activity (activity_id) ON DELETE CASCADE,
    position VARCHAR(24) NOT NULL,
    asset_id BIGINT NOT NULL,
    image_width INT,
    image_height INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_promo_placement_pos
    ON promotion_placement (activity_id, position);
CREATE UNIQUE INDEX IF NOT EXISTS uk_promo_placement_popup
    ON promotion_placement (activity_id)
    WHERE position IN ('POPUP_BEFORE_OPEN', 'POPUP_AFTER_CLOSE');

-- 3) 计价留痕：订单行记录命中的活动与优惠额，订单头汇总
ALTER TABLE cabinet_order
    ADD COLUMN IF NOT EXISTS activity_discount_cents INT NOT NULL DEFAULT 0;
ALTER TABLE cabinet_order_line
    ADD COLUMN IF NOT EXISTS activity_id BIGINT,
    ADD COLUMN IF NOT EXISTS activity_discount_cents INT NOT NULL DEFAULT 0;

-- 4) 字典：v1 三种可执行类型（既有 6 个类型保留用于历史活动展示）
-- 🔴 空库必须先有 dict_type 行：字典类型由 SysDictBootstrap 在**启动时**播种，而 Flyway 在启动前跑，
-- 直接插 sys_dict_data 会撞 sys_dict_data_dict_type_fkey（2026-11-01 空库 IT 实证）。
INSERT INTO sys_dict_type (dict_type, dict_name, status, remark, sort_order)
SELECT 'promotion_type', '营销活动类型', 'ACTIVE', '营销活动类型中文映射', 40
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'promotion_type');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE'
FROM (VALUES
    ('promotion_type'::varchar, 'SKU_REDUCE'::varchar, '商品立减'::varchar, 90),
    ('promotion_type'::varchar, 'SKU_DISCOUNT'::varchar, '商品折扣'::varchar, 91),
    ('promotion_type'::varchar, 'SKU_FIXED_PRICE'::varchar, '一口价'::varchar, 92)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
