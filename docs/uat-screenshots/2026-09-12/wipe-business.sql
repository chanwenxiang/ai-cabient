-- Full cleanup 2026-09-06 (no IF EXISTS — PG TRUNCATE syntax)

TRUNCATE TABLE ad_campaign_device, promotion_device, line_device,
  ops_device_org, ota_device_report, device_availability_kpi_daily,
  device_data_fee_bill, device_env_reading, device_fault_report,
  device_lifecycle_event, device_ops_event, device_sku_inventory,
  device_sku_lot, device_sku_price, device_slot, device_temp_plan,
  device_temp_plan_entry, device_temperature_reading RESTART IDENTITY CASCADE;

TRUNCATE TABLE device_info RESTART IDENTITY CASCADE;

TRUNCATE TABLE shopping_session, cabinet_order, cabinet_order_line,
  payment_operation, recharge_order, payment_reconciliation,
  payment_platform_bill_line, payment_risk_config, recognition_result,
  order_revenue_split, revenue_share_detail, consumer_preauth_hold,
  balance_refund_request, balance_refund_allocation, merchant_withdraw_request,
  merchant_wallet_ledger, merchant_wallet_account,
  line_wallet_ledger, line_wallet_account, line_commission_daily,
  line_withdraw_request, finance_margin_daily_lock, payscore_order,
  payscore_contract, invoice_request, compensation_task,
  idempotency_key, distributed_transaction RESTART IDENTITY CASCADE;

TRUNCATE TABLE dispute_ticket, dispute_message, ops_exception, risk_event,
  data_discrepancy, data_quality_check, data_change_log RESTART IDENTITY CASCADE;

TRUNCATE TABLE warehouse_movement, warehouse_inventory, warehouse_in_transit,
  warehouse_inbound, warehouse_inbound_line, warehouse_outbound,
  warehouse_outbound_line, supplier_payable, purchase_order,
  purchase_order_line, purchase_return, purchase_return_line,
  approval_task, approval_instance,
  replenishment_route, replenishment_task, replenishment_task_line,
  merchant_replenishment_request, merchant_replenishment_request_line,
  pull_off_task, repair_ticket, repair_ticket_event,
  inventory_movement, inventory_write_off, line_manager, line_promo_task RESTART IDENTITY CASCADE;

TRUNCATE TABLE sku_catalog, sku_vision_mapping, aliyun_category_mapping,
  sku_delist_review, coupon_definition, user_coupon, promotion_activity,
  ad_campaign, ad_campaign_item, ad_play_event, points_redeem_item, media_asset,
  file_attachment, rate_limit_config, rate_limit_record, announcement,
  ota_release, revenue_share_rule, member_level_rule RESTART IDENTITY CASCADE;

TRUNCATE TABLE merchant, merchant_ops_config, merchant_tax_profile,
  merchant_payment_onboarding, merchant_role_template,
  merchant_subscribe_pref, merchant_notify_log,
  site_contract, site_rent_split_rule, site_rent_bill RESTART IDENTITY CASCADE;

TRUNCATE TABLE admin_audit_log, sys_oper_log, notification_log,
  notification_template, user_feedback, data_consistency_record RESTART IDENTITY CASCADE;

BEGIN;
DELETE FROM ops_user_role WHERE user_id <> 100000001;
DELETE FROM ops_user_department WHERE user_id <> 100000001;
DELETE FROM ops_user_merchant WHERE user_id <> 100000001;
DELETE FROM ops_user_device_scope WHERE user_id <> 100000001;
DELETE FROM ops_user_device_scope_pref WHERE user_id <> 100000001;
DELETE FROM ops_user_route_scope WHERE user_id <> 100000001;
DELETE FROM ops_2fa_recovery_code WHERE user_id <> 100000001;
DELETE FROM user_coupon WHERE user_id NOT IN (0, 100000001);
DELETE FROM user_feedback WHERE user_id NOT IN (0, 100000001);
DELETE FROM user_login_log WHERE user_id NOT IN (0, 100000001);
DELETE FROM user_notify_pref WHERE user_id NOT IN (0, 100000001);
DELETE FROM user_realname_auth WHERE user_id NOT IN (0, 100000001);
DELETE FROM user_blacklist;
DELETE FROM member_points_log;
DELETE FROM member WHERE user_id NOT IN (0, 100000001);
DELETE FROM wechat_binding;
DELETE FROM consumer_preauth_hold;
DELETE FROM sms_verification_code;
DELETE FROM phone_verify_log;
DELETE FROM user_account WHERE user_id NOT IN (0, 100000001);
DELETE FROM user_info WHERE user_id NOT IN (0, 100000001);
COMMIT;

ANALYZE;

SELECT 'user_info' AS t, count(*)::text AS c FROM user_info
UNION ALL SELECT 'device_info', count(*)::text FROM device_info
UNION ALL SELECT 'merchant', count(*)::text FROM merchant
UNION ALL SELECT 'shopping_session', count(*)::text FROM shopping_session
UNION ALL SELECT 'sku_catalog', count(*)::text FROM sku_catalog
UNION ALL SELECT 'ops_permission', count(*)::text FROM ops_permission
UNION ALL SELECT 'sys_dict_data', count(*)::text FROM sys_dict_data
UNION ALL SELECT 'scheduled_task', count(*)::text FROM scheduled_task;

SELECT user_id, phone_number, name FROM user_info ORDER BY user_id;
