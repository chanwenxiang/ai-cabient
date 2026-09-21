-- V283: D1 会员储值等级 —— 等级规则新增「累计净充值」门槛
-- MIGRATION_KIND: backfill
-- NOTES: 加列 + 补回缺失的等级行 + 回填 min_recharge；全部幂等，不覆盖运营已调值
-- TABLES: member_level_rule (~4)
-- =====================================================================
-- 背景（D1 决策：F5 储值等级选 A —— 复用既有等级框架，不新建第二套体系）
--   竞品通行解是「储值即升级」（累计充值额达标 → 升级），本项目原判定只看累计消费。
--   本迁移只给规则表补一列「储值口径门槛」，判定逻辑在 MemberService 内做「取高」。
--
-- 为什么同时补回 4 行等级规则：
--   V79/V99 曾用 `INSERT ... ON CONFLICT DO NOTHING` 种过这 4 行，但现网（dev）实测
--   `select count(*) from member_level_rule` = 0 ⇒ 规则表被清空过，导致
--   calculateMemberLevel 恒落 NORMAL、会员价折扣与升级体系整体失效。
--   储值门槛无处可挂，故一并幂等补回（已存在的行完全不动）。
--
-- 为什么不加 seed_env 守卫（与 V244 不同）：
--   等级规则是**功能必需配置**而非 demo 数据 —— 表为空时等级体系就是坏的。
--   口径与 V79/V99 原始种子一致。
-- =====================================================================

ALTER TABLE member_level_rule
    ADD COLUMN IF NOT EXISTS min_recharge DECIMAL(12,2);

COMMENT ON COLUMN member_level_rule.min_recharge IS '升到该等级所需的累计净充值（元）；NULL=该档无储值路径';

-- 1) 补回缺失的等级行（幂等；已存在的行完全不动，运营调过的名字/门槛一律保留）
INSERT INTO member_level_rule (level_code, level_name, min_spent, max_spent, sortorder, status) VALUES
('NORMAL',   '普通会员', 0,     1000,  1, 'ACTIVE'),
('SILVER',   '银卡会员', 1000,  5000,  2, 'ACTIVE'),
('GOLD',     '金卡会员', 5000,  10000, 3, 'ACTIVE'),
('PLATINUM', '白金会员', 10000, NULL,  4, 'ACTIVE')
ON CONFLICT (level_code) DO NOTHING;

-- 2) 回填储值门槛 = 消费门槛的一半（0/1000/5000/10000 → 0/500/2000/5000），运营台可调。
--    只填 NULL：已调过的值一律不动（幂等，可安全重放）。
UPDATE member_level_rule SET min_recharge = 0    WHERE level_code = 'NORMAL'   AND min_recharge IS NULL;
UPDATE member_level_rule SET min_recharge = 500  WHERE level_code = 'SILVER'   AND min_recharge IS NULL;
UPDATE member_level_rule SET min_recharge = 2000 WHERE level_code = 'GOLD'     AND min_recharge IS NULL;
UPDATE member_level_rule SET min_recharge = 5000 WHERE level_code = 'PLATINUM' AND min_recharge IS NULL;
