-- 稳定在线自动解锁阈值：15 → 5 分钟（2026-09-17 产品定稿）。
--
-- 只迁移「仍是旧默认值 15」的行：运营台已手工改过的值保持不动，
-- 否则会把运营的显式配置吞掉（配置项退化成「假可配置」）。
--
-- 与 SystemConfigService.ensureDefaults() 的兜底默认值
-- （DEFAULT_DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES = 5）配套：
--   已有库 → 本迁移把 15 更新为 5；
--   空库   → 本迁移影响 0 行，随后由 ensureDefaults() 以新默认值插入。
--
-- 刻意**不用**「启动期发现值=15 就覆盖」的写法：那会在每次打开系统配置页时
-- 反复把运营改回 15 的值刷掉。Flyway 迁移天然只执行一次（见 flyway_schema_history）。

UPDATE system_config
SET config_value = '5',
    updated_at   = NOW()
WHERE config_key = 'device.offline.auto_unlock_stable_minutes'
  AND config_value = '15';
