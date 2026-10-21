-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: jiangyi_recognition_report（新建空表）
-- COMPETITOR_REF: CB-062
-- 将邑开门柜识别上报「原样落库存档」台账（CB-062，2026-10-21）：
--   · 现状：gateway 收到 orderProduct/addRecognitionGoodsToOrder 后只做「合成 door-event →
--     归一化 → 转发 edge-results」，forms 原文一条不进库；空单号/映射未命中/空 forms 三类
--     只 log.warn，进程重启或日志滚动即永久丢失 ⇒ 「厂商这单报了几个商品、报的什么价、
--     我方实际扣了多少」只能靠结算行反推，识别侧证据链断在内存里。
--   · 采纳同行口径（简单购/微信/支付宝/华为云同向）：入向报文按**原样全字段**留档，
--     append-only、一到达一行，**幂等只作用于结算、不作用于存档**（同一 orderNo 会被
--     重复上报/二次甄别，第 1 次报了什么、第 2 次改成什么都要能看到）。
--   · 金额口径 A 档不变：vendor_amount_cents 只是差异证据，结算金额始终 = Σ(我方 SKU 现价×qty)；
--     真机攒到真实报文后比对两侧价差，再决定是否升 B 档（见 docs/JIANGYI_GATEWAY_DESIGN.md §0）。
--   · 留存：自交易完成之日起不少于三年（电商法 31 条），表内不设删除任务。报文只含商品
--     名/条码/厂商商品 id 等非个人信息，消费者侧仅以我方 sessionId（=order_no）出现。
-- NOTES: 空表 CREATE，无回填；写入方为 gateway 转发链路（fire-and-forget，存档失败不阻断结算），
--        单量 = 柜机订单量级，增长可预测；不建外键（order_no 可为空单号形态，且台账需独立于
--        会话生命周期留存）。重放安全。
-- ROLLBACK: DROP TABLE jiangyi_recognition_report

CREATE TABLE IF NOT EXISTS jiangyi_recognition_report (
    report_id           BIGSERIAL PRIMARY KEY,
    device_id           VARCHAR(64)  NOT NULL,
    order_no            VARCHAR(64),                 -- ≡ 我方 sessionId；空单号报文存 NULL（真机实证存在该形态）
    transaction_id      VARCHAR(64),                 -- 固件 OrderEntity.transactionId，厂商侧流水号
    pay_type            VARCHAR(16),                 -- 固件上报支付方式原值（只留档，不参与结算）
    forms_json          JSONB        NOT NULL,       -- 整段 forms 原文：classId/quantity/thirdPid/price/productName/barCode/txtName/specialId/productCode/floorNum
    items_json          JSONB,                       -- 我方归一化结果 [{skuId,quantity,confidence}]；未命中/空单形态为 NULL
    form_count          INT          NOT NULL DEFAULT 0,
    item_count          INT          NOT NULL DEFAULT 0,
    vendor_amount_cents BIGINT,                      -- Σ(厂商上报 price×qty)，单位分；报文无 price 时 NULL。🔴 只存档不采信
    outcome             VARCHAR(32)  NOT NULL,       -- FORWARDED / MAPPING_MISS / EMPTY_FORMS / BLANK_ORDERNO / FORWARD_FAILED（归一化或转发异常也归此码，留档在 finally 恒执行）
    reported_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_jiangyi_recognition_report_order
    ON jiangyi_recognition_report (order_no, reported_at DESC);
CREATE INDEX IF NOT EXISTS idx_jiangyi_recognition_report_device
    ON jiangyi_recognition_report (device_id, reported_at DESC);
CREATE INDEX IF NOT EXISTS idx_jiangyi_recognition_report_outcome
    ON jiangyi_recognition_report (outcome, reported_at DESC);

COMMENT ON TABLE jiangyi_recognition_report IS
    '将邑识别上报原样留档台账（CB-062）：append-only，一到达一行，不覆写、不设删除任务；留存 ≥3 年';
COMMENT ON COLUMN jiangyi_recognition_report.vendor_amount_cents IS
    '厂商上报价合计（分）——A 档下仅作差异证据与 B 档判据输入，绝不参与结算金额';
COMMENT ON COLUMN jiangyi_recognition_report.outcome IS
    'FORWARDED=已转发结算；MAPPING_MISS=thirdPid 与 classId 均未命中（fail-closed 转人工）；EMPTY_FORMS=没识别到商品；BLANK_ORDERNO=空单号只审计；FORWARD_FAILED=转发 trade 未受理';
