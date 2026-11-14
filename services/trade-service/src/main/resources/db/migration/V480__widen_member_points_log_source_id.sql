-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low（varchar(64) → varchar(128) 是二进制兼容的放宽：PG 不重写表、不校验存量，
--            只在取锁那一刻短暂 ACCESS EXCLUSIVE）
-- TABLES: member_points_log.source_id (~47 行，本机 dev 实测 max(length(source_id))=56)
-- NOTES: 2026-11-14 收口 CI build 红（SettlementComboMatrixE2ETest:214 期望 200 得 500）。
--        883b9b1a 把积分扣回的幂等键从「本地自拼」改成「透传 payment 幂等键」，去重源键因此变成
--        `orderId + ":" + idemKey`；而按行部分退款的 idemKey 按 AU-175 带「订单+金额+行集指纹」，
--        最坏形态 = 32(orderId) + 1 + 7("REFUND:") + 32(orderId) + 1 + 金额 + 1 + 指纹 > 64
--        ⇒ PG 22001 `value too long for type character varying(64)`，退款接口整条 500。
--        **放宽而不是截断**：截断会把「同单同额、不同行集」的两笔重新压成同一个键，
--        正是 883b9b1a 要消灭的那一维（第二笔积分永不扣回）。
--        128 的上界来源：order_id 列宽 VARCHAR(32) 的两倍 + 前缀与金额 + 行集指纹裕量。
--        同批夹具：SettlementComboMatrixE2ETest 的按行部分退三面（真库跑，宽度不够必 500）。
ALTER TABLE member_points_log
    ALTER COLUMN source_id TYPE VARCHAR(128);
