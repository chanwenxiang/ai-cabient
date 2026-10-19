-- 演示运营账号的商户绑定种子（DC-14，2026-10-19 拍板 deny-by-default）
-- 背景：「未绑定商户=全局数据范围」旧口径废除（AU-089 根因，MerchantScopeService.isGlobalScope
-- 只认显式 admin 角色）后，V117 演示账号（finance/operator/replenisher/viewer）按零范围处理，
-- 演示面会看不到商户数据。本种子按生产正确形态补显式绑定：→ 两家演示商户。
-- 生产环境 seed_env:none 自动跳过（演示账号在生产本就应为零范围，需用即在运营台显式绑定）。
-- 幂等：ON CONFLICT DO NOTHING —— 不动运营在后台已做的人工绑定。
-- 重跑：docker exec -i <pg> psql -U aicabinet -tA < services/trade-service/src/main/resources/db/seed/R__seed_demo_ops_merchant_bindings.sql

INSERT INTO ops_user_merchant (user_id, merchant_id)
SELECT u.user_id, m.merchant_id
FROM user_info u
CROSS JOIN (VALUES ('MCH-DEFAULT'), ('MCH-OTHER')) AS m(merchant_id)
WHERE u.user_id IN (100000007, 100000008, 100000009, 100000010)
  AND u.account_type <> 'CONSUMER'
ON CONFLICT DO NOTHING;
