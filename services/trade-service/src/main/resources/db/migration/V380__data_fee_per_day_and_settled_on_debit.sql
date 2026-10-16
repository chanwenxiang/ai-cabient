-- MIGRATION_KIND: schema
-- F-2 / F-3 拍板落地（2026-10-16，docs/AUDIT_F2_F3_F4_DECISION_BRIEF_2026-10-16.md §7）
-- 本迁移**只改注释口径 + 加语义护栏**，不改列类型、不做单位换算——
-- 因为「分/月 → 分/天」是同一列的语义切换，任何自动换算都会在错误的月份天数上造出错误金额。
--
-- ① device_info.data_fee_cents：语义由「分/月」改为「分/天」。
--    依据（老系统取证，docs/OLD_EASYGO_MONEY_LOGIC_2026-10-16.md §3 L1/L2/L3）：
--    流量费是**逐台每日**从商户余额扣的（MobileCardFeeGenerationTask 每日跑、按 machineId 出账），
--    全程不读 machine_use_type ⇒ 不分租赁/买断都收；配置项允许 0（仅 <0 回退默认档）。
--    ⚠ 对存量非 0 值这是 30 倍风险（把月额当成日额=每天多扣约 30 倍），
--    故用 DO 块**硬拦**：存在 data_fee_cents > 0 的行即让整个迁移失败，
--    要求运营先人工按「月额 ÷ 当月天数」改数后重跑。
--    dev 现网实测 0 行、全新空库天然 0 行（2026-10-16 psql 取证），护栏不会挡住正常重建。
--
-- ② merchant_fee_ledger：状态口径改为「扣款成功即 SETTLED」。
--    老系统 L5「先扣到钱才写行、行即收据、无状态列」；我们此前是「先写 PENDING 行再扣款」
--    ⇒ 扣款抛异常仍留下一条从未扣到的欠费行（F-2 缺陷）。
--    改后 0/NULL 费用不进扣款路径、不留行（Z 口径：0=不收/没有 是一等态）。
--    同理硬拦历史 PENDING 行：那些行按旧顺序写就**无法证明扣到过钱**，
--    若被新判据（账龄满 lock_days 锁柜）当欠费使用会误锁商户柜机 ⇒ 必须人工核实后
--    线下收款 settle 或按记录清账，再重跑本迁移。dev 实测 0 行。
--
-- ③ 锁柜阈值：判据从「金额（月费 × merchant.overdue.lock_months）」改为
--    「未结清最老一行的账龄 ≥ merchant.overdue.lock_days（默认 7）」。
--    阈值键是 Java 侧 SystemConfigService 种子（不落 SQL，避开 seed 门禁），
--    本迁移只把库里注释与旧键说明对齐——旧键描述由代码刷成【已废弃·请勿使用】。
--    竞品先例：阿里云「欠费满 7 个自然日停服 / 补足欠款可正常恢复使用」、
--    智能接入网关满 5 天停机（老系统**无**欠费锁柜先例，此为我方设计判断，见 CB-041 修订）。

-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: device_info (~9 行)、merchant_fee_ledger (0 行)
-- NOTES: 仅 COMMENT ON COLUMN + 只读 DO 块探测，无 ALTER/索引，不触碰 check-migration-safety
--        HOT_TABLES 的锁路径。两个 DO 块是**故意的 fail-fast**：语义切换若撞上存量非 0 数据，
--        宁可让 Flyway 红并提示人工换算，也不要静默把商户按 30 倍日额扣穿。
-- ROLLBACK: COMMENT ON COLUMN device_info.data_fee_cents IS '流量费（分/月）'（并回退代码，勿单独回滚注释）

-- ① 语义护栏：存量非 0 流量费必须先人工换算
DO $$
DECLARE
    v_rows bigint;
BEGIN
    SELECT count(*) INTO v_rows FROM device_info WHERE data_fee_cents IS NOT NULL AND data_fee_cents > 0;
    IF v_rows > 0 THEN
        RAISE EXCEPTION
            'V380 语义切换被拦：device_info 有 % 行 data_fee_cents > 0。'
            '该列口径已从「分/月」改为「分/天」，直接沿用会把商户按约 30 倍日额扣款。'
            '请先人工把这些行的 data_fee_cents 改为「原月额 ÷ 当月天数」（并核对是否要按投放天数折算），'
            '确认后再重跑本迁移。', v_rows;
    END IF;
END $$;

-- ② 语义护栏：历史 PENDING 费用行无法证明扣到过钱
DO $$
DECLARE
    v_rows bigint;
BEGIN
    SELECT count(*) INTO v_rows FROM merchant_fee_ledger WHERE status = 'PENDING';
    IF v_rows > 0 THEN
        RAISE EXCEPTION
            'V380 状态口径切换被拦：merchant_fee_ledger 有 % 行 PENDING。'
            '旧顺序「先写行再扣款」写下的 PENDING 行不能证明实际扣到过钱，'
            '而新判据会把账龄满 lock_days 的 PENDING 行用于锁柜（误锁风险）。'
            '请逐行核实：确实扣到过款→置 SETTLED；线下收款→调 settle；从未扣款→按记录清账。完成后重跑。', v_rows;
    END IF;
END $$;

COMMENT ON COLUMN device_info.data_fee_cents IS
    '柜机流量费（分/天，2026-10-16 F-3 拍板；老系统逐台每日扣，不分租赁/买断）；0 或 NULL=这台柜不收/没配卡，不扣款不记欠不锁柜';

COMMENT ON COLUMN merchant_fee_ledger.status IS
    'SETTLED=当日已从商户钱包扣款成功（扣款成功后才写行，行即收据，对齐老系统 L5）；'
    'PENDING 仅用于人工补录的历史/例外行，账龄满 merchant.overdue.lock_days 触发欠费锁柜，结清自动复机';

COMMENT ON COLUMN merchant_fee_ledger.amount_cents IS
    '当日实扣金额（分）：租金=月额 ÷ 当月天数（仅 coopMode=RENTAL），流量费=data_fee_cents 日额原值（不除天数）；'
    '费用为 0/未配置的柜机不写行';
