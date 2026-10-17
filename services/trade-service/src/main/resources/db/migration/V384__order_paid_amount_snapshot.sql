-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-057
-- F-9 拍板落地（2026-10-17，用户选「收款时冻结毛额（新列）」）：
-- `cabinet_order` 新增 `paid_amount_cents`，在**扣款成功时点**冻结订单毛额；
-- 退款、按行部分退、争议改单一律**不得改写**它。佣金/GMV 净额改算
-- `max(0, 冻结毛额 − refunded_cents)`，不再用 `total_amount_cents − refunded_cents`。
--
-- 为什么要这一列（病灶）：`total_amount_cents` 会被三条路径**就地回写**——
--   ① SettlementOrderSupport.applyItemsToOrder/recalculatePayableAfterLineChange（结算与争议改单）；
--   ② SettlementPartialRefundService:120-122（按行部分退先把应付摊薄再记退款额）；
--   ③ OrderPaymentService.applyPaymentDelta:479-508（争议改单降价：改单已降 total，
--      负 delta 又走 refundOrder 累加 refunded_cents）。
-- 而 CommissionBaseCalc 把这一列当「成交额」用，于是「原 1000 退 700」的单
-- 净额算成 0（应为 300）⇒ 同一笔退款在佣金基数里**被扣两遍**，
-- LineCommissionJob:181,285 与 CommissionClawbackService:288,292 的 GMV 一起错。
-- `originalAmountCents` 是 @TableField(exist=false) 瞬态字段且同样被改单重写，不能充当冻结值。
--
-- 依据（取证见 CB-057）：
--   微信支付交易账单把「订单金额」与「退款金额」**分列**，退款/撤销行的订单金额填 0.00；
--   支付宝对账口径「成交订单 = 已付款成功的订单金额汇总（**未扣除退款订单金额**）」，
--     且「发生支付、退款时会产生**新的记录**」——退款不改旧行；
--   有赞「订单实付金额不剔除退款金额」（收款口径与退款口径两列）；
--   老系统 easygo 直接把订单金额列写成
--     `@Column(length=11, updatable=false, nullable=false) Integer orderTotalAmount`
--     （MachineOrderDetail.java:38-39，ORM 层面物理禁止回写），退款另立 StatFinRefundDetail
--     的 orderTotalAmount/refundAmount 两列，日账单 GMV = Σ order_total_amount − 退款行
--     （BillService.java:424）⇒ 我方与老系统、三家官方同构。
--
-- 冻结值定义：`payment_operation` 中 status=COMPLETED 且类型 ∈
--   {CHARGE, ADJUST_CHARGE, PREAUTH_CAPTURE} 的 amount_cents 之和（= 实际收到的毛额，不含退款）。
--   该定义与 Java 侧 `PaymentOperationMapper.chargedCompletedCents` 逐字同构（CB-057 结论 2），
--   回填与运行期冻结因此是**同一个数**，不会出现「迁移算一套、代码算一套」。
--   ⚠️ 前置条件 F-10（已修 `5ec4020f`）：净额查询原本漏 PREAUTH_CAPTURE，
--   若未修，只被开门预授权冲抵过的单会回填偏少。
--   欠款单（PENDING）没有扣款流水 ⇒ 留 0，由读侧回落 `total_amount_cents`（Z 口径：0=没收过）。
--
-- 不回改历史分账/佣金行：本列只影响**此后**重算的基数；已入账的 commission/staff_commission
--   行仍按 CB-056「以已入账行为基数」处置。
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: cabinet_order (~0 rows prod-at-rebuild), payment_operation (~0 rows prod-at-rebuild)
-- NOTES: ADD COLUMN ... NOT NULL DEFAULT 0（PG 11+ 走元数据快路径，不重写全表）+ 一条只碰
--        「有收入流水」的行的回填 UPDATE + 只读 DO 断言。cabinet_order 在 HOT_TABLES 名单内，
--        但上线前空库重建、dev 仅 6 行 ⇒ 无长锁风险；断言是**故意的 fail-loud**：
--        冻结毛额若大于「当前应付 + 已退」说明流水与订单对不上，宁可让 Flyway 红并交人工核实，
--        也不要带着错基数发佣。
-- ROLLBACK: ALTER TABLE cabinet_order DROP COLUMN paid_amount_cents（须与 CommissionBaseCalc
--           回读 total、两处冻结调用一并回退，勿单独删列）

