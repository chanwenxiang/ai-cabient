-- COMPETITOR_REF: CB-018
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- NOTES: 新建表 + 同迁移内建索引（空表非 hot table，无需 CONCURRENTLY，且 CONCURRENTLY 不能在事务块内跑）；无数据回填，重放安全。
-- TABLES: device_daily_online_rate (0 rows, 新建)
-- V329（CB-018 落码 ②）：柜机×日在线率表。
-- 数据源 = ops_exception 的 DEVICE_OFFLINE 区间 [created_at, resolved_at)：
--   心跳不可回溯（DevicePresenceService 仅更新设备信息表当前状态三字段），
--   离线/恢复分别写 DEVICE_OFFLINE 异常与 resolveSystem，区间可重建（分钟粒度）。
-- 口径：Asia/Shanghai 日界；进行中的离线段以统计时刻截断；未注册设备（registerUnknown
-- 之前无记录）不产生区间、不计入分母；重跑整行覆盖（幂等）。
-- 竞品：友宝/丰e足食均有柜机级在线/离线运营指标（docs/COMPETITOR_BENCHMARK.md CB-018）。

CREATE TABLE device_daily_online_rate (
    kpi_date        DATE             NOT NULL,
    device_id       VARCHAR(64)      NOT NULL,
    offline_minutes INT              NOT NULL DEFAULT 0,
    online_minutes  INT              NOT NULL,
    rate            DOUBLE PRECISION NOT NULL,
    computed_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (kpi_date, device_id)
);

CREATE INDEX idx_device_daily_online_rate_device_date
    ON device_daily_online_rate (device_id, kpi_date DESC);

COMMENT ON TABLE device_daily_online_rate IS '柜机×日在线率（CB-018）：由 DEVICE_OFFLINE 异常区间重建，每日快照，幂等覆盖';
