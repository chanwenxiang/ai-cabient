-- 店员只读：收回团队列表权限，避免「我的」出现团队入口（T-112 / 执行文档：无钱包/团队/设置入口）。
-- 店长仍保留 merchant:users:list，可查看团队但不可邀请。

DELETE FROM ops_role_permission
WHERE role_id = (SELECT role_id FROM ops_role WHERE role_key = 'merchant_staff')
  AND permission_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:users:list');
