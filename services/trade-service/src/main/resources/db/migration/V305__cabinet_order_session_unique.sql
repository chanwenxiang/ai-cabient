-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: cabinet_order（核心交易表；行数=累计订单量，生产按业务规模）
-- LOCK_RISK: high
-- ROLLBACK: DROP INDEX uk_cabinet_order_session（不可逆部分仅为发现重复时的处理决策）
-- NOTES: 审计 P1-2——防重复落单此前完全依赖「settle 分布式锁 + findByIdForUpdate + 复查」三重
-- 代码防线，缺 DB 级最后防线；锁租约内基础设施异常叠加时可同会话双订单双扣款。本迁移先
-- 显式检出历史重复（订单为资金记录，禁止像 V301 那样自动置空/合并——重复单如何处置属业务
-- 决策：核实后取消或人工合并，再重跑本迁移），无重复才建唯一索引。非 CONCURRENTLY：
-- 开发库小表短锁可接受；生产低峰执行（对齐 V301 先例，Flyway 社区版事务内不能跑 CONCURRENTLY）。

DO $$
DECLARE
    dupes text;
BEGIN
    SELECT string_agg(session_id, ', ' ORDER BY session_id)
      INTO dupes
      FROM (
        SELECT session_id
          FROM cabinet_order
         GROUP BY session_id
        HAVING count(*) > 1
      ) d;
    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'cabinet_order.session_id 存在历史重复，禁止盲建唯一索引：%', dupes
            USING HINT = '先逐单核实（重复单=同会话双扣款风险）：保留有效单、取消/合并其余，再重跑本迁移';
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_cabinet_order_session
    ON cabinet_order (session_id);

COMMENT ON INDEX uk_cabinet_order_session IS
    '一个开门会话至多一张订单（审计 P1-2）：防重复结算的最后防线，落单链路的锁+复查失效时兜底';
