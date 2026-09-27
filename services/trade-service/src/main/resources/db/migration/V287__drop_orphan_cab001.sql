-- V287: remove orphan demo cabinet CAB-001 (never use as shopping/demo path).
-- MIGRATION_KIND: backfill
-- Keeps real demo device 330449777078. Safe if CAB-001 already absent.

-- RESTRICT / NO ACTION children (must delete before device_info)
DELETE FROM device_temperature_reading WHERE device_id = 'CAB-001';
DELETE FROM device_ops_event WHERE device_id = 'CAB-001';
DELETE FROM device_lifecycle_event WHERE device_id = 'CAB-001';
DELETE FROM device_sku_price WHERE device_id = 'CAB-001';
DELETE FROM merchant_replenishment_request WHERE device_id = 'CAB-001';
DELETE FROM promotion_device WHERE device_id = 'CAB-001';
DELETE FROM ad_play_event WHERE device_id = 'CAB-001';
DELETE FROM device_fault_report WHERE device_id = 'CAB-001';
DELETE FROM inventory_movement WHERE device_id = 'CAB-001';
DELETE FROM inventory_write_off WHERE device_id = 'CAB-001';
DELETE FROM line_commission_daily WHERE device_id = 'CAB-001';
DELETE FROM pull_off_task WHERE device_id = 'CAB-001';
DELETE FROM repair_ticket WHERE device_id = 'CAB-001';
DELETE FROM replenishment_task_line WHERE task_id IN (SELECT task_id FROM replenishment_task WHERE device_id = 'CAB-001');
DELETE FROM replenishment_task WHERE device_id = 'CAB-001';
DELETE FROM warehouse_in_transit WHERE device_id = 'CAB-001';
DELETE FROM order_revenue_split WHERE device_id = 'CAB-001';
DELETE FROM cabinet_order_line WHERE order_id IN (SELECT order_id FROM cabinet_order WHERE device_id = 'CAB-001');
DELETE FROM cabinet_order WHERE device_id = 'CAB-001';
DELETE FROM shopping_session WHERE device_id = 'CAB-001';

-- Soft-ref tables without CASCADE / SET NULL FK
DELETE FROM device_data_fee_bill WHERE device_id = 'CAB-001';
DELETE FROM site_rent_bill WHERE device_id = 'CAB-001';

-- SET NULL FKs (explicit clear for clarity)
UPDATE user_feedback SET device_id = NULL WHERE device_id = 'CAB-001';
UPDATE user_coupon SET device_id = NULL WHERE device_id = 'CAB-001';
UPDATE ops_exception SET device_id = NULL WHERE device_id = 'CAB-001';
UPDATE risk_event SET device_id = NULL WHERE device_id = 'CAB-001';
UPDATE warehouse_outbound_line SET device_id = NULL WHERE device_id = 'CAB-001';

-- CASCADE children (slot/inventory/lot/ota/…) follow with device_info delete
DELETE FROM device_info WHERE device_id = 'CAB-001';
