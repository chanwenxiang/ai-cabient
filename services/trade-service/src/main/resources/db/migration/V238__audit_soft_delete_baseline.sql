-- V238: audit columns, selective soft-delete, weak-ref indexes
-- New columns include Chinese comments (Unicode-escaped for Windows Flyway).

-- ========== created_at / updated_at on hot master & document tables ==========
ALTER TABLE cabinet_order ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
COMMENT ON COLUMN cabinet_order.updated_at IS U&'\66F4\65B0\65F6\95F4';

ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
COMMENT ON COLUMN sku_catalog.updated_at IS U&'\66F4\65B0\65F6\95F4';

ALTER TABLE payment_operation ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
COMMENT ON COLUMN payment_operation.updated_at IS U&'\66F4\65B0\65F6\95F4';

ALTER TABLE supplier ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
COMMENT ON COLUMN supplier.updated_at IS U&'\66F4\65B0\65F6\95F4';

ALTER TABLE warehouse ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
COMMENT ON COLUMN warehouse.updated_at IS U&'\66F4\65B0\65F6\95F4';

ALTER TABLE cabinet_order_line ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
COMMENT ON COLUMN cabinet_order_line.created_at IS U&'\521B\5EFA\65F6\95F4';

-- ========== is_deleted (master / config / ops documents only) ==========
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN user_info.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE device_info ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN device_info.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE merchant ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN merchant.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN sku_catalog.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE repair_ticket ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN repair_ticket.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE announcement ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN announcement.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE coupon_definition ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN coupon_definition.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE supplier ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN supplier.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE warehouse ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN warehouse.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE promotion_activity ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN promotion_activity.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE notification_template ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN notification_template.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE media_asset ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN media_asset.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE ad_campaign ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN ad_campaign.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE site_contract ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN site_contract.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE ops_department ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN ops_department.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE ops_org_node ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN ops_org_node.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE member ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN member.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

ALTER TABLE member_level_rule ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN member_level_rule.is_deleted IS U&'\903B\8F91\5220\9664\FF1Afalse=\672A\5220\9664 true=\5DF2\5220\9664';

CREATE INDEX IF NOT EXISTS idx_user_info_is_deleted ON user_info (is_deleted);
CREATE INDEX IF NOT EXISTS idx_device_info_is_deleted ON device_info (is_deleted);
CREATE INDEX IF NOT EXISTS idx_merchant_is_deleted ON merchant (is_deleted);
CREATE INDEX IF NOT EXISTS idx_sku_catalog_is_deleted ON sku_catalog (is_deleted);
CREATE INDEX IF NOT EXISTS idx_repair_ticket_is_deleted ON repair_ticket (is_deleted);

-- ========== weak-ref indexes ==========
CREATE INDEX IF NOT EXISTS idx_shopping_session_scan_device_id ON shopping_session (scan_device_id);
CREATE INDEX IF NOT EXISTS idx_user_info_register_device_id ON user_info (register_device_id);
CREATE INDEX IF NOT EXISTS idx_repair_ticket_created_by ON repair_ticket (created_by);
CREATE INDEX IF NOT EXISTS idx_approval_instance_submitter_id ON approval_instance (submitter_id);
CREATE INDEX IF NOT EXISTS idx_device_sku_price_updated_by_user_id ON device_sku_price (updated_by_user_id);
CREATE INDEX IF NOT EXISTS idx_announcement_operator_id ON announcement (operator_id);
CREATE INDEX IF NOT EXISTS idx_ad_campaign_created_by ON ad_campaign (created_by);
CREATE INDEX IF NOT EXISTS idx_merchant_replenishment_created_by ON merchant_replenishment_request (created_by);
