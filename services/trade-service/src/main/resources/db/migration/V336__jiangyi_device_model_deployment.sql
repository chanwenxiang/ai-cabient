-- COMPETITOR_REF: CB-023
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- NOTES: 两张全新表 + 将邑设备登记表加两列（industrial_control_model/gather_locked_at）。将邑设备表为低频写入的接入登记表（非交易主数据），短锁可接受；无数据回填，重放安全。industrial_control_model 存将邑原值（"76"/"88"）不解释语义——采集文档示例中两种主板 rknn 的该字段同为 "88"，映射关系文档自证不了，下发校验用字符串相等。
-- TABLES: jiangyi_model_deployment (0 rows, 新建) / jiangyi_training_ticket (0 rows, 新建) / jiangyi_device (+2 列)
-- 将邑二期（CB-023）：模型下发审计 + 学习回调凭据 + 设备机型/采集模式锁。

-- ① 设备侧：工控机机型（将邑原值，空=未登记则拒绝模型下发 fail-closed）+ 采集模式锁（NULL=正常营业）
ALTER TABLE jiangyi_device ADD COLUMN industrial_control_model VARCHAR(16);
ALTER TABLE jiangyi_device ADD COLUMN gather_locked_at TIMESTAMPTZ;

-- ② 模型下发审计：跨「将邑云→trade→gateway→设备」四跳的异步动作台账；uk(device_id, model_name, sent_at) 辅助幂等排障
CREATE TABLE jiangyi_model_deployment (
    id                       BIGSERIAL PRIMARY KEY,
    device_id                VARCHAR(64)  NOT NULL,
    model_id                 VARCHAR(64),
    model_name               VARCHAR(128) NOT NULL,
    model_url                VARCHAR(512),
    classes_text_url         VARCHAR(512),
    industrial_control_model VARCHAR(16),
    classes_version          VARCHAR(64),
    status                   VARCHAR(16)  NOT NULL DEFAULT 'SENT',
    sent_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at             TIMESTAMPTZ,
    fail_reason              VARCHAR(255),
    CONSTRAINT ck_jiangyi_deploy_status CHECK (status IN ('SENT', 'CONFIRMED', 'FAILED'))
);
CREATE INDEX idx_jiangyi_deploy_device_sent ON jiangyi_model_deployment (device_id, sent_at DESC);
CREATE INDEX idx_jiangyi_deploy_model ON jiangyi_model_deployment (model_name);
COMMENT ON TABLE jiangyi_model_deployment IS '将邑模型下发审计（CB-023）：SENT=已下发待回执 / CONFIRMED=设备 downloadModelNotify 确认 / FAILED=超时或校验失败';

-- ③ 学习回调凭据：finishNotifyId 是服务端持有的一次性凭据（将邑要求回调 url 无鉴权，防伪造锚点）
CREATE TABLE jiangyi_training_ticket (
    id                 BIGSERIAL PRIMARY KEY,
    device_id          VARCHAR(64)  NOT NULL,
    sku_id             VARCHAR(64)  NOT NULL,
    jiangyi_product_id VARCHAR(64)  NOT NULL,
    finish_notify_id   VARCHAR(64)  NOT NULL,
    model_name         VARCHAR(128),
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    CONSTRAINT uk_jiangyi_training_notify UNIQUE (finish_notify_id),
    CONSTRAINT ck_jiangyi_training_status CHECK (status IN ('PENDING', 'FINISHED', 'FAILED'))
);
COMMENT ON TABLE jiangyi_training_ticket IS '将邑学习触发凭据（CB-023）：finishNotifyId 一次性凭据，回调命中 PENDING 才处理，其余静默丢弃';
