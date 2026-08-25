-- V168: 消息触达渠道扩展 + 会员等级规则后台管理（原 V164，因与既有 V164 撞号改名）

-- 1) 通知模板渠道（逗号分隔：IN_APP / WECHAT_SUBSCRIBE / SMS）
ALTER TABLE notification_template
    ADD COLUMN IF NOT EXISTS channels VARCHAR(64) NOT NULL DEFAULT 'IN_APP';

UPDATE notification_template
SET channels = 'IN_APP,WECHAT_SUBSCRIBE,SMS'
WHERE template_code IN ('order_paid', 'recharge_success', 'replenishment_assigned', 'merchant_settlement');

UPDATE notification_template
SET channels = 'IN_APP,WECHAT_SUBSCRIBE'
WHERE template_code IN ('coupon_expiring', 'dispute_resolved');

-- 2) 会员等级规则后台权限
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
VALUES
    (610, 402, 'ops:member-level:list', '会员等级规则', 'C', '/member-levels', 105, 'ACTIVE'),
    (611, 610, 'ops:member-level:edit', '会员等级规则编辑', 'F', NULL, 1, 'ACTIVE')
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:member-level:list', 'ops:member-level:edit')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission
WHERE perm_code = 'ops:member-level:list'
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
