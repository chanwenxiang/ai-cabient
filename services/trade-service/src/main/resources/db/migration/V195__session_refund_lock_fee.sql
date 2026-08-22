-- 订单累计退款额、柜机停售原因、提现手续费（分；默认 0=免）
ALTER TABLE cabinet_order
    ADD COLUMN IF NOT EXISTS refunded_cents INT NOT NULL DEFAULT 0;

ALTER TABLE device_info
    ADD COLUMN IF NOT EXISTS sales_lock_reason VARCHAR(200);

ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS fee_cents BIGINT NOT NULL DEFAULT 0;

ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS fee_cents BIGINT NOT NULL DEFAULT 0;

-- 历史全额退款：无累计额时用当前实付兜底（部分退无法精确回填）
UPDATE cabinet_order
SET refunded_cents = total_amount_cents
WHERE status = 'REFUNDED'
  AND COALESCE(refunded_cents, 0) = 0
  AND total_amount_cents > 0;
