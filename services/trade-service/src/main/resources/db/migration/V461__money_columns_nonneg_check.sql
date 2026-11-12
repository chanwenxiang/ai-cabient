-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low（ADD CONSTRAINT … NOT VALID 只取短暂的 ACCESS EXCLUSIVE 完成元数据登记；
--             VALIDATE CONSTRAINT 持 SHARE UPDATE EXCLUSIVE —— 不阻塞读写，只是全表扫一遍）
-- TABLES: cabinet_order (dev 44 行/生产按量级评估)、line_withdraw_request、merchant_withdraw_request
-- NOTES: R19-13（四端审计 2026-11-12）第一小批——先给「金额即资金事实」的三列上 DB 级兜底，
--        与 V457（库存 quantity >= 0）、V454（wallet_ledger 唯一 ref）同一设计取向：
--        应用层的回绕/取负/静默舍入只能事后发现，CHECK 让它在写入那刻直接失败。
--        全库 116 个金额列缺 >=0 约束，这里只收口最上游的三处（订单总额 + 两条提现单金额）；
--        其余按「该列是否真的允许负数」逐表判定，不搞一刀切。
--        上线前置预检（dev 库 2026-11-12 实跑，三表负值均为 0）：
--          SELECT COUNT(*) FROM cabinet_order            WHERE total_amount_cents < 0;
--          SELECT COUNT(*) FROM line_withdraw_request    WHERE amount_cents < 0;
--          SELECT COUNT(*) FROM merchant_withdraw_request WHERE amount_cents < 0;
--        上线当时若已有负值，VALIDATE 会 fail-closed 失败——先查明成因，不要 NOT VALID 放着不管。

ALTER TABLE cabinet_order
    ADD CONSTRAINT ck_cabinet_order_total_amount_nonneg
    CHECK (total_amount_cents >= 0) NOT VALID;

ALTER TABLE line_withdraw_request
    ADD CONSTRAINT ck_line_withdraw_amount_nonneg
    CHECK (amount_cents >= 0) NOT VALID;

ALTER TABLE merchant_withdraw_request
    ADD CONSTRAINT ck_merchant_withdraw_amount_nonneg
    CHECK (amount_cents >= 0) NOT VALID;

ALTER TABLE cabinet_order VALIDATE CONSTRAINT ck_cabinet_order_total_amount_nonneg;
ALTER TABLE line_withdraw_request VALIDATE CONSTRAINT ck_line_withdraw_amount_nonneg;
ALTER TABLE merchant_withdraw_request VALIDATE CONSTRAINT ck_merchant_withdraw_amount_nonneg;
