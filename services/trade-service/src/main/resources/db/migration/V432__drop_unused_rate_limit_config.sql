-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low（DROP TABLE 取 ACCESS EXCLUSIVE；仅 5 行种子配置，锁窗口短）
-- NOTES: 旧《孤儿表定策》B1 判定 rate_limit_config 零代码引用、限流实际走 Redis；2026-11-02
--        复核 services/clients/scripts 仍无引用，5 行只是早先种子的限流配置。
--        删除前已 pg_dump 到 .tmp/backup_rate_limit_config_2026-11-02.sql（含结构与数据）。
--        守卫允许 0 或 5 行（已知种子形态）；其他行数 fail-loud，防止把运营新增配置误删。
-- TABLES: rate_limit_config (~5)
-- ROLLBACK: psql -U aicabinet -d aicabinet -f .tmp/backup_rate_limit_config_2026-11-02.sql

-- 防线：只允许已知的 0/5 行形态；不要为了过迁移去放宽。
DO $$
DECLARE
    n bigint;
BEGIN
    IF to_regclass('public.rate_limit_config') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM public.rate_limit_config' INTO n;
        IF n NOT IN (0, 5) THEN
            RAISE EXCEPTION '拒绝删除 rate_limit_config：期望 0 或 5 行种子配置，实际 % 行；请先确认', n;
        END IF;
    END IF;
END $$;

DROP TABLE IF EXISTS rate_limit_config;
