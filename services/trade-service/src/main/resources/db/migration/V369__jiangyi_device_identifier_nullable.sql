-- COMPETITOR_REF: CB-022
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: jiangyi_device（仅改列约束，不动数据；行数级别~百级）
-- LOCK_RISK: low（ALTER COLUMN ... DROP NOT NULL 只改目录，不重写表；表本身近空）
-- ROLLBACK: 回滚需先清 identifier IS NULL 行，再 ADD CONSTRAINT ... NOT NULL（上线初期 0 行可逆）
-- NOTES: V369（审计 AU-133）：identifier 改为可空，与一期入驻状态机对齐。
-- V332 把 identifier 定成 NOT NULL，但 identifier 是**第 2 步**才产生的外部事实：
-- register（第 1 步）只登记 deviceId/deviceSn/modelName 并置 status=UNBOUND，
-- 全仓 setIdentifier 仅出现在 bindDomain（需调将邑真租户 setDomain/getIdentifier）。
-- ⇒ 后台「登记将邑设备」恒 500（2026-10-13 C-admin F1 实测实锤：
-- null value in column "identifier" violates not-null constraint，traceId 9cfd63e2d97f9ed6）。
-- 修法取「列可空」而非「占位串」：uk_jiangyi_device_identifier 是唯一约束，
-- 占位串会让第二台 UNBOUND 设备直接撞约束；Postgres 唯一约束允许多行 NULL。
-- 安全面不变：所有消费方（gateway 五处 + JiangyiDeviceDirectory/JiangyiGatherService/
-- JiangyiModelSyncService）都以 status=BOUND 为准入门，JiangyiGatherService:77 已显式
-- 拒绝空 identifier ⇒ UNBOUND 的空值不可能被拿去开门或签发路由。

ALTER TABLE jiangyi_device ALTER COLUMN identifier DROP NOT NULL;

COMMENT ON COLUMN jiangyi_device.identifier IS
    '将邑签发设备编号（CQYB+序号），WS 路径 /websocket/device/{identifier} 用；UNBOUND 阶段尚未签发，允许为空，bindDomain 回填';
