-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-064
-- MIGRATION_REVIEWED: yes
-- TABLES: payscore_order（开门期支付分单；历史行数量小）
-- LOCK_RISK: low（ALTER COLUMN DROP NOT NULL 只改元数据；PG 11+ 不重写表，ACCESS EXCLUSIVE 仅语句瞬间）
-- ROLLBACK: 先确认 SELECT count(*) FROM payscore_order WHERE order_id IS NULL = 0，再 ALTER TABLE payscore_order ALTER COLUMN order_id SET NOT NULL
-- NOTES: 2026-11-18 拍板。官方免确认四步契约要求「开门前创单评估，评估不过不可使用服务」
--   （CB-064 ②创单），而此时柜机订单尚未生成，payscore_order.order_id 必然为空；
--   V74 把它建成 NOT NULL + FK 到 cabinet_order，导致 PAYSCORE_ENABLED=true 时每次开门
--   insert 违约，被包成 412「微信免密确认失败」。
--   保留 FK（PG 外键允许 NULL），仅放开 NOT NULL；结算时 PayScoreService#completeWechatOrderForSession
--   仍会回填真实 orderId。不采用「结算时才建单」——那会让未通过风险金评估的用户先进柜。

ALTER TABLE payscore_order ALTER COLUMN order_id DROP NOT NULL;
