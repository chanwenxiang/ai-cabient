-- V137: purge legacy INACTIVE ops permissions (no active children)

DELETE FROM ops_role_permission
WHERE permission_id IN (
    SELECT permission_id FROM ops_permission WHERE status = 'INACTIVE'
);

DELETE FROM ops_permission WHERE status = 'INACTIVE';
