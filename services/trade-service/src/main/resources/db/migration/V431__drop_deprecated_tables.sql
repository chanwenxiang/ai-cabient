-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（DROP TABLE 取 ACCESS EXCLUSIVE；11 张均为 0 行，锁窗口短）
-- NOTES: 删除 11 张已废弃且无活引用的表。执行前已用 pg_dump 备份：
--        .tmp/backup_deprecated_tables_2026-11-02.sql（含 11 张表结构与数据）。
--        本迁移先逐表 count(*)，任何一张 >0 立即 RAISE EXCEPTION 拒绝删除（防止生产库有数据被误删）。
--        执行窗口须确认共享测试环境无人使用；由 Flyway 在 trade-service 启动时应用。
-- TABLES: data_discrepancy (~0) | data_quality_check (~0) | idempotency_key (~0) | payscore_contract (~0)
--         | promotion_device (~0) | rate_limit_record (~0) | revenue_share_detail (~0) | revenue_share_rule (~0)
--         | sys_oper_log (~0) | user_login_log (~0) | wechat_binding (~0)
-- ROLLBACK: psql -U aicabinet -d aicabinet -f .tmp/backup_deprecated_tables_2026-11-02.sql
--           （备份为 plain SQL，含 11 张表的建表语句与数据；DROP 不可逆，恢复前先确认备份可读）

-- 防线：任何一张表有数据就拒绝删除，不要为了过迁移去注释掉本段。
DO $$
DECLARE
    t text;
    n bigint;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'data_discrepancy',
        'data_quality_check',
        'idempotency_key',
        'payscore_contract',
        'promotion_device',
        'rate_limit_record',
        'revenue_share_detail',
        'revenue_share_rule',
        'sys_oper_log',
        'user_login_log',
        'wechat_binding'
    ] LOOP
        IF to_regclass('public.' || t) IS NOT NULL THEN
            EXECUTE format('SELECT count(*) FROM public.%I', t) INTO n;
            IF n > 0 THEN
                RAISE EXCEPTION '拒绝删除 %：仍有 % 行数据，请先确认生产数据处置', t, n;
            END IF;
        END IF;
    END LOOP;
END $$;

DROP TABLE IF EXISTS data_discrepancy;
DROP TABLE IF EXISTS data_quality_check;
DROP TABLE IF EXISTS idempotency_key;
DROP TABLE IF EXISTS payscore_contract;
DROP TABLE IF EXISTS promotion_device;
DROP TABLE IF EXISTS rate_limit_record;
DROP TABLE IF EXISTS revenue_share_detail;
DROP TABLE IF EXISTS revenue_share_rule;
DROP TABLE IF EXISTS sys_oper_log;
DROP TABLE IF EXISTS user_login_log;
DROP TABLE IF EXISTS wechat_binding;
