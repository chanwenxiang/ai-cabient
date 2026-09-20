-- 系统配置变更历史（F1 动态定价 · 策略版本与审计）
--
-- 背景：system_config 是全平台运营策略的唯一权威来源（68+ 个键：定价、结算、退款、
-- 设备离线策略、OTA 开关……），V1 建表起只有「当前值」一列 config_value，
-- 被覆盖即不可追。运营在页面上改错一个折扣比例、误删一个键，事后只能从
-- createdAt/updatedAt 知道「改过」，无法知道「原来是多少、被谁改成了什么」。
--
-- 缺口（本轮实测确认）：SystemConfigService 全文件 `audit|history` 命中 0 ——
-- upsert / delete 两条写入路径既不写 admin_audit_log，也不留任何旧值快照。
-- 而 admin_audit_log（V6）早已存在、AdminAuditService.appendLog 早已被退款等
-- 模块复用，配置这一条路径只是**没人接**。
--
-- 本次补齐：
--   1) system_config_history —— 结构化「版本」：一行一次变更，old_value 是变更前值，
--      于是「某键的全部历史」= 按 created_at 排出来的版本序列，可回滚到任一条的 old_value；
--   2) 同一次变更另写一条 admin_audit_log（action=CONFIG_UPSERT|CONFIG_DELETE）——
--      统一审计视图，运营台审计页可按 target_type=system_config 直接过滤。
--   两者由开关 ops.config.audit.enabled 控制（默认 false ⇒ 两条都不写，
--   行为与接入前逐字节一致，fail-closed）。
--
-- 刻意的设计取舍：
--   * old_value/new_value 用 TEXT 而非 VARCHAR(n)：与 system_config.config_value 同宽，
--     避免历史侧比当前值更早被截断（改了校验规则后旧值仍要能原样回看）。
--   * operator_id 语义与 admin_audit_log 一致（0 = V221 预置的「系统」账号），
--     但**不建外键**：运营账号已在 admin_audit_log 上受 ON DELETE RESTRICT 约束，
--     历史表没必要再为同一生命周期加一道锁；写入路径只接受来自鉴权上下文的 ID。
--   * 不给 system_config 加触发器：写入方只有 SystemConfigService 一处（实测
--     upsert/delete 的唯一生产调用方是 SystemConfigController），在服务层记录可以
--     带上 operatorId 与「动作类型」，触发器拿不到这两样。
--
-- MIGRATION_KIND: schema
-- LOCK_RISK: low
-- NOTES: CREATE TABLE + CREATE INDEX，均为新对象，不触碰任何既有表 ⇒ 无重写、无长锁。
--        表从 0 行开始增长，一行一次配置变更（人工操作频率，日增量级个位数）。
-- TABLES: system_config_history (新增)

CREATE TABLE IF NOT EXISTS system_config_history (
    history_id  BIGSERIAL    PRIMARY KEY,
    config_key  VARCHAR(128) NOT NULL,
    old_value   TEXT,
    new_value   TEXT,
    operator_id BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE system_config_history IS '系统配置变更历史（F1 策略版本）：一行一次写操作，old_value 为变更前值';
COMMENT ON COLUMN system_config_history.config_key IS '被修改的配置键（对齐 system_config.config_key）';
COMMENT ON COLUMN system_config_history.old_value IS '变更前的值；首次创建时为 NULL（该版本不可回滚）';
COMMENT ON COLUMN system_config_history.new_value IS '变更后的值；删除操作时为 NULL';
COMMENT ON COLUMN system_config_history.operator_id IS '操作人 user_id；0 = 系统账号（无鉴权上下文的调用）';
COMMENT ON COLUMN system_config_history.created_at IS '变更时间';

-- 主查询形态：某键的版本序列（倒序）。config_key + created_at 复合索引直接覆盖。
CREATE INDEX IF NOT EXISTS idx_system_config_history_key
    ON system_config_history (config_key, created_at DESC);
