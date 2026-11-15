-- MIGRATION_KIND: backfill
-- MA-200（R23-B / 2026-11-15 拍板）：退役 `ops:recognition-demo:view`。
--
-- 成因：识别演示页 `vision/RecognitionDemoView.vue` 已在 c498c653（与「录像上传」同一支
-- 下线提交）删除，路由与侧栏项均不存在；`clients/admin-vue/src` 全域、trade 源码对该码
-- **零引用**（2026-11-15 复核）。V421:24 曾注明「去留属 R8 待拍板、本迁移不预支」，该
-- 拍板项漂移出两份 PENDING_DECISIONS 台账 ⇒ 本迁移连同拍板回填一起收口。
--
-- 拍板口径（与 V421 同源）：**RETIRED 而非 DELETE** —— 授权行与审计历史原样保留、
-- 菜单管理仍可见（留痕），而 OpsPermissionMapper 两条读查询都带 `status='ACTIVE'`
-- ⇒ 即刻不再进任何人的权限集与角色树。
-- 原豁免的两条门禁（check-menu-permissions / check-frontend-perm-codes）已同批删除
-- ALLOWLIST 条目；V138「菜单可见性与按钮两级分离」的设计意图由按钮码 `ops:sku:demo`
-- 继续承载（该码仍在用，不涉本迁移）。
--
-- 无 DDL、无外键变更、热表无涉：ops_permission 一行 UPDATE（表 ~241 行）。
-- LOCK_RISK: low
-- TABLES: ops_permission (~241 rows)
-- NOTES: 纯 DML；幂等（status<>'RETIRED' 才写）；不改任何历史迁移；授权行不删

UPDATE ops_permission
   SET status = 'RETIRED'
 WHERE perm_code = 'ops:recognition-demo:view'
   AND status <> 'RETIRED';
