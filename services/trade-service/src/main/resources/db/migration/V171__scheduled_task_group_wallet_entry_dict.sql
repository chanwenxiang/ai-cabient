-- 新增系统字典：前端展示枚举统一入库（与 shared-dict 基线 / SysDictBootstrap 保持一致）

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('scheduled_task_group', '定时任务分组', 'ACTIVE', 100, '定时任务管理页分组列中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('wallet_entry_type', '钱包流水类型', 'ACTIVE', 101, '线长/商户钱包流水类型中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('session_kind', '会话类型', 'ACTIVE', 102, '会话列表/详情会话类型中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('restock_line_type', '补货行类型', 'ACTIVE', 103, '补货任务明细行类型中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('ops_alert_type', '运维告警类型', 'ACTIVE', 104, '首页/异常告警类型中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('ad_asset_type', '广告素材类型', 'ACTIVE', 105, '广告素材/投放计划素材类型中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('ad_campaign_status', '投放计划状态', 'ACTIVE', 106, '广告投放计划状态中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('consistency_check_type', '一致性检查类型', 'ACTIVE', 107, '数据一致性巡检类型中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('sku_perf_level', '选品表现等级', 'ACTIVE', 108, '选品诊断表现等级中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('sku_review_status', '选品评审状态', 'ACTIVE', 109, '选品评审状态中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('member_level', '会员等级', 'ACTIVE', 110, '用户会员等级中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('site_contract_status', '点位合同状态', 'ACTIVE', 111, '组织与点位合同状态中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('device_env_type', '设备环境指标类型', 'ACTIVE', 112, '设备环境监控指标类型中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('device_lifecycle_action', '设备生命周期操作', 'ACTIVE', 113, '设备生命周期操作日志中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('stock_health_dim', '库存健康维度', 'ACTIVE', 114, '库存健康报表维度中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('purchase_suggestion_reason', '补货建议原因', 'ACTIVE', 115, '采购建议原因中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('supplier_payable_status', '供应商应付状态', 'ACTIVE', 116, '供应商应付状态中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('stocktake_mode', '盘点方式', 'ACTIVE', 117, '库存盘点方式中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('stocktake_status', '盘点单状态', 'ACTIVE', 118, '库存盘点单状态中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('stocktake_line_status', '盘点行状态', 'ACTIVE', 119, '库存盘点明细行状态中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
VALUES ('merchant_alert_type', '商户告警类型', 'ACTIVE', 120, '商户端告警/消息类型中文映射')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, row_number() OVER (ORDER BY v.sort_order), 'ACTIVE'
FROM (VALUES
    ('scheduled_task_group'::varchar, 'DEVICE'::varchar,    '设备'::varchar,    1),
    ('scheduled_task_group',          'TRADE',              '交易',             2),
    ('scheduled_task_group',          'OPS',                '运维',             3),
    ('scheduled_task_group',          'SYSTEM',             '系统',             4),
    ('scheduled_task_group',          'WAREHOUSE',          '仓储',             5),
    ('scheduled_task_group',          'MERCHANT',           '商户',             6),
    ('scheduled_task_group',          'FINANCE',            '财务',             7),
    ('scheduled_task_group',          'MARKETING',          '营销',             8)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, row_number() OVER (ORDER BY v.sort_order), 'ACTIVE'
FROM (VALUES
    ('session_kind'::varchar, 'RESTOCK'::varchar, '补货'::varchar, 1),
    ('session_kind', 'OPS', '运维', 2),
    ('session_kind', 'SHOPPING', '消费', 3),
    ('restock_line_type', 'RESTOCK', '上架', 1),
    ('restock_line_type', 'PULL_OFF', '下架', 2),
    ('restock_line_type', 'REMOVE', '下架', 3),
    ('restock_line_type', 'PULL', '下架', 4),
    ('ops_alert_type', 'DISPUTE', '账单争议', 1),
    ('ops_alert_type', 'DEVICE_OFFLINE', '设备离线', 2),
    ('ops_alert_type', 'UPLOAD_STUCK', '录像滞留', 3),
    ('ops_alert_type', 'SESSION_STALE', '会话超时', 4),
    ('ops_alert_type', 'LOW_STOCK', '库存不足', 5),
    ('ops_alert_type', 'REPLENISHMENT', '补货任务', 6),
    ('ops_alert_type', 'RECON_MISMATCH', '对账差异', 7),
    ('ops_alert_type', 'RECONCILIATION_MISMATCH', '对账差异', 8),
    ('ops_alert_type', 'SPLIT_EXCEPTION', '分账异常', 9),
    ('ops_alert_type', 'IN_TRANSIT_OVERDUE', '签收超时', 10),
    ('ad_asset_type', 'IMAGE', '图片', 1),
    ('ad_asset_type', 'VIDEO', '视频', 2),
    ('ad_asset_type', 'H5', 'H5', 3),
    ('ad_campaign_status', 'DRAFT', '草稿', 1),
    ('ad_campaign_status', 'RUNNING', '投放中', 2),
    ('ad_campaign_status', 'STOPPED', '已停止', 3),
    ('consistency_check_type', 'ORDER_AMOUNT', '订单金额', 1),
    ('consistency_check_type', 'PAYMENT_AMOUNT', '支付净额', 2),
    ('consistency_check_type', 'INVENTORY_MISMATCH', '库存汇总', 3),
    ('sku_perf_level', 'BEST_SELLER', '畅销', 1),
    ('sku_perf_level', 'NORMAL', '正常', 2),
    ('sku_perf_level', 'SLOW_MOVER', '慢销', 3),
    ('sku_perf_level', 'NO_SALES', '无销量', 4),
    ('sku_review_status', 'PENDING', '待评审', 1),
    ('sku_review_status', 'RECOMMEND_DELIST', '建议下架', 2),
    ('sku_review_status', 'DELISTED', '已下架', 3),
    ('sku_review_status', 'KEPT', '已保留', 4),
    ('member_level', 'NORMAL', '普通', 1),
    ('member_level', 'SILVER', '白银', 2),
    ('member_level', 'GOLD', '黄金', 3),
    ('member_level', 'PLATINUM', '铂金', 4),
    ('member_level', 'DIAMOND', '钻石', 5),
    ('site_contract_status', 'ACTIVE', '有效', 1),
    ('site_contract_status', 'EXPIRING', '临期', 2),
    ('site_contract_status', 'EXPIRED', '已到期', 3),
    ('device_env_type', 'HUMIDITY', '湿度', 1),
    ('device_env_type', 'VOLTAGE', '电压', 2),
    ('device_env_type', 'POWER', '功耗', 3),
    ('device_lifecycle_action', 'BIND', '绑定商户', 1),
    ('device_lifecycle_action', 'UNBIND', '解绑', 2),
    ('device_lifecycle_action', 'DEPLOY', '投放', 3),
    ('device_lifecycle_action', 'UNDEPLOY', '撤回未投放', 4),
    ('device_lifecycle_action', 'RETURN', '返厂', 5),
    ('device_lifecycle_action', 'RETIRE', '退役', 6),
    ('device_lifecycle_action', 'INBOUND', '入库', 7),
    ('stock_health_dim', 'STOCKOUT', '断货', 1),
    ('stock_health_dim', 'LOW', '低库存', 2),
    ('stock_health_dim', 'NEAR_EXPIRY', '临期', 3),
    ('purchase_suggestion_reason', 'SALES_DRIVEN', '销量驱动', 1),
    ('purchase_suggestion_reason', 'TREND_FORECAST', '趋势预测', 2),
    ('purchase_suggestion_reason', 'LOW_STOCK', '库存不足', 3),
    ('supplier_payable_status', 'UNPAID', '未付', 1),
    ('supplier_payable_status', 'PARTIAL', '部分付款', 2),
    ('supplier_payable_status', 'PAID', '已付', 3),
    ('supplier_payable_status', 'CLOSED', '已关闭', 4),
    ('stocktake_mode', 'BLIND', '盲盘', 1),
    ('stocktake_mode', 'VISIBLE', '明盘', 2),
    ('stocktake_status', 'DRAFT', '草稿', 1),
    ('stocktake_status', 'IN_PROGRESS', '盘点中', 2),
    ('stocktake_status', 'COMPLETED', '已完成', 3),
    ('stocktake_status', 'ADJUSTED', '已调整', 4),
    ('stocktake_status', 'CANCELLED', '已取消', 5),
    ('stocktake_line_status', 'PENDING', '未盘', 1),
    ('stocktake_line_status', 'MATCHED', '相符', 2),
    ('stocktake_line_status', 'DIFF', '有差异', 3),
    ('stocktake_line_status', 'ADJUSTED', '已调整', 4),
    ('merchant_alert_type', 'LOW_STOCK', '低库存', 1),
    ('merchant_alert_type', 'EXPIRY', '临期', 2),
    ('merchant_alert_type', 'REPLENISHMENT_REQUIRED', '需补货', 3),
    ('merchant_alert_type', 'REPLENISHMENT', '补货任务', 4),
    ('merchant_alert_type', 'DEVICE_OFFLINE', '柜机离线', 5),
    ('merchant_alert_type', 'DEVICE_FAULT', '柜机故障', 6),
    ('merchant_alert_type', 'DISPUTE', '消费争议', 7),
    ('merchant_alert_type', 'SETTLEMENT_FAILED', '结算失败', 8)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, row_number() OVER (ORDER BY v.sort_order), 'ACTIVE'
FROM (VALUES
    ('wallet_entry_type'::varchar, 'ADJUST'::varchar,            '运营调整'::varchar, 1),
    ('wallet_entry_type',          'COMMISSION',                 '佣金入账',          2),
    ('wallet_entry_type',          'COMMISSION_DAILY',           '日结佣金',          3),
    ('wallet_entry_type',          'WITHDRAW_FREEZE',            '提现冻结',          4),
    ('wallet_entry_type',          'WITHDRAW_RELEASE',           '提现解冻',          5),
    ('wallet_entry_type',          'WITHDRAW_PAID',              '提现打款',          6),
    ('wallet_entry_type',          'SPLIT_CREDIT',               '分账入账',          7),
    ('wallet_entry_type',          'SPLIT_REVERSE',              '分账退回',          8),
    ('wallet_entry_type',          'RECHARGE',                   '充值',              9),
    ('wallet_entry_type',          'RECHARGE_REFUND',            '充值退款',          10),
    ('wallet_entry_type',          'REFUND',                     '退款',              11),
    ('wallet_entry_type',          'SETTLE',                     '结算入账',          12),
    ('wallet_entry_type',          'PAYOUT',                     '打款',              13),
    ('wallet_entry_type',          'FEE',                        '手续费',            14)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
