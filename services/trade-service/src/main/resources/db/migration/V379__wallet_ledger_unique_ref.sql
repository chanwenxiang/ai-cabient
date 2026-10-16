-- MIGRATION_KIND: schema
-- B-1 扩展审 F-1（2026-10-16，docs/AUDIT_B1_EXTENDED_FUND_2026-10-16.md）：
-- 三张钱包流水表补「请求幂等」的 **DB 级最后防线**。
--
-- 为什么应用层两层不够（取证，非推断）：
--   · 全部出/入账幂等只有「findByRef 查重 + Redisson 分布式锁」两层；
--   · 锁在 `@Transactional` 方法体的 finally 里 unlock，**早于事务提交**——后一个线程拿到锁时，
--     前一笔流水尚未可见，findByRef 查不到 ⇒ 同一 refId 双扣/双入账；`FOR UPDATE` 的行锁
--     发生在查重**之后**，拦不住这个窗口。
--   · 2026-10-16 用真库 IT 证实：同 (staff_user_id, ref_type, ref_id) 的第二行插入成功
--     （WalletRefIdempotencySqlIT#duplicateRefRowIsRejectedAtDbLevel 负向红）。
--   与 V305 / V301 同一思路：幂等不能只活在代码里。
--
-- 为什么排除 ref_type='WITHDRAW'（提现生命周期天然一 ref 多行）：
--   一笔提现请求会写 WITHDRAW_FREEZE / WITHDRAW_RELEASE / WITHDRAW_PAID / WITHDRAW_FEE 多行，
--   且「打款失败释放 → 重试打款重新冻结」(MerchantWithdrawService:605 / LineWithdrawService:421)
--   会产生**第二条同 entry_type、同 ref 的 FREEZE 行** ⇒ 把 entry_type 放进唯一键也照样误伤。
--   被约束的 ref_type 集合恰是 `*IfAbsent` 系幂等路径（COMMISSION_DAILY / STAFF_COMMISSION_DAILY /
--   SETTLEMENT_FEE / COMMISSION_CLAWBACK / SPLIT* / RENTAL_DEPOSIT* / LINE_PROMO / SEED /
--   STAFF_WITHDRAW / OPS_ADJUST），逐一核过调用点，均为「一请求一流水」。
--   补 IT 用例 withdrawLifecycleRowsMayRepeatRef 钉住这条豁免，防止日后收紧误伤提现。

-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: staff_wallet_ledger (~0 行)、line_wallet_ledger (~0 行)、merchant_wallet_ledger (1 行)
-- NOTES: 三表均不在 check-migration-safety 的 HOT_TABLES 名单；建索引只取 SHARE 锁、行数演示级，
--        短锁可接受（对齐 V305 先例：Flyway 社区版事务内不能跑 CREATE INDEX CONCURRENTLY）。
--        先显式检出历史重复再建索引——流水行是资金记录，禁止像 V301 那样自动合并/置空，
--        发现重复=真实双扣事故，必须人工核实后重跑本迁移。
-- ROLLBACK: DROP INDEX uk_staff_wallet_ledger_ref / uk_line_wallet_ledger_ref / uk_merchant_wallet_ledger_ref

-- ==== 1. 历史重复检出（有重复即当场红，绝不静默建索引）====
DO $$
DECLARE
    dupes text;
BEGIN
    SELECT string_agg(x, ', ' ORDER BY x) INTO dupes FROM (
        SELECT staff_user_id || '|' || ref_type || '|' || ref_id AS x
          FROM staff_wallet_ledger
         WHERE ref_type IS NOT NULL AND ref_id IS NOT NULL AND ref_type <> 'WITHDRAW'
         GROUP BY staff_user_id, ref_type, ref_id HAVING count(*) > 1
    ) d;
    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'staff_wallet_ledger 存在同 ref 重复流水，禁止盲建唯一索引：%', dupes
            USING HINT = '重复流水=同请求双扣/双入账（资金记录，禁止自动合并）：逐笔核实后人工处置，再重跑本迁移';
    END IF;
END
$$;

DO $$
DECLARE
    dupes text;
BEGIN
    SELECT string_agg(x, ', ' ORDER BY x) INTO dupes FROM (
        SELECT manager_id || '|' || ref_type || '|' || ref_id AS x
          FROM line_wallet_ledger
         WHERE ref_type IS NOT NULL AND ref_id IS NOT NULL AND ref_type <> 'WITHDRAW'
         GROUP BY manager_id, ref_type, ref_id HAVING count(*) > 1
    ) d;
    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'line_wallet_ledger 存在同 ref 重复流水，禁止盲建唯一索引：%', dupes
            USING HINT = '重复流水=同请求双扣/双入账（资金记录，禁止自动合并）：逐笔核实后人工处置，再重跑本迁移';
    END IF;
END
$$;

DO $$
DECLARE
    dupes text;
BEGIN
    SELECT string_agg(x, ', ' ORDER BY x) INTO dupes FROM (
        SELECT merchant_id || '|' || ref_type || '|' || ref_id AS x
          FROM merchant_wallet_ledger
         WHERE ref_type IS NOT NULL AND ref_id IS NOT NULL AND ref_type <> 'WITHDRAW'
         GROUP BY merchant_id, ref_type, ref_id HAVING count(*) > 1
    ) d;
    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'merchant_wallet_ledger 存在同 ref 重复流水，禁止盲建唯一索引：%', dupes
            USING HINT = '重复流水=同请求双扣/双入账（资金记录，禁止自动合并）：逐笔核实后人工处置，再重跑本迁移';
    END IF;
END
$$;

-- ==== 2. 幂等兜底唯一索引（partial：手工/无 ref 流水不受约束）====
CREATE UNIQUE INDEX IF NOT EXISTS uk_staff_wallet_ledger_ref
    ON staff_wallet_ledger (staff_user_id, ref_type, ref_id)
    WHERE ref_type IS NOT NULL AND ref_id IS NOT NULL AND ref_type <> 'WITHDRAW';

CREATE UNIQUE INDEX IF NOT EXISTS uk_line_wallet_ledger_ref
    ON line_wallet_ledger (manager_id, ref_type, ref_id)
    WHERE ref_type IS NOT NULL AND ref_id IS NOT NULL AND ref_type <> 'WITHDRAW';

CREATE UNIQUE INDEX IF NOT EXISTS uk_merchant_wallet_ledger_ref
    ON merchant_wallet_ledger (merchant_id, ref_type, ref_id)
    WHERE ref_type IS NOT NULL AND ref_id IS NOT NULL AND ref_type <> 'WITHDRAW';

COMMENT ON INDEX uk_staff_wallet_ledger_ref IS
    '补货员钱包流水幂等兜底（B-1 F-1）：同请求同 ref 只允许一条流水；提现生命周期 ref_type=WITHDRAW 天然多行，故排除';
COMMENT ON INDEX uk_line_wallet_ledger_ref IS
    '线长钱包流水幂等兜底（B-1 F-1）：同请求同 ref 只允许一条流水；提现生命周期 ref_type=WITHDRAW 天然多行，故排除';
COMMENT ON INDEX uk_merchant_wallet_ledger_ref IS
    '商户钱包流水幂等兜底（B-1 F-1）：同请求同 ref 只允许一条流水；提现生命周期 ref_type=WITHDRAW 天然多行，故排除（普通索引 idx_merchant_wallet_ledger_ref 保留作查询加速）';
