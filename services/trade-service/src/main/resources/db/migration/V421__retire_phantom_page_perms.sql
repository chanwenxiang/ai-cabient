-- MIGRATION_KIND: backfill
-- R8（2026-11-01 系统模块审计，MA-021）：撤下 4 个「前后端零引用」的 ACTIVE 页面权限码。
--
-- 成因：H51（2026-10 后台权限审计）发现侧栏导航码与后端实际校验码不一致时，只改了前端一侧
-- ——`clients/admin-vue/src/config/menu.ts` 把 /big-screen 改指 `ops:dashboard:view`（该文件:35 留注）、
-- 把 /ad-campaigns 改指 `ops:ad:list`（:441 留注）、识别映射页改指 `ops:device:*`（:188 留注）。
-- 库里被换下来的 `ops_permission` 行原样留着 status='ACTIVE'，于是留下两类误导面：
--   1) 「菜单管理」列的是权限表本身（includeInactive），这些行显示成启用中的页面；
--   2) 角色编辑的权限树取 ACTIVE 全集（`OpsRbacService` 的列表查询），运营能把它们勾给角色，
--      而勾了不会打开任何页面、也不会放开任何接口——即「幽灵菜单」。
-- 判据（本轮逐码复核，非仅 grep）：
--   `rg` 全仓（排除 node_modules/dist/target/db-migration/docs）后，四个码在
--   services/**.java、clients/**.{ts,vue,js} 里 0 命中；menu.ts 仅剩解释性注释；
--   且四行在 `ops_permission` 里 `parent_id` 子节点数为 0（不会把在用的 F 按钮一起带下去）。
-- 处置沿用 V386（`ops:session:upload`）的拍板口径：**RETIRED 而非 DELETE**——
--   授权行与审计历史原样保留、菜单管理仍可见（留痕），而 OpsPermissionMapper 的
--   两条读查询都带 `p.status = 'ACTIVE'` ⇒ 即刻不再进任何人的权限集与角色树。
-- 刻意**不动**的同类行（判据不同，勿顺手对齐）：
--   · `ops:operlog`(290) / `ops:pricing:view`(718)：本身零引用，但各自挂着在用的 F 子节点
--     （`ops:operlog:export`、`ops:pricing:edit` 在前后端都有实引用），退役父行会让角色树里的
--     这些按钮无处挂靠 ⇒ 保留 ACTIVE，它们现在是「分组节点」而非页面码。
--   · `ops:nav:*`（7 个 M 目录行）：侧栏分组按「有无可见子页」推导，目录码不进代码；
--     menu.ts:4 已注明它与菜单管理目录一一对应 ⇒ 结构性节点，保留。
--   · `ops:recognition-demo:view`：两条门禁（check-menu-permissions / check-frontend-perm-codes）
--     按 V138「菜单可见性与按钮两级分离」显式豁免，去留属 R8 待拍板项，本迁移不预支。
-- 无 DDL、无外键变更、热表无涉：ops_permission 四行 UPDATE（表 ~241 行）。
-- LOCK_RISK: low
-- TABLES: ops_permission (~241 rows)
-- NOTES: 纯 DML；幂等（status<>'RETIRED' 才写）；不改任何历史迁移；授权行不删

UPDATE ops_permission
   SET status = 'RETIRED'
 WHERE perm_code IN (
    'ops:ad:campaign:list',   -- V170 种，页面 /ad-campaigns 现由 ops:ad:list 把关
    'ops:bigscreen:view',     -- V154 种，大屏接口实际认 ops:dashboard:view / ops:analytics:view
    'ops:sku-vision:list',    -- V144 种「识别入驻」，vision 链已随 DC-27(b) 退役
    'ops:vision:list'         -- V14/V109 种「识别映射」，旧控制器批 5 已删（menu.ts:188 留注）
 )
   AND status <> 'RETIRED';
