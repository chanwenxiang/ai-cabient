-- module-audit 造数：用户行为分析「沉睡用户」批量样本（MA-001 / MA-002 回归用）
-- COMPETITOR_REF: CB-081
--
-- 口径锚点（当前 HEAD 实证）：
--   · 沉睡判据 = 末单落在 [今天-90, 今天-30) —— UserBehaviorAnalyticsService.summary()
--     的 dormantCutoff / dormantFloor，窗口固定，不随 days 参数漂移（MA-001 修复后）。
--   · 本脚本造 N 个「只有 45 天前一笔 PAID 订单」的用户 ⇒ active30d/new30d 不该数到他们，
--     dormantUsers30d 必须恰好 = N（MA-002 修复前列表被截成 20）。
--   · cabinet_order 触发器 assert_cabinet_order_inventory_link 只在 status∈{PAID,PARTIAL_REFUNDED}
--     **且有 order_line** 时才要 inventory_deducted ⇒ 本脚本刻意不建 order_line，免动库存。
--
-- 纪律：ID 全部走 1990000000+g、编号一律 AUDIT- 前缀；跑完必须执行同目录
--       R01-user-behavior-dormant-cleanup.sql 并断言三个计数归 0，再重打一次
--       GET /api/v2/admin/growth/user-analysis/summary 确认口径已恢复。
BEGIN;

-- 想造多少个沉睡用户就改这里（≤1000，与 UserRecallService.MAX_RECALL_USERS 对齐）
CREATE TEMP TABLE _seed(n) AS VALUES (22);

INSERT INTO user_info (user_id, phone_number, name, created_at, updated_at)
SELECT 1990000000 + g,
       '199' || lpad(g::text, 9, '0'),
       'AUDIT-R1-沉睡-' || g,
       now() - interval '120 days',
       now() - interval '120 days'
FROM generate_series(1, (SELECT n FROM _seed)) AS g;

INSERT INTO shopping_session (session_id, user_id, device_id, state, created_at, updated_at)
SELECT 'AUDIT-R1-SS-' || g,
       1990000000 + g,
       (SELECT device_id FROM device_info ORDER BY device_id LIMIT 1),
       'COMPLETED',
       now() - interval '45 days',
       now() - interval '45 days'
FROM generate_series(1, (SELECT n FROM _seed)) AS g;

INSERT INTO cabinet_order (order_id, session_id, user_id, device_id,
                           total_amount_cents, paid_amount_cents, status,
                           pay_channel, created_at, updated_at, pay_time)
SELECT 'AUDIT-R1-' || lpad(replace(s.session_id, 'AUDIT-R1-SS-', ''), 4, '0'),
       s.session_id,
       s.user_id,
       s.device_id,
       500,
       500,
       'PAID',
       'BALANCE',
       now() - interval '45 days',
       now() - interval '45 days',
       now() - interval '45 days'
FROM shopping_session s
WHERE s.session_id LIKE 'AUDIT-R1-SS-%';

COMMIT;

-- 自证（期望三行都 = _seed.n）
SELECT 'user_info' AS tbl, count(*) FROM user_info WHERE name LIKE 'AUDIT-R1-%'
UNION ALL SELECT 'shopping_session', count(*) FROM shopping_session WHERE session_id LIKE 'AUDIT-R1-%'
UNION ALL SELECT 'cabinet_order', count(*) FROM cabinet_order WHERE order_id LIKE 'AUDIT-R1-%';
