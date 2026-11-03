-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: sys_dict_type (+1 类型) | sys_dict_data (+10 行，均为字典缺项补种)
-- LOCK_RISK: low（sys_dict 是小表，无热路径写入）
-- ROLLBACK: DELETE FROM sys_dict_data WHERE (dict_type,dict_value) IN (本文件列出的 10 组);
--           DELETE FROM sys_dict_type WHERE dict_type='device_rental_bill_status';
-- NOTES: 一次性补齐「后端在写、字典没有」的枚举值；新库从 V171/V225 + 本迁移得到同一套，
--        存量库由本迁移补齐；运行期还有 SysDictBootstrap 自愈兜底（三源必须同名同词）。
-- MA-132/133/134/135/131（批次J·字典回填族）：把**后端真实写入但字典里从来没有**的枚举值补进 sys_dict。
--
-- 为什么这是缺陷而不是「显示问题」：这些值都在资金/单据链上真实产生
--   · COMMISSION_CLAWBACK —— CommissionClawbackService（佣金随退款冲回，进可提现钱包）
--   · WITHDRAW_FEE        —— WalletEngine（商户/线长提现手续费，真扣款）
--   · WITHDRAW_PAYOUT     —— OpsStaffWalletController→StaffWalletService（补货员线下打款出账）
--   · DAILY_INCOME / FEE_REMINDER —— MerchantDailyIncomeNotifyScheduler（每日批量落库的高频站内信）
--   · PENDING_APPROVAL / REJECTED —— ProcurementService（采购单审批链）
-- 字典缺项时 admin 资金页/站内信页显示「未知」，merchant-mp 靠自造 map 或 remark 兜底出中文
-- ⇒ **同一条流水在两端不同词**，运营与商户对账时各说各话。
--
-- 另新建 dict_type `device_rental_bill_status`（MA-131）：租赁账单状态此前**无任何字典**，
-- admin 页面自造 map 把作废写成 `VOIDED`，而后端常量是 FEE_BILL_STATUS_VOID=`VOID`
-- ⇒ 状态列裸显 "VOID" 且「已作废」筛选恒查空（筛选值一路传到 mapper 的 eq(status)）。
-- 词表与同族 site_rent_bill_status / device_data_fee_bill_status 对齐（待付/已付/已作废）。
--
-- 幂等：照 V171/V225 先例，逐值 WHERE NOT EXISTS（重复执行不产生第二行）。
-- 同步义务：本迁移的每个值都必须同时出现在 SysDictBootstrap.seedCatalog()
-- 与 packages/shared-dict 基线里（三源一致，见 check:warehouse-movement-dict 同族判据）。

-- 类型行必须在这里也兜一遍：全新库里 purchase_order_status 等类型**只由运行期
-- SysDictBootstrap 自愈补出，迁移里没有**（dict_data 有 FK 指向 dict_type，
-- 靠 bootstrap 建类型意味着「迁移先跑、类型还没建」时必然 FK 违约——这一条是被
-- DictLabelUniquenessIT 的 fresh-DB Flyway 链抓出来的，静态门禁看不见）。
INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT v.dict_type, v.dict_name, 'ACTIVE', 0, v.remark
FROM (VALUES
    ('wallet_entry_type'::varchar,        '钱包流水类型'::varchar,    'wallet_ledger.entry_type'::varchar),
    ('notification_biz_type',             '站内信业务类型',            'notification_log.biz_type'),
    ('purchase_order_status',             '采购单状态',                'purchase_order.status'),
    ('device_rental_bill_status',         '设备租赁账单状态',          'device_rental_bill.status')
) AS v(dict_type, dict_name, remark)
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_type = v.dict_type);

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE'
FROM (VALUES
    ('wallet_entry_type'::varchar, 'COMMISSION_CLAWBACK'::varchar, '佣金冲回'::varchar, 15),
    ('wallet_entry_type',          'WITHDRAW_FEE',                 '提现手续费',        16),
    ('wallet_entry_type',          'WITHDRAW_PAYOUT',              '提现出账',          17),
    ('notification_biz_type',      'DAILY_INCOME',                 '日结收入',          12),
    ('notification_biz_type',      'FEE_REMINDER',                 '欠费提醒',          13),
    ('purchase_order_status',      'PENDING_APPROVAL',             '待审批',            5),
    ('purchase_order_status',      'REJECTED',                     '已驳回',            6),
    ('device_rental_bill_status',  'UNPAID',                       '待付',              1),
    ('device_rental_bill_status',  'PAID',                         '已付',              2),
    ('device_rental_bill_status',  'VOID',                         '已作废',            3)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
