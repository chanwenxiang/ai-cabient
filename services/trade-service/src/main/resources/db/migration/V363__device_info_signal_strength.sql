-- 图2 对照（叮当小柜，2026-10-15）：设备详情展示「信号强度」。
-- 协议依据：将邑 V16 §4.2.15 上报设备信息（deviceInfo/getDeviceSimInfo）
-- 携带 signalStrength 0-4（M 必报，设备启动默认上报一次，可下发 getDeviceInfo 实时拉取）。
-- 注意：网络延迟/拍照设置/断电检测协议无对应接口，不做（2026-10-15 用户拍板）。
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS signal_strength INT;
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS signal_reported_at TIMESTAMPTZ;

COMMENT ON COLUMN device_info.signal_strength IS '将邑信号强度 0-4（getDeviceSimInfo 上报）';
COMMENT ON COLUMN device_info.signal_reported_at IS '信号强度最近上报时间';
