-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-036
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（3 条 ADD CONSTRAINT ... CHECK ... NOT VALID：只取短暂 ACCESS EXCLUSIVE 且不扫存量行；VALIDATE 解耦为部署前脏值探针后的运维动作）
-- TABLES: approval_instance.status / approval_task.status / user_coupon.status（第 13 批，②类建专用族，3 列 3 新族）
-- NOTES: R20 P3-1 第 13 批（docs/AUDIT_FOUR_END_R20_2026-11-12.md §三十三）。
--        域的权威来源 = 全仓写入点穷举（含 setter 形态，R7 类锚点只覆盖 LambdaUpdateWrapper 形态）：
--          · approval_instance.status <- approval_instance_status {PENDING,APPROVED,REJECTED}：
--            ApprovalWorkflowService 122/143(PENDING 重启与创建) 247(APPROVED) 287(REJECTED)；mapper 仅 .eq 读。
--            X 不含 CANCELLED：该值只出现在重启守卫的防御性读取（:172 equalsIgnoreCase），全仓无写点，死分支不入域。
--            X 不含 SKIPPED：SKIPPED 是任务级语义（并签时非 assignee 行被跳过），instance 无此态。
--          · approval_task.status <- approval_task_status {PENDING,APPROVED,SKIPPED,REJECTED}：
--            ApprovalWorkflowService 214(APPROVED) 232/281(SKIPPED) 277(REJECTED) + 创建即 PENDING。
--            🔴 instance 与 task 域不同 ⇒ R2 要求 CHECK 与族字面相等，必须分族（approval_instance_status /
--            approval_task_status），不为凑数放宽 instance 的 CHECK 去兼容 task 的 SKIPPED。
--          · user_coupon.status <- coupon_status {UNUSED,USED,EXPIRED}：
--            CouponService 225(发放 UNUSED) 307/742(核销 USED) 786(过期任务 EXPIRED)；UserCouponMapper:131
--            退款回滚 set(getStatus,"UNUSED")（R7 捕获点）；实体默认 "UNUSED"(UserCoupon:24)。
--            常量 CabinetConstants.COUPON_STATUS_UNUSED/USED/EXPIRED 三值齐备，与 dev 实测 DISTINCT 三值完全吻合。
--        DDL DEFAULT 全 ∈ 值域（R6）：approval_instance(V228:29 'PENDING') / approval_task(V228:46 'PENDING')
--          / user_coupon(V66:54 'UNUSED')。
--        CHECK (col IN (...)) 对 NULL 天然放行（SQL 三值逻辑）；3 列均 NOT NULL，NULL 不构成风险。
--        部署前脏值探针（逐列，NULL 显式放行）：
--          SELECT DISTINCT status FROM <table> WHERE status IS NOT NULL AND UPPER(TRIM(status)) NOT IN (<族值集>);
--        零脏值后再按表执行 ALTER TABLE <table> VALIDATE CONSTRAINT chk_<约束名>;
--        VALIDATE 故意不写进迁移：大表全扫 + 存量脏值会让 Flyway 失败=部署失败。

ALTER TABLE approval_instance
  ADD CONSTRAINT chk_approval_instance_status
  CHECK (status IN ('PENDING','APPROVED','REJECTED'))
  NOT VALID;
ALTER TABLE approval_task
  ADD CONSTRAINT chk_approval_task_status
  CHECK (status IN ('PENDING','APPROVED','SKIPPED','REJECTED'))
  NOT VALID;
ALTER TABLE user_coupon
  ADD CONSTRAINT chk_user_coupon_status
  CHECK (status IN ('UNUSED','USED','EXPIRED'))
  NOT VALID;