ALTER TABLE cabinet_order
    ADD COLUMN IF NOT EXISTS paid_amount_cents INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN cabinet_order.paid_amount_cents IS
    '收款时点冻结的订单毛额（分，CB-057/F-9）：扣款成功时写入，等于该单 COMPLETED 的 '
    'CHARGE/ADJUST_CHARGE/PREAUTH_CAPTURE 流水之和；退款、按行部分退、争议改单**永不改写**它。'
    '佣金/GMV 净额 = max(0, 本列 − refunded_cents)。0=从未扣到过款（欠款单/历史无流水），'
    '读侧回落 total_amount_cents';

-- 回填：优先取流水毛额（唯一能还原「当初实收多少」的口径；已被摊薄的 total 不可信）。
UPDATE cabinet_order o
SET paid_amount_cents = g.gross_cents
FROM (
    SELECT po.order_id,
           SUM(po.amount_cents)::int AS gross_cents
    FROM payment_operation po
    WHERE po.status = 'COMPLETED'
      AND po.operation_type IN ('CHARGE', 'ADJUST_CHARGE', 'PREAUTH_CAPTURE')
      AND po.order_id IS NOT NULL
    GROUP BY po.order_id
) g
WHERE g.order_id = o.order_id
  AND g.gross_cents > 0;

-- ① 零残留断言：列必须真的存在且默认 0（照 V383 的姿势，防「写了迁移却没生效」）。
DO $paid_amount_default$
DECLARE
    d text;
BEGIN
    SELECT column_default::text INTO d FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'cabinet_order'
      AND column_name = 'paid_amount_cents';
    IF d IS DISTINCT FROM '0' THEN
        RAISE EXCEPTION 'V384 断言失败：cabinet_order.paid_amount_cents 列缺失或默认值不是 0（实际 %）', d;
    END IF;
END
$paid_amount_default$;

-- ② fail-loud 一致性护栏：冻结毛额不得大于「当前应付 + 已退」。
--    合法形态里两者最多相等（原 1000 摊薄成 300 + 已退 700）；
--    超过去 ⇒ 流水里有这张单不该收到的钱（挂错 order_id / 重复扣款），
--    带着这种数据发佣就是资损，必须拦下来交人工核实。
DO $paid_amount_bound$
DECLARE
    v_bad bigint;
    v_sample text;
BEGIN
    SELECT count(*) INTO v_bad FROM cabinet_order
    WHERE paid_amount_cents > total_amount_cents + GREATEST(0, refunded_cents);
    IF v_bad > 0 THEN
        SELECT string_agg(t.order_id, ', ' ORDER BY t.order_id) INTO v_sample
        FROM (
            SELECT order_id FROM cabinet_order
            WHERE paid_amount_cents > total_amount_cents + GREATEST(0, refunded_cents)
            ORDER BY order_id LIMIT 10
        ) t;
        RAISE EXCEPTION
            'V384 回填被拦：% 行的冻结毛额 > (total_amount_cents + refunded_cents)，示例：%。'
            '这表示 payment_operation 里存在与本单对不上的收款流水（挂错 order_id、重复扣款或漏记退款）。'
            '请逐单核实流水后置正确（禁止直接改本列迁就 total），再重跑本迁移。', v_bad, v_sample;
    END IF;
END
$paid_amount_bound$;

-- ③ 留痕（不静默）：多少单拿到了冻结毛额、多少单只能靠读侧回落。
DO $paid_amount_summary$
DECLARE
    v_total bigint;
    v_frozen bigint;
BEGIN
    SELECT count(*) INTO v_total FROM cabinet_order;
    SELECT count(*) INTO v_frozen FROM cabinet_order WHERE paid_amount_cents > 0;
    RAISE NOTICE 'V384 paid_amount_cents 回填：订单 % 行，其中 % 行按流水冻结到毛额，% 行留 0 由读侧回落 total',
        v_total, v_frozen, v_total - v_frozen;
END
$paid_amount_summary$;
