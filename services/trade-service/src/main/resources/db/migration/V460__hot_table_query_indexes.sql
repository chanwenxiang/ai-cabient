-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: high
-- TABLES: cabinet_order (dev 44 行/生产按量级评估，见 NOTES)、cabinet_order_line (dev 39 行)、admin_audit_log (dev 606 行)
-- NOTES: cabinet_order / cabinet_order_line 是 HOT_TABLES。Flyway 社区版事务块内不能跑
--        CREATE INDEX CONCURRENTLY（对齐 V301/V305/V332/V379 先例），故本迁移为非并发建索引：
--        建索引持 SHARE 锁——不阻塞读、阻塞写，耗时随表规模线性增长。
--        生产执行建议：低峰窗口；若评估写入阻塞不可接受，可先手工
--        `CREATE INDEX CONCURRENTLY idx_... ON ...` 再 `flyway repair` 后重启（索引名保持一致）。
-- R19-12（四端审计 2026-11-12）：两张核心表在真实查询路径上缺索引，均为全表扫：
--        ① cabinet_order_line 无 order_id 索引 —— 订单详情/件数汇总按 orderId 查
--           （CabinetOrderLineMapper:76/107）；
--        ② cabinet_order 无 (status, created_at) —— 待办/超时单按状态+时间窗扫描
--           （CabinetOrderMapper:124/246 无 LIMIT，UserBehaviorAnalyticsService:63 全量载入窗口订单）；
--        ③ admin_audit_log 无 (target_type, target_id, created_at) —— 事故/订单详情每开一次
--           全表扫（findByTargetTypeAndTargetIdOrderByCreatedAtAsc）。

CREATE INDEX IF NOT EXISTS idx_cabinet_order_line_order_id
    ON cabinet_order_line (order_id);

CREATE INDEX IF NOT EXISTS idx_cabinet_order_status_created
    ON cabinet_order (status, created_at);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_target
    ON admin_audit_log (target_type, target_id, created_at);
