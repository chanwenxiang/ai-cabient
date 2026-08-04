-- 商户功能包（平台租户能力）：与 RBAC 正交
-- field=现场作业 / biz=经营工具 / team=团队与设置
-- 细粒度写权限仍用 allow_merchant_pricing_edit / allow_merchant_planogram_edit

ALTER TABLE merchant
    ADD COLUMN IF NOT EXISTS pack_field_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS pack_biz_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS pack_team_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN merchant.pack_field_enabled IS '功能包：现场作业（柜机/补货/待办/库存）';
COMMENT ON COLUMN merchant.pack_biz_enabled IS '功能包：经营工具（订单/结算/定价/争议/分析）';
COMMENT ON COLUMN merchant.pack_team_enabled IS '功能包：团队与设置';
