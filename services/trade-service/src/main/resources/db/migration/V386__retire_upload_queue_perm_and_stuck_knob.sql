-- MIGRATION_KIND: backfill
-- DC-27(b) 批 6 收尾（2026-10-17 用户拍板「A 全撤」+「权限行改 RETIRED 留痕」，
-- 拍板面见 docs/AUDIT_DECISION_BRIEF_2026-10-16.md §15）：录像上传链随 vision 退役删除后，
-- 它遗留的两处运营可见面（一个告警扫描旋钮 + 一个权限码）也一并撤下。
--
-- 1) `ops_permission` 160 = `ops:session:upload`（V14 建行，原挂「上传队列」菜单 /upload-queue）。
--    该菜单页早已不存在（admin 侧栏由 clients/admin-vue/src/config/menu.ts 代码驱动，无此项），
--    代码里它只剩一处用途：会话/争议/异常页「看录像」按钮的 OR 兜底授权。那 7 处已全部收敛为
--    `ops:session:list`（两个持有该码的角色 admin/operator 本来就同时持有 ops:session:list ⇒
--    收敛不改变任何人的可见面）。
--    处置按拍板取 **RETIRED 而非 DELETE**：`ops_role_permission` 的授权行与审计历史原样保留，
--    菜单管理页 includeInactive 仍能看到这行（留痕），而 OpsPermissionMapper 的两条查询都带
--    `p.status = 'ACTIVE'` ⇒ 该码即刻在全系统失效，不会再被签进任何人的权限集。
--    不改历史迁移（V14/V93/V94/V118/V126/V138/V144 一律原样）。
-- 2) 删 `system_config` 的 `ops.scan.upload_stuck_minutes`（该键从无迁移建行，历来由
--    SystemConfigService.ensureDefaults() 补种；本次 seed 与注册表条目已同步撤除）。
--    它是 UPLOAD_STUCK 扫描的阈值旋钮；扫描分支已删（WAITING_UPLOAD 在全代码无写入者，
--    该扫描恒为 0 条），留着就是「改了没人执行」的死旋钮（本仓 no-dead-knobs 约定，同 V385）。
--    参数配置页 / 告警规则页都直接列 system_config 全表 ⇒ 必须删行，不能只摘注册表。
--
-- 标签层刻意全留（同一次拍板）：`sys_dict_data` 三行（ops_alert_type / merchant_alert_type /
-- session_state 里的 UPLOAD_STUCK 与 WAITING_UPLOAD 文案）、SysDictBootstrap 两处种子、
-- shared-dict 三处映射、大屏与商户小程序的渲染分支一律不动 —— 历史 ops_exception 行里
-- 已存在的 UPLOAD_STUCK 记录仍需在后台/大屏/商户端渲染成中文，撤标签会把旧告警变成裸枚举名。
-- 无外键变更、无热表：ops_permission 单行 UPDATE（~239 行）、system_config 单行 DELETE（~200 行）。
-- LOCK_RISK: low
-- TABLES: ops_permission (~239 rows), system_config (~200 rows)
-- NOTES: 纯 DML；权限行改状态不删行、不删授权；配置键删行后 ensureDefaults 不再补种（seed 已撤）

UPDATE ops_permission
   SET status = 'RETIRED'
 WHERE perm_code = 'ops:session:upload'
   AND status <> 'RETIRED';

DELETE FROM system_config
 WHERE config_key = 'ops.scan.upload_stuck_minutes';
