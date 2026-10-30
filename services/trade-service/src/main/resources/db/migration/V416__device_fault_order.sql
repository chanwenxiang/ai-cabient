-- COMPETITOR_REF: CB-077
-- MIGRATION_KIND: create
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- NOTES: 全新表（设备侧故障单，固件自动上报入库）。与消费者手动报修表 device_fault_report 分表不混用
--   （语义不同：自报遥测 vs 人工工单，后者有 user_id/issue_type）。不 FK device 表：网关转发的 deviceId
--   是厂商侧标识，未注册设备的故障也要留痕（fail-open，与 jiangyi_order_video 同约定）。
--   order_no 可空（固件故障单不一定挂会话）；source 预留多协议来源。无数据回填，重放安全。
-- TABLES: device_fault_order (0 rows, 新建)
-- 将邑故障单链路（CB-077）：设备 POST /jiangyi/api/order/uploadFaultOrder → 网关转发 trade 入库，
--   后台运维/商户 Web 后台设备详情「故障记录」列表（CB-031 下钻范式）。不参与结算/资金路径。

CREATE TABLE device_fault_order (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(64)  NOT NULL,
    identifier  VARCHAR(64),
    order_no    VARCHAR(64),
    error_code  VARCHAR(64),
    error_msg   VARCHAR(512) NOT NULL DEFAULT '',
    source      VARCHAR(32)  NOT NULL DEFAULT 'JIANGYI_FIRMWARE',
    reported_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_device_fault_order_device ON device_fault_order (device_id, reported_at DESC);
