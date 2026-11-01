-- MIGRATION_KIND: backfill
-- R10（2026-11-02，MA-036）：再撤 4 个「前后端零引用」权限码 + 1 个失去唯一子节点的父行。
--
-- 判据（R10 §10.2 三方对账 B∖(A∪C) 收敛到 20 候选 → 逐一复核 → 本批又亲自复核一遍）：
--   `grep -rn -F <码> services/*/src clients/*/src packages/*/src scripts`（排除 dist）四码**全部 0 命中**；
--   动态拼码形态 `hasPerm(`${…}`)` 经查只出现在「读 nav 配置对象」的 4 处，不构成字符串拼接；
--   R10 顺带抓出一条脚本假阳性护栏：`ops:audit:recent` 在 `AdminDashboardController` /
--   `OpsAuditQueryService` 以**非注解**形式使用，而只扫 `@RequiresPermissions` 字面量的 A 集会漏看 ⇒ 不领号。
--
-- 本批 5 行：
--   · `merchant:coupon:view`(C，1 角色持有)：商户端券可见性码，商户端源码零引用（真实门控走别的路径）。
--   · `ops:operlog:export` / `ops:upload:export` / `ops:vision:export`(F)：三个导出按钮码，
--     对应页面/控制器已随导出链与 vision 退役（DC-27(b)）删除，全仓无 `@RequiresPermissions` 也无前端 hasPerm。
--   · `ops:operlog`(C)：MA-021 当时**刻意保留**，理由正是「它的 F 子节点 `ops:operlog:export` 在用、
--     退役父行会让按钮无处挂靠」。本轮复核证明那个子节点本身就是零引用幽灵 ⇒ 子行退役后父行
--     再无在用子节点（`live_kids=0`），保留理由消失，一并 RETIRED。
--     对照：`ops:pricing:view` 的 `live_kids=2`（`ops:pricing:edit` 在 `AdminDevicePricingController` 真在用）
--     ⇒ **继续保留**，勿顺手对齐。
--
-- 处置沿用 V386/V421 口径：**RETIRED 不删行**（授权行与审计历史原样保留，菜单管理 includeInactive 仍可见留痕；
-- `OpsPermissionMapper` 两条读查询带 `p.status='ACTIVE'` ⇒ 即刻不再进任何人的权限集与角色树）。
-- 幂等：`AND status <> 'RETIRED'`；不改任何历史迁移；无 DDL、无外键变更。
-- LOCK_RISK: low
-- TABLES: ops_permission (~241 rows，命中 5 行)
-- NOTES: 纯 DML 改状态；角色侧既有授权行不删（读侧按 ACTIVE 过滤即失效）

UPDATE ops_permission
   SET status = 'RETIRED'
 WHERE perm_code IN (
    'merchant:coupon:view',   -- C，商户端零引用
    'ops:operlog:export',     -- F，导出链已退役
    'ops:upload:export',      -- F，同上
    'ops:vision:export',      -- F，vision 链随 DC-27(b) 退役
    'ops:operlog'             -- C，唯一子节点即上面那条幽灵 ⇒ 保留理由消失（MA-021 判据更新）
 )
   AND status <> 'RETIRED';
