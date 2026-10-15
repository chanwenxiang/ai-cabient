-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- COMPETITOR_REF: CB-047
-- NOTES: C3 商品模板（2026-10-15 backlog 拍板项）：DB 化铺货模板——把某台设备的货道配置
--       （货道×SKU×售价×目层库存）存为模板，供同型设备一键套用。老系统对位
--       ego_machine_aisle_template（货道名+SKU+默认价+默认库存）。
--       与老系统差异（有意不同）：老模板是「新机开通铺货道」，我方模板是「设备间复製陈列」
--       ——设备开通骨架已由代码内置机型模板（PlanogramTemplateService）承担，此处只做
--       SKU/价格/目层的复制；套用时行缺失自动补货道骨架。
-- TABLE: slot_template (id BIGSERIAL PK, template_name VARCHAR(64) UNIQUE NOT NULL,
--        created_by BIGINT, created_at TIMESTAMPTZ DEFAULT now())
-- TABLE: slot_template_line (id BIGSERIAL PK, template_id BIGINT NOT NULL REFERENCES slot_template(id) ON DELETE CASCADE,
--        slot_code VARCHAR(16) NOT NULL, row_no INT NOT NULL, col_no INT NOT NULL,
--        sku_id VARCHAR(32) NOT NULL, price_cents BIGINT NOT NULL, par_level INT NOT NULL DEFAULT 8)
-- ID: slot_template_pkey / slot_template_line_pkey

CREATE TABLE slot_template (
    id           BIGSERIAL PRIMARY KEY,
    template_name VARCHAR(64) NOT NULL UNIQUE,
    created_by   BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE slot_template_line (
    id          BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES slot_template (id) ON DELETE CASCADE,
    slot_code   VARCHAR(16) NOT NULL,
    row_no      INT NOT NULL,
    col_no      INT NOT NULL,
    sku_id      VARCHAR(32) NOT NULL,
    price_cents BIGINT NOT NULL,
    par_level   INT NOT NULL DEFAULT 8
);
CREATE INDEX slot_template_line_template_idx ON slot_template_line (template_id);
