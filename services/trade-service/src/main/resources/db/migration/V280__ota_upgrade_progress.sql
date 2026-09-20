-- OTA 升级进度上报（O2 · 让 ota_device_report 从「建表即孤儿」变成真正在用的上报表）
--
-- 背景：ota_device_report 自 V9__commercial_ops.sql 建表起全仓 java 零引用
-- （无 entity / mapper / service），V217 给它补了 device_id 外键、V236/V237 补了中文注释，
-- 但始终没有写入者 —— 建表即孤儿，线上 0 行。
--
-- 缺口：设备侧 OTA 只有「终态」一个信号 —— GET /internal/v1/devices/{id}/ota/check 里
-- 顺带 reportVersion，把 app_version 写进 device_info。下载/安装过程中的进度、失败原因
-- 全部无处落地，于是「卡在下载 60%」「安装失败」的柜机，在运营台看起来和
-- 「压根没开始升级」完全一样，也没有任何线索可查。
--
-- 本次补齐：新增 target_version / upgrade_status / progress_percent / error_message
-- 四个进度列 + updated_at（app_version 保留原义 = 当前实际运行的版本），
-- 把「过程」与「终态」落到同一行。写入方：OtaService.reportProgress（受开关
-- ota.progress.enabled 控制，默认关闭 ⇒ 不写库，行为与接入前一致）。
--
-- MIGRATION_KIND: schema
-- LOCK_RISK: low
-- NOTES: 非热点表且线上 0 行；ADD COLUMN 带常量默认值，PostgreSQL 11+ 为元数据变更，
--        不重写表、不长锁。未建索引：本表一行一设备，运营台查询走主键/小表扫描即可。
-- TABLES: ota_device_report (~设备台数)

ALTER TABLE ota_device_report
    ADD COLUMN IF NOT EXISTS target_version   VARCHAR(32),
    ADD COLUMN IF NOT EXISTS upgrade_status   VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    ADD COLUMN IF NOT EXISTS progress_percent SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS error_message    VARCHAR(256),
    ADD COLUMN IF NOT EXISTS updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW();

COMMENT ON COLUMN ota_device_report.target_version IS '本次升级的目标版本；无升级中任务时为 NULL';
COMMENT ON COLUMN ota_device_report.upgrade_status IS '升级状态：IDLE/DOWNLOADING/INSTALLING/SUCCESS/FAILED';
COMMENT ON COLUMN ota_device_report.progress_percent IS '升级进度百分比 0-100';
COMMENT ON COLUMN ota_device_report.error_message IS '失败原因（仅 FAILED 时有值，其它状态必须清空）';
COMMENT ON COLUMN ota_device_report.updated_at IS '最近一次进度上报时间';
