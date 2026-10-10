-- COMPETITOR_REF: CB-024
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- NOTES: 全新表（将邑设备视频上报台账）。uk(order_no, serial_num) 支撑设备重试重报幂等 upsert；video_urls 存逗号拼接原文（上/下摄像头同片），空串=该片生成/上传失败（文档 §4.2.14 明示 [] 语义）。无数据回填，重放安全。
-- TABLES: jiangyi_order_video (0 rows, 新建)
-- 将邑视频链路（CB-024）：设备经 STS 直传 OSS 后上报视频地址，admin 按订单复核。

CREATE TABLE jiangyi_order_video (
    id             BIGSERIAL PRIMARY KEY,
    order_no       VARCHAR(64)  NOT NULL,
    device_id      VARCHAR(64),
    serial_num     INTEGER      NOT NULL,
    video_quantity INTEGER      NOT NULL,
    video_urls     TEXT         NOT NULL DEFAULT '',
    reported_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_jiangyi_order_video UNIQUE (order_no, serial_num)
);
CREATE INDEX idx_jiangyi_order_video_device ON jiangyi_order_video (device_id, reported_at DESC);
