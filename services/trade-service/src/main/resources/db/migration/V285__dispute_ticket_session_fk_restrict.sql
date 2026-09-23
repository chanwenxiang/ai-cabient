-- 争议工单 → 会话 的外键，原先声明 ON DELETE SET NULL，但 session_id 列是 NOT NULL：
-- 这个组合**永远无法满足** —— 删会话时数据库必然报
--   null value in column "session_id" of relation "dispute_ticket" violates not-null constraint
-- 也就是说「会话删除时把争议单的关联置空」这个设计从未真正生效过。
--
-- 按「拒绝删除 + 如实提示」策略改为 RESTRICT：
--   有争议工单的会话不可硬删除，失败原因从「内部 not-null 违约」变成明确的「存在下游引用」。
-- 争议工单属留痕资料，不应因删会话而丢失关联（更不能被静默抹掉）。
--
-- MIGRATION_REVIEWED: yes
-- TABLES: dispute_ticket (~16 行), shopping_session (~42 行)
-- LOCK_RISK: low
-- ROLLBACK: V286 反向改回 SET NULL（不采纳：该组合永不可满足，回滚没有意义）
-- NOTES: 两表均为小表（16 / 42 行）；ADD CONSTRAINT 需一次全表校验，dev 实测瞬时完成、无锁等待。

ALTER TABLE dispute_ticket
    DROP CONSTRAINT IF EXISTS dispute_ticket_session_id_fkey;

ALTER TABLE dispute_ticket
    ADD CONSTRAINT dispute_ticket_session_id_fkey
        FOREIGN KEY (session_id) REFERENCES shopping_session(session_id)
        ON DELETE RESTRICT;
