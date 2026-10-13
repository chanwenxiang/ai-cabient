-- 外部渠道解析模板种子（CB-036，2026-10-13 六渠道结构实证后定稿）
-- 幂等：ON CONFLICT DO NOTHING —— 不覆盖运营在后台已人工调整的模板。
-- 重跑：docker exec -i <pg> psql -U aicabinet -tA < services/trade-service/src/main/resources/db/seed/R__external_import_templates.sql
-- 列映射语义字段：extId/extName/brand/spec/unitPrice/qty/qtyBefore/qtyAfter/person/happenedAt
-- qty_semantics：DIRECT=直接数量列 / BY_DIFF=补货后-补货前 / SALES=销量即调出口径
-- person_source：SHEET_NAME=sheet 名即线长 / COLUMN=人员列 / MANUAL=导入时人工指定

INSERT INTO external_import_template
    (channel_code, channel_name, file_type, sheet_strategy, qty_semantics, key_field, person_source, op_type, column_map, enabled, remark)
VALUES
('dahuange', '大黄鹅', 'XLSX', 'ALL', 'DIRECT', 'PRODUCT_ID', 'SHEET_NAME', 'WAREHOUSE_OUT',
 '{"extId":"商品ID","extName":"商品名称","brand":"品牌","spec":"规格","unitPrice":"单价","qty":"补货数量","qtyBefore":"补货前库存","qtyAfter":"补货后库存","happenedAt":"补货时间"}'::jsonb,
 TRUE,
 '多 sheet（全量+每线长一个）；202606 曾为 CSV/GBK 且无补货数量列（需 后-前），模板可按期调整文件类型'),
('haibianli', '嗨便利', 'XLSX', 'FIRST', 'SALES', 'PRODUCT_ID', 'MANUAL', 'WAREHOUSE_OUT',
 '{"extId":"商品ID","extName":"商品名称","brand":"品牌","spec":"分类","qty":"实际销售数量"}'::jsonb,
 TRUE,
 '销售数据非补货：调出口径=销售数；按城市分文件，导入时人工指定线长'),
('jianyi', '江门健怡', 'XLSX', 'FIRST', 'DIRECT', 'NAME', 'MANUAL', 'WAREHOUSE_OUT',
 '{"extName":"商品名称","qty":"购买数量","happenedAt":"下单时间"}'::jsonb,
 TRUE,
 '订单明细无商品ID，名称键聚合；导入时人工指定线长'),
('minsheng_wds', '民盛无人店', 'XLSX', 'FIRST', 'SALES', 'NAME', 'MANUAL', 'WAREHOUSE_OUT',
 '{"extName":"商品名称","qty":"净销售数量"}'::jsonb,
 TRUE,
 '销售统计；用户手工透视后导入，导入时人工指定线长'),
('leyaoyao', '乐摇摇', 'XLS', 'FIRST', 'DIRECT', 'NAME', 'COLUMN', 'WAREHOUSE_OUT',
 '{"extName":"商品名称","unitPrice":"价格","qty":"本次补货数量","qtyBefore":"补货前","qtyAfter":"补货后","person":"补货人员","happenedAt":"补货时间"}'::jsonb,
 TRUE,
 '老式 .xls(OLE2)；补货人员列=姓名+手机掩码，解析取姓名前缀'),
('keqing_water', '客情用水', 'XLSX', 'FIRST', 'DIRECT', 'NAME', 'COLUMN', 'GUEST_WATER',
 '{"extName":"商品名称","spec":"规格","qty":"实发数","person":"领货人"}'::jsonb,
 TRUE,
 '线长手工单（自动不了，归本通道）；操作类型=客情用水，月结落客情列')
ON CONFLICT (channel_code) DO NOTHING;
