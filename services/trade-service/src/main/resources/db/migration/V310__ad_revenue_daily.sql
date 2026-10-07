-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: ad_revenue_daily（新增：广告收益日账，按广告位维度）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = DROP TABLE ad_revenue_daily
-- NOTES: 广告变现「收益对账」独立账本（V310，流量主路线）。
--
--   背景：2026-10-06 三方差距审计列出「广告收益对账/入账」为P1 缺口；
--   2026-10-07 用户拍板变现路线 = **只做腾讯流量主，不出广告主**。
--   展示链路已通（`client/consumer-mp/src/components/wx-ad-slot.vue` 用微信原生 `<ad>`），
--   但**服务端零收入落库** ⇒ 能展示、算不出收入。走流量主路线时这笔钱是
--   唯一收入来源，却对不上账 ⇒ 本项从 P1 升为路线阻塞项。
--
--   1) 为什么**独立账本**而不挂 RevenueSplit：
--      `OrderRevenueSplit` 以 order_id 为锚（分账 + 退款 voidSplitOnFullRefund 联动），
--      而**广告收入没有订单**。硬挂只能造「假订单」，污染订单表与资金对账口径。
--      ⇒ 存储层独立，只在报表层汇总。
--   2) 粒度：**(biz_date, ad_slot)** 每天每广告位一行。
--      不按设备/小程序用户拆 —— 我们**拿不到**用户级数据（微信只给汇总），
--      硬拆只能是假数据。`ad_unit_id`（广告位 ID）作为可选列存细分，
--      但唯一键不含它：微信的 `ad_unit_id` 可能在广告位重建后变化，
--      把它放进唯一键会导致「同一广告位被拆成两行」。
--   3) 金额一律 **BIGINT 分**（微信接口 `income`/`ecpm` 本身就是分）。
--      不用 double 存元 —— 金额用浮点必然出现分位误差，对账时无法收敛。
--   4) `data_source` 区分 `ESTIMATE`（publisher_adpos_general 预估）与
--      `SETTLED`（publisher_settlement 结算）。**收入确认以 SETTLED 为准**，
--      ESTIMATE 只用于趋势展示 —— 两者混在一张表会让人拿预估当已到账。
--   5) 幂等：唯一键 + UPSERT。定时任务会反复拉同一区间（90 天跨度内可重复），
--      重复拉必须覆盖而不是累加 —— 累加会让收入虚增数倍。
--
--   并发：仅 CREATE TABLE + CREATE INDEX，无事务型语句。

CREATE TABLE IF NOT EXISTS ad_revenue_daily (
    ad_revenue_id      BIGSERIAL PRIMARY KEY,
    biz_date           DATE        NOT NULL,
    ad_slot            VARCHAR(64) NOT NULL,
    ad_unit_id         VARCHAR(128),
    ad_unit_name       VARCHAR(255),
    data_source        VARCHAR(16) NOT NULL DEFAULT 'ESTIMATE',
    -- 流量侧计数（拉取量/ 曝光 / 点击）。BIGINT 而非 INT：
    -- 热门广告位单日曝光可过百万（INT 上限 21亿，日累计仍安全但跨月汇总会溢出）。
    req_succ_count     BIGINT      NOT NULL DEFAULT 0,
    exposure_count     BIGINT      NOT NULL DEFAULT 0,
    click_count        BIGINT      NOT NULL DEFAULT 0,
    -- 金额一律「分」。income_cents 为微信返回的 income 原值。
    income_cents       BIGINT      NOT NULL DEFAULT 0,
    ecpm_micros        BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ad_revenue_daily_source CHECK (data_source IN ('ESTIMATE', 'SETTLED'))
);

-- 唯一键：不含 ad_unit_id（理由见上方 NOTES 第 2点）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ad_revenue_daily_date_slot_source
    ON ad_revenue_daily (biz_date, ad_slot, data_source);

-- 运营台按时间范围查收益趋势
CREATE INDEX IF NOT EXISTS idx_ad_revenue_daily_date
    ON ad_revenue_daily (biz_date);

COMMENT ON TABLE ad_revenue_daily IS '广告收益日账（腾讯流量主）。刻意不挂 OrderRevenueSplit：广告收入无订单锚点，挂上去会造假订单污染资金对账。金额单位分。';
COMMENT ON COLUMN ad_revenue_daily.data_source IS 'ESTIMATE=publisher_adpos_general 预估（趋势用）；SETTLED=publisher_settlement 结算（收入确认以此为准）';
COMMENT ON COLUMN ad_revenue_daily.ecpm_micros IS '千次曝光收益，单位「微元」（1元=1_000_000微元）。微信返回的 ecpm 本身是「分」，此处放大 100 倍存整数避免浮点';
COMMENT ON COLUMN ad_revenue_daily.income_cents IS '收入，单位分。微信 publisher/stat 的 income 字段本身就是分，未做任何换算';