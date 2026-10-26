-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: jiangyi_class_mapping (~百行)
-- COMPETITOR_REF: CB-065
-- 重训后自动复核（H-27/§10.1）新增「机器挂接」来源：模型新增类目（NEW）若其厂商标准商品
-- 条码与我方唯一一个在售 SKU 的条码精确相等，自动档会直接挂上并启用；否则仍建 DISABLED 空行
-- 等人工挂。这类行必须能与「人工挂接的 MODEL_SYNC 行」区分开——否则挂错时无法回溯是谁挂的。
-- NOTES: 只改 CHECK 约束表达式（V333 建的 ck_jiangyi_class_source），扫全表但表很小；
--        不加默认值、不改列类型，历史行取值仍只有 MANUAL/MODEL_SYNC。
-- ROLLBACK: ALTER TABLE jiangyi_class_mapping DROP CONSTRAINT IF EXISTS ck_jiangyi_class_source;
--           ALTER TABLE jiangyi_class_mapping ADD CONSTRAINT ck_jiangyi_class_source
--               CHECK (source IN ('MANUAL', 'MODEL_SYNC'));  -- 须先清掉 MODEL_AUTO_MATCH 行
ALTER TABLE jiangyi_class_mapping
    DROP CONSTRAINT ck_jiangyi_class_source;
ALTER TABLE jiangyi_class_mapping
    ADD CONSTRAINT ck_jiangyi_class_source
        CHECK (source IN ('MANUAL', 'MODEL_SYNC', 'MODEL_AUTO_MATCH'));
