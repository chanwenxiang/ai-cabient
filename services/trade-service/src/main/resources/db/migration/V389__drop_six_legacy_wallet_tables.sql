-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: merchant_wallet_account/ledger、line_wallet_account/ledger、staff_wallet_account/ledger（各演示级行数，V388 已搬运到 wallet_*）
-- COMPETITOR_REF: docs/WALLET_UNIFY_DECISION_BRIEF_2026-10-18.md（§12 决策 #1 = A 案，三域钱包物理统一）
-- NOTES: 三域 6 张旧表已由 V388 全量搬到 wallet_account / wallet_ledger（行数逐一核对，见下 assert）。
--        删除旧表连带删除：
--          · merchant_wallet_account / merchant_wallet_ledger → merchant(merchant_id) 的外键（V212 CASCADE）。
--            统一多态表无法把 line/staff 的 owner_id 也 FK 进 merchant，故**有意降级为应用层保证**：
--            MerchantWalletService 的建账/入账路径本就要求商户存在，且全仓无 merchant 删除代码路径
--            （CASCADE 只在手工删商户时触发）。资金侧更稳：流水不再随商户被级联清除（留审计）。
--          · V379 的三张 partial uk_*_wallet_ledger_ref、V139/V133/V359 的 owner/ref 索引（CASCADE 自动移除）。
--        DROP TABLE 取 ACCESS EXCLUSIVE 锁；行数演示级、建/删瞬时，短锁可接受。
--        先断言「新旧行数一致」再删——不等即当场红，禁止带数据缺口删表（资金记录，V379 同纪律）。
-- ROLLBACK: 撤销 = 从 wallet_* 反灌回旧 6 表（domain 拆列）；本迁移前旧表仍在，V388 是纯只读源。

-- ==== 1. 搬运完整性断言：新表分域行数 == 旧表行数，缺一即当场红 ====
DO $$
DECLARE
    n_new bigint;
    n_old bigint;
BEGIN
    SELECT count(*) INTO n_new FROM wallet_account WHERE domain = 'MERCHANT';
    SELECT count(*) INTO n_old FROM merchant_wallet_account;
    IF n_new <> n_old THEN
        RAISE EXCEPTION 'wallet_account(MERCHANT)=% != merchant_wallet_account=%，禁止删旧表', n_new, n_old;
    END IF;

    SELECT count(*) INTO n_new FROM wallet_account WHERE domain = 'LINE';
    SELECT count(*) INTO n_old FROM line_wallet_account;
    IF n_new <> n_old THEN
        RAISE EXCEPTION 'wallet_account(LINE)=% != line_wallet_account=%，禁止删旧表', n_new, n_old;
    END IF;

    SELECT count(*) INTO n_new FROM wallet_account WHERE domain = 'STAFF';
    SELECT count(*) INTO n_old FROM staff_wallet_account;
    IF n_new <> n_old THEN
        RAISE EXCEPTION 'wallet_account(STAFF)=% != staff_wallet_account=%，禁止删旧表', n_new, n_old;
    END IF;

    SELECT count(*) INTO n_new FROM wallet_ledger WHERE domain = 'MERCHANT';
    SELECT count(*) INTO n_old FROM merchant_wallet_ledger;
    IF n_new <> n_old THEN
        RAISE EXCEPTION 'wallet_ledger(MERCHANT)=% != merchant_wallet_ledger=%，禁止删旧表', n_new, n_old;
    END IF;

    SELECT count(*) INTO n_new FROM wallet_ledger WHERE domain = 'LINE';
    SELECT count(*) INTO n_old FROM line_wallet_ledger;
    IF n_new <> n_old THEN
        RAISE EXCEPTION 'wallet_ledger(LINE)=% != line_wallet_ledger=%，禁止删旧表', n_new, n_old;
    END IF;

    SELECT count(*) INTO n_new FROM wallet_ledger WHERE domain = 'STAFF';
    SELECT count(*) INTO n_old FROM staff_wallet_ledger;
    IF n_new <> n_old THEN
        RAISE EXCEPTION 'wallet_ledger(STAFF)=% != staff_wallet_ledger=%，禁止删旧表', n_new, n_old;
    END IF;
END
$$;

-- ==== 2. 删除六张旧表（CASCADE 连带删外键/索引/uk）====
DROP TABLE IF EXISTS merchant_wallet_ledger CASCADE;
DROP TABLE IF EXISTS merchant_wallet_account CASCADE;
DROP TABLE IF EXISTS line_wallet_ledger CASCADE;
DROP TABLE IF EXISTS line_wallet_account CASCADE;
DROP TABLE IF EXISTS staff_wallet_ledger CASCADE;
DROP TABLE IF EXISTS staff_wallet_account CASCADE;
