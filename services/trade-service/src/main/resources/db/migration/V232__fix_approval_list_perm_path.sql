-- V232: Fix approval inbox perm path colliding with approval config page.
-- Inbox lives in topbar (OpsApprovalInbox), not a sidebar page at /approvals.
-- /approvals is reserved for ops:approval:config (系统 → 审批流配置).

UPDATE ops_permission
SET perm_type = 'F',
    path = NULL,
    perm_name = '审批待办（顶栏）',
    sort_order = 1
WHERE perm_code = 'ops:approval:list';

-- Keep under finance/merchant group as a button capability, not a duplicate menu.
UPDATE ops_permission
SET parent_id = 402
WHERE perm_code = 'ops:approval:list'
  AND parent_id IS DISTINCT FROM 402;
