-- COMPETITOR_REF: CB-023
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- NOTES: 新建独立关联表（将邑商品 ↔ 我方 SKU），零 DDL 触碰既有表、无数据回填，重放安全。将邑 barCode 可空且来源为 manual，故仅作快照列不作唯一键；jiangyi_product_id 唯一（一个将邑商品至多挂一个我方 SKU，防识别歧义）；sku_id 普通索引（允许同 SKU 挂多个将邑规格）；jiangyi_text_name 仅索引（学习产物命名规则供应商可控性低，留 classes 交叉验证用）。
-- TABLES: sku_jiangyi_link (0 rows, 新建)
-- 将邑二期（CB-023）：商品主数据挂接——我方 SKU 与将邑商品库（stdSku）的关联表。
-- 自研视觉链路（yolo_class_name）与本表并行互不污染；识别映射预生成（MODEL_SYNC）
-- 经本表拿 textName ↔ skuId 归属，避免逐设备人工配置。
CREATE TABLE sku_jiangyi_link (
    id                BIGSERIAL PRIMARY KEY,
    sku_id            VARCHAR(64)  NOT NULL,
    jiangyi_product_id VARCHAR(64) NOT NULL,
    jiangyi_name      VARCHAR(255),
    jiangyi_text_name VARCHAR(255),
    bar_code          VARCHAR(64),
    sync_status       VARCHAR(16)  NOT NULL DEFAULT 'BOUND',
    synced_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_sku_jiangyi_product UNIQUE (jiangyi_product_id),
    CONSTRAINT ck_sku_jiangyi_sync_status CHECK (sync_status IN ('BOUND', 'CREATED', 'RETIRED'))
);
CREATE INDEX idx_sku_jiangyi_link_sku ON sku_jiangyi_link (sku_id);
CREATE INDEX idx_sku_jiangyi_link_text_name ON sku_jiangyi_link (jiangyi_text_name);
COMMENT ON TABLE sku_jiangyi_link IS '将邑商品↔我方SKU挂接（CB-023）：sync_status BOUND=挂接既有将邑商品 / CREATED=我方发起新增 / RETIRED=解挂留痕';
