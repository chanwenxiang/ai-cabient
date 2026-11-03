-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: sys_dict_type (+10 类型) | sys_dict_data (+45 词条) | wallet_entry_type.SPLIT_REVERSE 标签统一
-- LOCK_RISK: low（sys_dict 小表，无热路径写入；全部 insert-if-absent / 带标签守卫的 UPDATE）
-- ROLLBACK: DELETE FROM sys_dict_data WHERE dict_type IN (下述 10 类型)；DELETE FROM sys_dict_type 同集；
--           UPDATE sys_dict_data SET dict_label='分账退回' WHERE dict_type='wallet_entry_type' AND dict_value='SPLIT_REVERSE'
--           （注意：运营在字典管理改过标签的行不会被本迁移触碰，回滚亦同）
-- NOTES: MA-145（基线独有 10 类型从未种子：balance_refund_status/user_role/wallet_ref_type/
--        wallet_ledger_type/consistency_diag_code/merchant_settlement_bill_status/close_sheet_status/
--        close_sheet_opening_source/close_sheet_disposition/expiry_urgency）+ MA-140（wallet_ref_type
--        补 SPLIT_REV）+ MA-141（SPLIT_REVERSE 三源统一「分账冲正」，UPDATE 只动默认标签，V415 范式）。
--        三源一致义务：本迁移每个值必须同时存在于 SysDictBootstrap.seedCatalog() 与
--        packages/shared-dict/src/index.ts（两处已同批改齐）。
--        依赖：wallet_entry_type 的 SPLIT_REVERSE 行由 V171 保证存在。

-- ── 类型行先落（FK）──
INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT v.dict_type, v.dict_name, 'ACTIVE', 900, 'MA-145：shared-dict 基线对齐回填'
FROM (VALUES
    ('balance_refund_status', '余额退款状态'),
    ('user_role', '用户角色'),
    ('wallet_ref_type', '钱包关联类型'),
    ('wallet_ledger_type', '钱包账务类型'),
    ('consistency_diag_code', '一致性诊断码'),
    ('merchant_settlement_bill_status', '商户结算单状态'),
    ('close_sheet_status', '月结单状态'),
    ('close_sheet_opening_source', '月结期初来源'),
    ('close_sheet_disposition', '月结差异处置'),
    ('expiry_urgency', '临期紧急度')
) AS v(dict_type, dict_name)
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_type = v.dict_type);

-- ── 词条（insert-if-absent；运营改过的标签不受影响）──
INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE'
FROM (VALUES
    ('balance_refund_status', 'PENDING_REVIEW', '待审核', 1),
    ('balance_refund_status', 'REFUNDED', '已退款', 2),
    ('balance_refund_status', 'REJECTED', '已驳回', 3),
    ('balance_refund_status', 'FAILED', '失败', 4),
    ('user_role', 'CONSUMER', '消费者', 1),
    ('user_role', 'OPERATOR', '运营账号', 2),
    ('user_role', 'MERCHANT', '商户', 3),
    ('user_role', 'OPS', '运营', 4),
    ('wallet_ref_type', 'ORDER', '订单', 1),
    ('wallet_ref_type', 'WITHDRAW', '提现', 2),
    ('wallet_ref_type', 'OPS_ADJUST', '运营调账', 3),
    ('wallet_ref_type', 'COMMISSION_DAILY', '日结佣金', 4),
    ('wallet_ref_type', 'SPLIT', '分账', 5),
    ('wallet_ref_type', 'SPLIT_PARTIAL', '分账增额', 6),
    ('wallet_ref_type', 'SPLIT_PARTIAL_REV', '分账增额冲正', 7),
    ('wallet_ref_type', 'SPLIT_REV', '分账冲正', 8),
    ('wallet_ref_type', 'RECHARGE', '充值', 9),
    ('wallet_ref_type', 'REFUND', '退款', 10),
    ('wallet_ref_type', 'SEED', '演示初始', 11),
    ('wallet_ledger_type', 'MERCHANT_CREDIT', '商户入账', 1),
    ('wallet_ledger_type', 'LINE_COMMISSION', '线长佣金', 2),
    ('wallet_ledger_type', 'WITHDRAW_FREEZE', '提现冻结', 3),
    ('wallet_ledger_type', 'WITHDRAW_RELEASE', '提现解冻', 4),
    ('wallet_ledger_type', 'WITHDRAW_PAID', '提现打款', 5),
    ('wallet_ledger_type', 'ADJUST', '调账', 6),
    ('wallet_ledger_type', 'REVERSE', '冲正', 7),
    ('wallet_ledger_type', 'SPLIT_CREDIT', '分账入账', 8),
    ('wallet_ledger_type', 'SPLIT_PARTIAL_CREDIT', '分账增额入账', 9),
    ('wallet_ledger_type', 'SPLIT_REVERSE', '分账冲正', 10),
    ('wallet_ledger_type', 'SPLIT_PARTIAL_REVERSE', '分账增额冲正', 11),
    ('consistency_diag_code', 'SALE_OK', '应有 SALE 扣库流水', 1),
    ('consistency_diag_code', 'MISSING_SALE', '缺少 SALE 扣库流水', 2),
    ('consistency_diag_code', 'INVENTORY_FLAG', '未标记已扣库', 3),
    ('consistency_diag_code', 'REFUND_OR_KEPT', '应有退款/留货库存流水', 4),
    ('consistency_diag_code', 'MISSING', '缺少退款/留货库存流水', 5),
    ('consistency_diag_code', 'LINKED', '应有关联订单', 6),
    ('merchant_settlement_bill_status', 'PENDING', '待确认', 1),
    ('merchant_settlement_bill_status', 'CONFIRMED', '已确认', 2),
    ('close_sheet_status', 'DRAFT', '未审批', 1),
    ('close_sheet_status', 'APPROVED', '已审批', 2),
    ('close_sheet_opening_source', 'PERIOD_CLOSE', '上期实盘结转', 1),
    ('close_sheet_opening_source', 'BOOK', '账面结转', 2),
    ('close_sheet_disposition', 'PENDING', '待认定', 1),
    ('close_sheet_disposition', 'CLAIM', '已索赔', 2),
    ('close_sheet_disposition', 'NORMAL_LOSS', '正常损耗', 3),
    ('close_sheet_disposition', 'SURPLUS', '盘盈待查', 4),
    ('expiry_urgency', 'EXPIRED', '已过期', 1),
    ('expiry_urgency', 'URGENT', '紧急（≤7天）', 2),
    ('expiry_urgency', 'SOON', '临近（≤30天）', 3),
    ('expiry_urgency', 'NORMAL', '正常', 4)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

-- ── MA-141：SPLIT_REVERSE 标签三源统一「分账冲正」──
-- 只动仍是默认标签的行；运营自定义过的标签不覆盖（V415 范式）
UPDATE sys_dict_data SET dict_label = '分账冲正', updated_at = NOW()
WHERE dict_type = 'wallet_entry_type' AND dict_value = 'SPLIT_REVERSE'
  AND dict_label = '分账退回';
