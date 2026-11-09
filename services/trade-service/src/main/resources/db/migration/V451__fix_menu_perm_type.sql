-- 菜单级权限码类型修正：ops:rbac:assign / ops:approval:config 的 perm_type 由 F 改为 C
--
-- 为什么必须改：
--   前端路由守卫（clients/admin-vue/src/router/index.ts 的 canAccessNav）除了 hasPerm
--   还要过 isNavMenuActive；而 /rbac/me/nav 只返回 perm_type ∈ (C, M) 的 ACTIVE 权限码
--   —— 这是刻意设计（停用菜单后即使持有 ops:admin 也不该出现在导航里）。
--   这两个码在 V8 / V228 里建成 'F'（功能点），却又被 clients/admin-vue/src/config/menu.ts
--   当作「运营账号」「审批流配置」两个菜单的权限码 ⇒ 全新迁移出来的库里这两页对所有人
--   都「无权访问」，超管也不例外。
--
--   实测（2026-11-09，dev 库单变量对照）：
--     perm_type='F' → /admin/operators 跳 /admin/forbidden?from=/operators
--     perm_type='C' → /admin/operators 正常渲染
--   开发库此前是被人在「权限管理」界面手工改成 C 的，所以本机看着正常、SQL 与库长期不一致；
--   换到 UAT / 生产这类全新建库的环境必然复发。
--
-- 只改类型（决定导航可见性），不动任何授权关系：持有该码的角色与账号不受影响。
-- 幂等：已是 'C' 的行不会被改写。
UPDATE ops_permission
SET perm_type = 'C'
WHERE perm_code IN ('ops:rbac:assign', 'ops:approval:config')
  AND perm_type <> 'C';
