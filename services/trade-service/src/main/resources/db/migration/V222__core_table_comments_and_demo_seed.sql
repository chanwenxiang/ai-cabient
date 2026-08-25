-- V222: Core table/column comments + idempotent demo seed for empty admin modules.
-- Comments: 15 transactional tables. Seed: only WHERE NOT EXISTS (safe on populated demo DBs).

-- ---------------------------------------------------------------------------
-- Part 1: COMMENT ON core tables
-- ---------------------------------------------------------------------------

COMMENT ON TABLE shopping_session IS '购物会话：扫码开门→购物→识别→结算全链路';
COMMENT ON TABLE device_info IS '智能柜设备主数据（点位、商户、生命周期）';
COMMENT ON TABLE cabinet_order IS '关门结算订单头';
COMMENT ON TABLE cabinet_order_line IS '订单明细行（SKU、数量、货道）';
COMMENT ON TABLE sku_catalog IS '商品 SKU 主数据';
COMMENT ON TABLE merchant IS '商户/租户主数据';
COMMENT ON TABLE order_revenue_split IS '订单微信分账记录（平台/商户分成）';
COMMENT ON TABLE warehouse IS '中心仓/前置仓主数据';
COMMENT ON TABLE warehouse_inventory IS '仓库批次库存（SKU+批次+效期）';
COMMENT ON TABLE device_sku_inventory IS '柜内 SKU 汇总库存（按设备+SKU）';
COMMENT ON TABLE promotion_activity IS '营销活动（满减/新客/积分等）';
COMMENT ON TABLE coupon_definition IS '优惠券定义（面额、门槛、发行量）';
COMMENT ON TABLE user_coupon IS '用户持券实例（领取/使用/过期）';
COMMENT ON TABLE dispute_ticket IS '争议/售后工单（识别异议、退款协商）';
COMMENT ON TABLE payment_operation IS '支付流水（扣款、退款、充值，幂等）';

COMMENT ON COLUMN shopping_session.state IS 'CREATED|OPENING|SHOPPING|RECOGNIZING|WAITING_UPLOAD|SETTLING|COMPLETED|DISPUTED|FAILED|CANCELLED';
COMMENT ON COLUMN shopping_session.order_id IS '关联结算订单；会话完成后写入';
COMMENT ON COLUMN shopping_session.preferred_coupon_id IS '开门前用户指定优惠券；结算优先用此券，无效则回退自动择优';

COMMENT ON COLUMN device_info.lifecycle_status IS 'INBOUND|IDLE|DEPLOYED|RETURNING|RETIRED';
COMMENT ON COLUMN device_info.coop_mode IS 'SELF|FRANCHISE|CONSIGN';
COMMENT ON COLUMN device_info.merchant_id IS '所属商户；直营/加盟柜归属';

COMMENT ON COLUMN cabinet_order.status IS 'PAID|REFUNDED|PARTIAL_REFUND|CLOSED 等订单状态';
COMMENT ON COLUMN cabinet_order.pay_channel IS 'BALANCE|WECHAT|ALIPAY|PAYSCORE 等支付渠道';
COMMENT ON COLUMN cabinet_order.coupon_id IS '本单使用的 user_coupon.coupon_id';

COMMENT ON COLUMN cabinet_order_line.slot_id IS '出货货道编码，对应 device_slot.slot_code';
COMMENT ON COLUMN sku_catalog.status IS 'ACTIVE|INACTIVE|DELISTED 等上架状态';

COMMENT ON COLUMN merchant.status IS 'ACTIVE|SUSPENDED|CLOSED 等商户状态';
COMMENT ON COLUMN order_revenue_split.status IS 'ACCRUED|PENDING|SETTLED|FAILED 等分账状态';

COMMENT ON COLUMN promotion_activity.status IS 'DRAFT|RUNNING|PAUSED|ENDED';
COMMENT ON COLUMN promotion_activity.activity_type IS 'NEW_USER|DISCOUNT|POINTS 等活动类型';

COMMENT ON COLUMN coupon_definition.status IS 'ACTIVE|INACTIVE 券定义状态';
COMMENT ON COLUMN coupon_definition.coupon_type IS 'FIXED|PERCENT|FREE 等券类型';

COMMENT ON COLUMN user_coupon.status IS 'UNUSED|USED|EXPIRED 用户券状态';

COMMENT ON COLUMN dispute_ticket.status IS 'OPEN|IN_PROGRESS|RESOLVED|CLOSED';
COMMENT ON COLUMN dispute_ticket.category IS 'RECOGNITION|REFUND|OTHER 争议类别';

