-- MIGRATION_REVIEWED: yes
-- MIGRATION_KIND: alter（月结行加 7 列、月结单加 1 列、损耗字典种子；无数据回填）
-- COMPETITOR_REF: CB-017
-- LOCK_RISK: low（ALTER TABLE ADD COLUMN 带默认值，PG 11+ 不重写表；字典幂等 INSERT）
-- TABLES: warehouse_monthly_close_line (+7) | warehouse_monthly_close (+1) | sys_dict_type (+0) | sys_dict_data (+0)
-- NOTES: 月结账期制返工（任务#4，2026-10-13 拍板，账期=上月26 00:00~本月25 23:59，标签=结束月）。
--        ① 财务 18 列对齐：损耗单列拆「摇机损耗/过期损耗/客情用水/费用抵扣」四列 + 单价快照列；
--          费用抵扣与摇机损耗支持草稿阶段手工补录（edited_flag 标记人工编辑过，漂移核对跳过）。
--        ② 期初结转：opening_source = PERIOD_CLOSE（取上期单实盘） / BOOK（账面流水结转，告警「期初为账面值」）。
--        ③ 损耗字典（sys_dict monthly_loss_type）供前端 label；归集逻辑在应用层常量
--          （EXPIRED→过期列、DAMAGED→摇机列、外部导入 GUEST_WATER→客情列、费用抵扣纯手工）。

-- ── 1. 月结行：四损耗列 + 单价快照 + 人工编辑标记 ─────────────────────────────────────────
ALTER TABLE warehouse_monthly_close_line
    ADD COLUMN rock_loss_qty     int NOT NULL DEFAULT 0,
    ADD COLUMN expired_loss_qty  int NOT NULL DEFAULT 0,
    ADD COLUMN guest_water_qty   int NOT NULL DEFAULT 0,
    ADD COLUMN fee_deduct_qty    int NOT NULL DEFAULT 0,
    ADD COLUMN unit_price_cents  bigint,
    ADD COLUMN edited_flag       boolean NOT NULL DEFAULT false,
    ADD COLUMN line_remark       varchar(255);

-- ── 2. 月结单：期初结转来源（PERIOD_CLOSE=上期实盘结转 / BOOK=账面结转需告警）─────────────
ALTER TABLE warehouse_monthly_close
    ADD COLUMN opening_source varchar(20) NOT NULL DEFAULT 'BOOK';

-- ── 3. 损耗四类字典（前端 label 与导出表头；归集映射见应用层 WarehouseMonthlyCloseService）──
INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT 'monthly_loss_type', '月结损耗类型', 'ACTIVE', 900,
       '财务18列四损耗列（摇机/过期/客情/费用抵扣）；归集来源见 SATELLITE_WAREHOUSE.md'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'monthly_loss_type');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('monthly_loss_type', 'ROCK',        '摇机损耗',  1, '仓库核销 reasonCategory=DAMAGED 自动归集 + 草稿手工补录'),
    ('monthly_loss_type', 'EXPIRED',     '过期损耗',  2, '仓库核销 reasonCategory=EXPIRED 自动归集'),
    ('monthly_loss_type', 'GUEST_WATER', '客情用水',  3, '外部导入 GUEST_WATER 流水自动归集（补货目的链路待补货模块返工）'),
    ('monthly_loss_type', 'FEE_DEDUCT',  '费用抵扣',  4, '财务审批前草稿阶段手工补录')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
