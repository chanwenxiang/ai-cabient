-- module-audit 造数清理：R01 用户行为「沉睡用户」样本（与 R01-user-behavior-dormant.sql 成对）
-- 纪律：跑完必须看到三个 0，否则视为未清理（共享库会污染下一轮/下一个 AI 的判据）。
BEGIN;

DELETE FROM cabinet_order WHERE order_id LIKE 'AUDIT-R1-%';
DELETE FROM shopping_session WHERE session_id LIKE 'AUDIT-R1-%';
DELETE FROM user_info WHERE name LIKE 'AUDIT-R1-%';

COMMIT;

SELECT 'user_info' AS tbl, count(*) FROM user_info WHERE name LIKE 'AUDIT-R1-%'
UNION ALL SELECT 'shopping_session', count(*) FROM shopping_session WHERE session_id LIKE 'AUDIT-R1-%'
UNION ALL SELECT 'cabinet_order', count(*) FROM cabinet_order WHERE order_id LIKE 'AUDIT-R1-%';
