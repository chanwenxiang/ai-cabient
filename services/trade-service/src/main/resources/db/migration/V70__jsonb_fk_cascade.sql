-- P0: JSONB 字段修正 + 外键级联规则

-- 1. shopping_session 视频/重力字段改为 JSONB
ALTER TABLE shopping_session
    ALTER COLUMN video_clips TYPE JSONB USING video_clips::jsonb,
    ALTER COLUMN gravity_deltas TYPE JSONB USING gravity_deltas::jsonb;

-- 2. device_info.merchant_id: 删商户时置空
ALTER TABLE device_info
    DROP CONSTRAINT IF EXISTS device_info_merchant_id_fkey,
    ADD CONSTRAINT device_info_merchant_id_fkey
        FOREIGN KEY (merchant_id) REFERENCES merchant(merchant_id)
        ON DELETE SET NULL;

-- 3. 争议工单: 会话删除时级联置空（争议可独立存查）
ALTER TABLE dispute_ticket
    DROP CONSTRAINT IF EXISTS dispute_ticket_session_id_fkey,
    ADD CONSTRAINT dispute_ticket_session_id_fkey
        FOREIGN KEY (session_id) REFERENCES shopping_session(session_id)
        ON DELETE SET NULL;

-- 4. 识别结果: 会话删除时级联删除
ALTER TABLE recognition_result
    DROP CONSTRAINT IF EXISTS recognition_result_session_id_fkey,
    ADD CONSTRAINT recognition_result_session_id_fkey
        FOREIGN KEY (session_id) REFERENCES shopping_session(session_id)
        ON DELETE CASCADE;

-- 5. 订单: 会话删除时级联删除
ALTER TABLE cabinet_order
    DROP CONSTRAINT IF EXISTS cabinet_order_session_id_fkey,
    ADD CONSTRAINT cabinet_order_session_id_fkey
        FOREIGN KEY (session_id) REFERENCES shopping_session(session_id)
        ON DELETE CASCADE;

-- 6. 订单行: 订单删除时级联删除
ALTER TABLE cabinet_order_line
    DROP CONSTRAINT IF EXISTS cabinet_order_line_order_id_fkey,
    ADD CONSTRAINT cabinet_order_line_order_id_fkey
        FOREIGN KEY (order_id) REFERENCES cabinet_order(order_id)
        ON DELETE CASCADE;

-- 7. 商户/角色关联: 商户删除时级联
ALTER TABLE ops_user_merchant
    DROP CONSTRAINT IF EXISTS ops_user_merchant_merchant_id_fkey,
    ADD CONSTRAINT ops_user_merchant_merchant_id_fkey
        FOREIGN KEY (merchant_id) REFERENCES merchant(merchant_id)
        ON DELETE CASCADE;

-- 8. 支付操作: 订单删除时级联删除（先清理历史脏数据）
DELETE FROM payment_operation po
WHERE po.order_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cabinet_order co WHERE co.order_id = po.order_id);

ALTER TABLE payment_operation
    DROP CONSTRAINT IF EXISTS payment_operation_order_id_fkey,
    ADD CONSTRAINT payment_operation_order_id_fkey
        FOREIGN KEY (order_id) REFERENCES cabinet_order(order_id)
        ON DELETE CASCADE;

-- 9. 灰度 ETL / 前值提醒：分析前清理无效会话
ANALYZE shopping_session;
ANALYZE device_info;
