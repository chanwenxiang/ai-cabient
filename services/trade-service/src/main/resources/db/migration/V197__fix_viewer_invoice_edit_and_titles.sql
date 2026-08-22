-- Restrict invoice edit away from read-only viewer; repair garbled demo invoice titles.
DELETE FROM ops_role_permission rp
USING ops_role r, ops_permission p
WHERE rp.role_id = r.role_id
  AND rp.permission_id = p.permission_id
  AND r.role_key = 'viewer'
  AND p.perm_code = 'ops:invoice:edit';

UPDATE invoice_request
SET title = '深圳示例科技有限公司',
    updated_at = NOW()
WHERE invoice_id = 1
  AND title ~ '^\?+$';

UPDATE invoice_request
SET title = '北京示例商贸有限公司',
    updated_at = NOW()
WHERE invoice_id = 2
  AND title ~ '^\?+$';
