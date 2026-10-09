-- COMPETITOR_REF: CB-022
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- V333（将邑开门柜接入②，模式一）：class_id → 我方 SKU 映射表（设备+模型维度）。
-- 将邑识别上报只带 classId（无金额、无 SKU；specialId 全文档仅一次出口、来源未定义，见 V16 §4.2.10），
-- 金额绝不采信设备侧：结算金额 = Σ(映射 SKU 当前价 × qty)（分）；
-- 映射未命中 fail-closed 转 DISPUTED（宁可人工介入，不算错账）。
-- source：MANUAL(admin 人工建立) | MODEL_SYNC(模型 classes 同步预生成，二期)；
-- status：ACTIVE 生效 | DISABLED 预生成先禁用、人工确认后激活（防误映射直接扣款）。

CREATE TABLE jiangyi_class_mapping (
    id          BIGSERIAL    PRIMARY KEY,
    device_id   VARCHAR(64)  NOT NULL,
    class_id    INT          NOT NULL,
    model_name  VARCHAR(128),
    text_name   VARCHAR(255),
    sku_id      VARCHAR(64),
    status      VARCHAR(16)  NOT NULL DEFAULT 'DISABLED',
    source      VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_jiangyi_class_device_class UNIQUE (device_id, class_id),
    CONSTRAINT ck_jiangyi_class_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_jiangyi_class_source CHECK (source IN ('MANUAL', 'MODEL_SYNC'))
);

CREATE INDEX idx_jiangyi_class_device_status ON jiangyi_class_mapping (device_id, status);

COMMENT ON TABLE jiangyi_class_mapping IS '将邑 class_id→SKU 映射（CB-022）：金额我方按映射 SKU 价合计（分），未命中 fail-closed 转 DISPUTED';
