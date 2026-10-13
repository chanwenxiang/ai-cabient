-- P3 修复：消费者余额退款申请服务端幂等键。
-- 现状：apply 有 分布式锁 + PENDING 守卫，但自动审批（O6）通过后守卫即释放——
-- 双击/网络重试可对同一意图重复退款。服务端按 (user_id, idem_key) 重放返回原单。
ALTER TABLE balance_refund_request
    ADD COLUMN IF NOT EXISTS idem_key VARCHAR(64);

-- 部分唯一索引：历史行 idem_key 为 NULL 不受约束；同用户同键至多一单。
CREATE UNIQUE INDEX IF NOT EXISTS uk_balance_refund_request_user_idem
    ON balance_refund_request (user_id, idem_key)
    WHERE idem_key IS NOT NULL;

COMMENT ON COLUMN balance_refund_request.idem_key IS
    '客户端幂等键（可选）：同 (user_id, idem_key) 重放返回原单，不重复冻结/退款';