COMMENT ON COLUMN payment_operation.operation_type IS 'PAY|REFUND|RECHARGE 等操作类型';
COMMENT ON COLUMN payment_operation.status IS 'SUCCESS|FAILED|PENDING 流水状态';
COMMENT ON COLUMN payment_operation.idempotency_key IS '幂等键，防止重复扣款/退款';

COMMENT ON TABLE merchant_payment_onboarding IS '商户支付进件（微信/支付宝/支付分渠道）';
COMMENT ON COLUMN merchant_payment_onboarding.status IS 'DRAFT|SUBMITTED|ACTIVE|REJECTED';

-- ---------------------------------------------------------------------------
-- Part 2: Demo field backfill (nullable display fields)
-- ---------------------------------------------------------------------------

UPDATE merchant
SET contact_phone = '0755-88880001'
WHERE merchant_id = 'MCH-DEFAULT'
  AND (contact_phone IS NULL OR contact_phone = '');

UPDATE merchant
SET contact_phone = '0755-88880002'
WHERE merchant_id = 'MCH-OTHER'
  AND (contact_phone IS NULL OR contact_phone = '');

-- ---------------------------------------------------------------------------
-- Part 3: Idempotent demo seed (empty modules only)
-- ---------------------------------------------------------------------------

-- 3.1 商户支付进件
INSERT INTO merchant_payment_onboarding (merchant_id, channel, status, external_ref, note)
SELECT 'MCH-DEFAULT', 'WECHAT', 'SUBMITTED', 'demo-wx-submitted', '演示：微信进件审核中'
WHERE NOT EXISTS (
    SELECT 1 FROM merchant_payment_onboarding
    WHERE merchant_id = 'MCH-DEFAULT' AND channel = 'WECHAT'
);

INSERT INTO merchant_payment_onboarding (merchant_id, channel, status, external_mch_id, note)
SELECT 'MCH-DEFAULT', 'ALIPAY', 'ACTIVE', 'demo-alipay-mch-001', '演示：支付宝进件已通过'
WHERE NOT EXISTS (
    SELECT 1 FROM merchant_payment_onboarding
    WHERE merchant_id = 'MCH-DEFAULT' AND channel = 'ALIPAY'
);

INSERT INTO merchant_payment_onboarding (merchant_id, channel, status, note)
SELECT 'MCH-OTHER', 'WECHAT', 'DRAFT', '演示：待提交微信进件'
WHERE NOT EXISTS (
    SELECT 1 FROM merchant_payment_onboarding
    WHERE merchant_id = 'MCH-OTHER' AND channel = 'WECHAT'
);

-- 3.2 风控黑名单（演示账号，带过期时间）
INSERT INTO user_blacklist (user_id, reason, source, expires_at)
SELECT 10002, '演示：疑似异常下单频率（已自动解除）', 'AUTO', NOW() + INTERVAL '30 days'
WHERE EXISTS (SELECT 1 FROM user_info WHERE user_id = 10002)
  AND NOT EXISTS (SELECT 1 FROM user_blacklist WHERE user_id = 10002);

-- 3.3 营销活动设备范围
INSERT INTO promotion_device (activity_id, device_id)
SELECT 1, 'CAB-001'
WHERE EXISTS (SELECT 1 FROM promotion_activity WHERE activity_id = 1)
  AND EXISTS (SELECT 1 FROM device_info WHERE device_id = 'CAB-001')
  AND NOT EXISTS (SELECT 1 FROM promotion_device WHERE activity_id = 1 AND device_id = 'CAB-001');

INSERT INTO promotion_device (activity_id, device_id)
SELECT 2, 'CAB-001'
WHERE EXISTS (SELECT 1 FROM promotion_activity WHERE activity_id = 2)
  AND NOT EXISTS (SELECT 1 FROM promotion_device WHERE activity_id = 2 AND device_id = 'CAB-001');

-- 3.4 设备生命周期事件
INSERT INTO device_lifecycle_event (device_id, from_status, to_status, action, operator_id, remark)
SELECT 'CAB-001', NULL, 'INBOUND', 'INBOUND', 100000001, '演示：设备入库'
WHERE NOT EXISTS (
    SELECT 1 FROM device_lifecycle_event
    WHERE device_id = 'CAB-001' AND action = 'INBOUND'
);

INSERT INTO device_lifecycle_event (device_id, from_status, to_status, action, operator_id, remark)
SELECT 'CAB-001', 'INBOUND', 'DEPLOYED', 'DEPLOY', 100000001, '演示：部署上线（测试柜-001）'
WHERE NOT EXISTS (
    SELECT 1 FROM device_lifecycle_event
    WHERE device_id = 'CAB-001' AND action = 'DEPLOY'
);

