-- MIGRATION_KIND: backfill（一条 system_config 关开关 + 两张券模板停用；不新建表、无 DDL）
-- 2026-11-23 产品决定：不做邀请裂变（营销 2），下线分享入口与发券。
--
-- 为什么还要这个迁移：V501 已应用（Flyway 文件不可删/不可回滚），且开关在 system_config
-- 里当前为 true —— 只改代码默认值不够，库里的显式行优先，故这里把存量行改成 false。
-- 券模板置 INACTIVE：issued_count=0（dev 实测 0，无 user_coupon 引用）；保留行以便将来
-- 若重开时复用，不物理删除。
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: system_config (1 row), coupon_definition (2 rows)
-- NOTES: 两条 UPDATE/INSERT 均按唯一键命中 1/2 行；非热表；无 DDL、无长锁。

INSERT INTO system_config (config_key, config_value, description, updated_at)
VALUES (
  'marketing.invite.enabled',
  'false',
  '邀请拉新开关：2026-11-23 产品下线（保留开关仅用于临时回开）',
  NOW()
)
ON CONFLICT (config_key) DO UPDATE
SET config_value = 'false', updated_at = NOW();

UPDATE coupon_definition
SET status = 'INACTIVE'
WHERE system_code IN ('INVITE_NEWCOMER', 'INVITE_INVITER');