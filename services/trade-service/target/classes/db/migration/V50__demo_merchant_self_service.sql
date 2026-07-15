-- Demo merchant: enable self-service flags for local verification
UPDATE merchant
SET allow_merchant_planogram_edit = TRUE,
    allow_merchant_pricing_edit = TRUE
WHERE merchant_id = 'MCH-DEFAULT';

-- Staff role: read-only planogram view
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 7, permission_id FROM ops_permission WHERE perm_code = 'merchant:slots:view'
ON CONFLICT DO NOTHING;
