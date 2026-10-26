-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: jiangyi_training_ticket (0~百行)
-- COMPETITOR_REF: CB-023
-- 批量触发学习（一次 §4.4.3 调用、多个 SKU 票据）需要多张 ticket 共享同一个 finishNotifyId：
-- 厂商侧学习是集合粒度（请求体无商品参数，见台账「集合粒度实锤」），所以「一次学习要覆盖哪些
-- 我方商品」只能在回调扇出时逐张交叉验证。原 uk(finish_notify_id) 会挡住第二张票据。
-- NOTES: 只替换唯一约束为普通索引；表很小。finishNotifyId 仍是我方一次性随机凭据，
--        回调按「notifyId 命中 + 每行 PENDING CAS」消费，防重放语义不变。
-- ROLLBACK: DROP INDEX IF EXISTS idx_jiangyi_training_ticket_notify;
--           ALTER TABLE jiangyi_training_ticket
--               ADD CONSTRAINT uk_jiangyi_training_notify UNIQUE (finish_notify_id);
--           -- 回滚前须确认没有同一 finish_notify_id 的多行票据
ALTER TABLE jiangyi_training_ticket DROP CONSTRAINT uk_jiangyi_training_notify;
CREATE INDEX idx_jiangyi_training_ticket_notify
    ON jiangyi_training_ticket (finish_notify_id);
COMMENT ON TABLE jiangyi_training_ticket IS
    '将邑学习触发凭据（CB-023）：finishNotifyId 一次性凭据；一次 §4.4.3 调用可扇出多张 SKU 票据共享它，回调按通知 id 命中后逐张 CAS';
