-- MIGRATION_KIND: backfill
-- 敏感配置历史脱敏（2026-11-02）。
--
-- 背景：system_config_history / admin_audit_log 会把配置的 old/new 值原样留痕。
-- ops.alert.email.password（企业邮箱授权码）、*_webhook（含 token 的 URL）等键
-- 一旦被运营台改动，授权码就会出现在「配置版本」和审计详情里，任何有审计查看
-- 权限的人都能回读 —— 与「凭据只存键名、不进日志/审计」的口径冲突。
-- 代码侧已改为：查询接口返回 ******、写入回传 ****** 表示保持原值、历史/审计按
-- 敏感键脱敏；本迁移负责清理此前已经落库的明文历史。
--
-- 幂等：只改仍是明文且命中的行；重复执行不会重复改写。
UPDATE system_config_history
SET old_value = '******'
WHERE old_value IS NOT NULL
  AND old_value <> '******'
  AND config_key ~* '(password|secret|token|access_key|private_key|_webhook$)';

UPDATE system_config_history
SET new_value = '******'
WHERE new_value IS NOT NULL
  AND new_value <> '******'
  AND config_key ~* '(password|secret|token|access_key|private_key|_webhook$)';

UPDATE admin_audit_log
SET detail = '******（敏感配置，历史值已脱敏）'
WHERE target_type = 'system_config'
  AND target_id ~* '(password|secret|token|access_key|private_key|_webhook$)'
  AND coalesce(detail, '') NOT LIKE '******（敏感配置%';