-- 3.5 识别结果（补全无识别记录的已完成会话，最多 5 条）
INSERT INTO recognition_result (task_id, session_id, items, overall_confidence, fusion_mode, model_version, need_review)
SELECT
    'demo-rec-' || s.session_id,
    s.session_id,
    jsonb_build_array(jsonb_build_object('skuId', 'SKU-DEMO-001', 'skuName', '可口可乐 330ml', 'qty', 1, 'confidence', 0.92)),
    0.92,
    'VISION',
    'demo-v1',
    false
FROM shopping_session s
WHERE s.state IN ('COMPLETED', 'SETTLING')
  AND NOT EXISTS (SELECT 1 FROM recognition_result r WHERE r.session_id = s.session_id)
ORDER BY s.created_at DESC
LIMIT 5;

-- 3.6 分润明细（为有金额订单补演示行）
INSERT INTO revenue_share_detail (order_id, rule_type, target_id, share_amount_cents, status)
SELECT
    o.order_id,
    'PLATFORM',
    'PLATFORM',
    GREATEST(1, o.total_amount_cents / 20),
    'PENDING'
FROM cabinet_order o
WHERE o.total_amount_cents > 0
  AND o.status = 'PAID'
  AND NOT EXISTS (SELECT 1 FROM revenue_share_detail d WHERE d.order_id = o.order_id)
ORDER BY o.created_at DESC
LIMIT 5;

-- 3.7 争议消息（为首个 OPEN 工单补一条运营回复）
INSERT INTO dispute_message (ticket_id, author_type, author_id, body)
SELECT
    t.ticket_id,
    'OPS',
    100000001,
    '演示：已收到您的反馈，正在核对识别视频，请稍候。'
FROM dispute_ticket t
WHERE t.status = 'OPEN'
  AND NOT EXISTS (SELECT 1 FROM dispute_message m WHERE m.ticket_id = t.ticket_id)
ORDER BY t.created_at DESC
LIMIT 1;

-- 3.8 通知日志（仅当整表为空时灌入演示数据）
INSERT INTO notification_log (template_code, channel, audience, user_id, title, body, biz_type, biz_id, status)
SELECT 'order_paid', 'IN_APP', 'CONSUMER', 10001, '订单支付成功', '您的演示订单已支付，感谢惠顾。', 'ORDER', 'demo-order', 'SENT'
WHERE NOT EXISTS (SELECT 1 FROM notification_log LIMIT 1);

INSERT INTO notification_log (template_code, channel, audience, merchant_id, title, body, biz_type, status)
SELECT 'points_expiring', 'IN_APP', 'MERCHANT', 'MCH-DEFAULT', '积分即将过期提醒', '演示：门店会员积分即将过期，请关注。', 'POINTS', 'SENT'
WHERE (SELECT COUNT(*) FROM notification_log) <= 1;

-- 3.9 发票申请（仅当整表为空）
INSERT INTO invoice_request (order_id, user_id, title, tax_no, email, amount_cents, status)
SELECT o.order_id, o.user_id, '深圳演示科技有限公司', '91440300MA5DEMO001', 'finance@demo.local', o.total_amount_cents, 'PENDING'
FROM cabinet_order o
WHERE o.total_amount_cents > 0
  AND NOT EXISTS (SELECT 1 FROM invoice_request LIMIT 1)
ORDER BY o.created_at DESC
LIMIT 1;

-- 3.10 余额退款申请（仅当整表为空）
INSERT INTO balance_refund_request (request_no, user_id, amount_cents, status, reason)
SELECT 'BR-DEMO-001', 10001, 500, 'PENDING_REVIEW', '演示：用户申请退回未使用余额'
WHERE NOT EXISTS (SELECT 1 FROM balance_refund_request LIMIT 1);

-- 3.11 支付对账（仅当整表为空）
INSERT INTO payment_reconciliation (recon_date, channel, platform_total, ledger_total, diff_cents, matched_count, unmatched_count, status, completed_at)
SELECT CURRENT_DATE - 1, 'WECHAT', 125000, 125000, 0, 48, 0, 'MATCHED', NOW()
WHERE NOT EXISTS (SELECT 1 FROM payment_reconciliation LIMIT 1);

INSERT INTO payment_reconciliation (recon_date, channel, platform_total, ledger_total, diff_cents, matched_count, unmatched_count, status, detail)
SELECT CURRENT_DATE - 2, 'BALANCE', 89000, 88500, 500, 12, 1, 'MISMATCH', '{"demo": true, "note": "演示差异账"}'::jsonb
WHERE (SELECT COUNT(*) FROM payment_reconciliation) <= 1;

