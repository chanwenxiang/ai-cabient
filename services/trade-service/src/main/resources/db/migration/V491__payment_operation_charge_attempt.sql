-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes（payment_operation 加可空列：扣款尝试渠道/实际扣款额，供 UNKNOWN 对账）
-- COMPETITOR_REF: CB-061（退款流水逐笔台账；扣款尝试同样留痕到笔）
-- B1-B2（2026-11-16 拍板 A）：CHARGE_PENDING 痕迹补 attempted_channel + charge_cents，
-- 让 ChargePendingReconciler 能按渠道查单、按实际差额补收口，而不是拿订单总额猜。
-- 旧行两列为 NULL：不尝试自动收口，仍走 60 分钟告警人工处置。
-- LOCK_RISK: low（仅加可空列，无默认值回写）
-- TABLES: payment_operation

ALTER TABLE payment_operation ADD COLUMN IF NOT EXISTS attempted_channel VARCHAR(32) NULL;
ALTER TABLE payment_operation ADD COLUMN IF NOT EXISTS charge_cents INTEGER NULL;
