-- MIGRATION_REVIEWED: yes
-- MIGRATION_KIND: create（外部渠道对账导入 4 张新表；无数据回填）
-- COMPETITOR_REF: CB-016,CB-017
-- LOCK_RISK: low（全为 CREATE TABLE，不触既有表）
-- TABLES: external_import_template (+0) | external_product (+0) | external_product_mapping (+0) | external_import_batch (+0) | external_channel_flow (+0)
-- NOTES: 外部渠道（大黄鹅/嗨便利/健怡/民盛无人店/乐摇摇/客情用水）补货与销售数据导入（2026-10-13 拍板，CB-036）。
--        外部原始格式各异，解析模板按渠道配置；解析后统一 9 列（渠道/账期/线长/操作类型/商品ID/商品名称/数量/单价/时间）。
--        外部商品独立建库（external_product）不进我方 SKU 目录，经 external_product_mapping 映射到本系统 SKU。
--        批次留痕（external_import_batch，原始解析行 JSONB）→ 确认后聚合成 external_channel_flow（仓×SKU×渠道×操作类型），
--        月结（任务#4 账期制返工）按 period_label+warehouse_id 从该表取「仓库外调/客情用水」列。
--        模板种子走 db/seed/R__external_import_templates.sql（运营可改，ON CONFLICT DO NOTHING 不覆盖人工修改）。

-- ── 1. 解析模板（每渠道一条；列映射 JSONB：语义字段→外部列头）──────────────────────────────
CREATE TABLE external_import_template (
    template_id     BIGSERIAL PRIMARY KEY,
    channel_code    VARCHAR(40)  NOT NULL UNIQUE,
    channel_name    VARCHAR(60)  NOT NULL,
    file_type       VARCHAR(20)  NOT NULL,              -- CSV_GBK / XLSX / XLS
    sheet_strategy  VARCHAR(20)  NOT NULL DEFAULT 'FIRST',   -- FIRST / ALL(每 sheet 同构) / SKIP_META(第一个 sheet 前跳过元数据? 预留)
    qty_semantics   VARCHAR(20)  NOT NULL DEFAULT 'DIRECT',  -- DIRECT(直接数量列) / BY_DIFF(后-前) / SALES(销量=调出)
    key_field       VARCHAR(20)  NOT NULL DEFAULT 'NAME',    -- PRODUCT_ID / NAME（聚合键与商品库主键来源）
    person_source   VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',  -- SHEET_NAME / COLUMN / FILE_NAME / MANUAL(导入时人工指定)
    op_type         VARCHAR(30)  NOT NULL DEFAULT 'WAREHOUSE_OUT', -- WAREHOUSE_OUT / WAREHOUSE_STOCKTAKE / GUEST_WATER
    column_map      JSONB        NOT NULL,              -- {"person":..., "extId":"商品ID", "extName":"商品名称", "qty":"补货数量", "qtyAfter":"补货后库存", "qtyBefore":"补货前库存", "unitPrice":"单价", "happenedAt":"补货时间"}
    default_warehouse_id VARCHAR(32),                  -- MANUAL 人员归属时的兜底仓
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    remark          TEXT,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── 2. 外部商品库（外部 SKU 不进我方商品目录；每渠道自见）──────────────────────────────────
CREATE TABLE external_product (
    external_product_id BIGSERIAL PRIMARY KEY,
    channel_code    VARCHAR(40)  NOT NULL,
    external_key    VARCHAR(200) NOT NULL,              -- 商品ID（无则用归一化名称，模板 key_field 决定）
    external_name   VARCHAR(200) NOT NULL,
    brand           VARCHAR(100),
    spec            VARCHAR(100),
    unit_price_cents BIGINT,
    first_seen_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    times_seen      INT          NOT NULL DEFAULT 1,
    CONSTRAINT uq_external_product UNIQUE (channel_code, external_key)
);
CREATE INDEX idx_external_product_name ON external_product (channel_code, external_name);

-- ── 3. 外部商品 → 我方 SKU 映射（治理：一个外部商品至多绑一个本系统 SKU）───────────────────
CREATE TABLE external_product_mapping (
    mapping_id          BIGSERIAL PRIMARY KEY,
    external_product_id BIGINT      NOT NULL UNIQUE REFERENCES external_product (external_product_id) ON DELETE CASCADE,
    sku_id              VARCHAR(64) NOT NULL,
    bound_by            BIGINT,
    bound_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    note                TEXT
);
CREATE INDEX idx_external_mapping_sku ON external_product_mapping (sku_id);

-- ── 4. 导入批次（留痕；解析行全量 JSONB，确认提交后不可再改）──────────────────────────────
CREATE TABLE external_import_batch (
    batch_id        BIGSERIAL PRIMARY KEY,
    channel_code    VARCHAR(40)  NOT NULL,
    period_label    VARCHAR(10)  NOT NULL,              -- 账期标签=结束月，如 202609（26~25 账期）
    file_name       VARCHAR(260) NOT NULL,
    person_override VARCHAR(60),                        -- MANUAL 渠道导入时指定的线长
    status          VARCHAR(20)  NOT NULL DEFAULT 'PARSED', -- PARSED / COMMITTED
    rows_total      INT          NOT NULL DEFAULT 0,
    rows_mapped     INT          NOT NULL DEFAULT 0,    -- 外部商品已映射且人员可归仓
    rows_unmapped   INT          NOT NULL DEFAULT 0,
    unmapped_products JSONB,                             -- [{externalKey, externalName, timesSeen}] 预览用
    unresolved_persons JSONB,                           -- ["张三", ...] 人员无法归仓
    parsed_rows     JSONB,                               -- 统一 9 列行 [{person,extKey,extName,qty,unitPriceCents,happenedAt,srcLine}]
    uploaded_by     BIGINT,
    uploaded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    committed_at    TIMESTAMPTZ,
    commit_summary  JSONB                               -- {flows: N, qtyTotal: M, warehouses: [...]}
);

-- ── 5. 渠道流水（提交后的聚合事实表：仓×SKU×渠道×操作类型；月结取数源）─────────────────────
CREATE TABLE external_channel_flow (
    flow_id         BIGSERIAL PRIMARY KEY,
    batch_id        BIGINT       NOT NULL REFERENCES external_import_batch (batch_id),
    channel_code    VARCHAR(40)  NOT NULL,
    period_label    VARCHAR(10)  NOT NULL,
    warehouse_id    VARCHAR(32)  NOT NULL,
    person_name     VARCHAR(60),
    sku_id          VARCHAR(64)  NOT NULL,
    op_type         VARCHAR(30)  NOT NULL,
    qty             INT          NOT NULL,
    unit_price_cents BIGINT,
    happened_min    DATE,                                -- 行内最早/最晚业务时间（跨度核对用）
    happened_max    DATE,
    row_count       INT          NOT NULL DEFAULT 1,
    remark          TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_channel_flow_lookup ON external_channel_flow (period_label, warehouse_id, op_type);
CREATE INDEX idx_channel_flow_batch ON external_channel_flow (batch_id);
