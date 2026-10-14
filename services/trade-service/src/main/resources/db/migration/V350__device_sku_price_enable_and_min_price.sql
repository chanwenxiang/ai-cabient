-- COMPETITOR_REF: CB-037
-- MIGRATION_KIND: backfill（权限种子随迁移一次性登记）
-- 单柜（点位）差异化定价：admin 补设备级定价入口的三块地基（2026-10-14 拍板）
--
-- 背景（CB-037）：丰e足食/友宝均为点位级定价（办公平价、酒店/棋牌室溢价 30%+；
-- 友宝支持按台改价促销），旧系统 easygo 价格就挂在货道上（ego_machine_aisle_info.
-- aisle_goods_price）。新系统 device_sku_price 覆盖价机制 V41 已建，本次：
-- ① 覆盖价「启用/停用」开关（用户拍板语义 C：预设价可不立即生效，行内开关择机启用）
-- ② SKU 显式最低限价（用户 2026-10-14 否决「不低于进货成本」硬下限：时段/清仓折扣
--    本来就能打穿成本，成本线只挡手设价不自洽；改为 min_price_cents 显式限价，
--    NULL=不限仅要求 >0，支持清仓/引流打折；低于成本由前端提醒、不阻断）
-- ③ admin 定价权限点（对称商户端 merchant:pricing:view/edit）

ALTER TABLE device_sku_price ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS min_price_cents INT;

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:sku:list' LIMIT 1),
       'ops:pricing:view', '商品定价查看', 'C', '/devices', 30, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:pricing:view');

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:pricing:view' LIMIT 1),
       'ops:pricing:edit', '商品定价修改', 'F', NULL, 31, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:pricing:edit');

-- 超管/运营：查看+改价；只读：仅查看
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM (VALUES (1), (2)) AS r(role_id)
JOIN ops_permission p ON p.perm_code IN ('ops:pricing:view', 'ops:pricing:edit')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 5, permission_id FROM ops_permission WHERE perm_code = 'ops:pricing:view'
ON CONFLICT DO NOTHING;