-- 3.12 选品淘汰评审（仅当整表为空）
INSERT INTO sku_delist_review (sku_id, review_status, performance_level, sales_qty, revenue_cents, stock_days, action_type, reason)
SELECT 'SKU-APPLE-001', 'PENDING', 'SLOW', 2, 600, 45, 'WATCH', '演示：动销偏低，建议观察或汰换'
WHERE EXISTS (SELECT 1 FROM sku_catalog WHERE sku_id = 'SKU-APPLE-001')
  AND NOT EXISTS (SELECT 1 FROM sku_delist_review LIMIT 1);

-- 3.13 广告素材/活动/播放（仅当 ad_campaign 为空）
INSERT INTO media_asset (title, asset_type, storage_uri, duration_seconds, status, uploaded_by)
SELECT '演示屏保视频', 'VIDEO', 'minio://ad/demo-screen.mp4', 15, 'ACTIVE', 100000001
WHERE NOT EXISTS (SELECT 1 FROM media_asset WHERE title = '演示屏保视频');

INSERT INTO ad_campaign (name, status, device_scope, start_at, end_at, created_by)
SELECT '演示屏保投放', 'RUNNING', 'SPECIFIC', NOW() - INTERVAL '7 days', NOW() + INTERVAL '30 days', 100000001
WHERE NOT EXISTS (SELECT 1 FROM ad_campaign WHERE name = '演示屏保投放');

INSERT INTO ad_campaign_item (campaign_id, asset_id, sort_order)
SELECT c.campaign_id, a.asset_id, 0
FROM ad_campaign c
CROSS JOIN media_asset a
WHERE c.name = '演示屏保投放'
  AND a.title = '演示屏保视频'
  AND NOT EXISTS (
      SELECT 1 FROM ad_campaign_item i
      WHERE i.campaign_id = c.campaign_id AND i.asset_id = a.asset_id
  );

INSERT INTO ad_campaign_device (campaign_id, device_id)
SELECT c.campaign_id, 'CAB-001'
FROM ad_campaign c
WHERE c.name = '演示屏保投放'
  AND NOT EXISTS (
      SELECT 1 FROM ad_campaign_device d
      WHERE d.campaign_id = c.campaign_id AND d.device_id = 'CAB-001'
  );

INSERT INTO ad_play_event (campaign_id, device_id, asset_id, event_type)
SELECT c.campaign_id, 'CAB-001', a.asset_id, 'IMPRESSION'
FROM ad_campaign c
CROSS JOIN media_asset a
WHERE c.name = '演示屏保投放'
  AND a.title = '演示屏保视频'
  AND NOT EXISTS (SELECT 1 FROM ad_play_event LIMIT 1);

-- 3.14 用户反馈（仅当整表为空）
INSERT INTO user_feedback (user_id, feedback_type, content, device_id, rating, status)
SELECT 10001, 'COMPLAINT', '演示：柜门关闭较慢，希望优化。', 'CAB-001', 3, 'PENDING'
WHERE NOT EXISTS (SELECT 1 FROM user_feedback LIMIT 1);

INSERT INTO user_feedback (user_id, feedback_type, content, device_id, rating, status, handler_id, reply, handled_at)
SELECT 10001, 'SUGGESTION', '演示：建议增加常温饮料品类。', 'CAB-001', 4, 'REPLIED', 100000001, '感谢建议，已记录选品需求。', NOW()
WHERE (SELECT COUNT(*) FROM user_feedback) <= 1;

-- 3.15 采购单（仅当整表为空）
INSERT INTO purchase_order (supplier_id, warehouse_id, status, ref_no, operator_id, notes)
SELECT 'SUP-DEMO-001', 'WH-DEMO-001', 'RECEIVED', 'PO-DEMO-001', 100000001, '演示：首批补货采购'
WHERE NOT EXISTS (SELECT 1 FROM purchase_order LIMIT 1);

INSERT INTO purchase_order_line (purchase_order_id, sku_id, batch_no, expiry_date, ordered_qty, received_qty, unit_cost_cents, quality_status)
SELECT po.purchase_order_id, 'SKU-DEMO-001', 'BATCH-DEMO-001', CURRENT_DATE + 180, 120, 120, 180, 'PASSED'
FROM purchase_order po
WHERE po.ref_no = 'PO-DEMO-001'
  AND NOT EXISTS (
      SELECT 1 FROM purchase_order_line l
      WHERE l.purchase_order_id = po.purchase_order_id
  );
