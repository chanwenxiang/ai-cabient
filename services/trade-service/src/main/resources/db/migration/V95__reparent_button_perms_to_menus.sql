-- 将挂在已停用目录下的按钮（F）挂到对应菜单（C），避免菜单树因「含停用」逻辑露出旧目录

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:device:list')
WHERE perm_code = 'ops:device:edit';

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:session:list')
WHERE perm_code = 'ops:session:cancel';

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:sku:list')
WHERE perm_code = 'ops:sku:edit';

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:user:list')
WHERE perm_code = 'ops:user:balance';

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:risk:list')
WHERE perm_code = 'ops:risk:blacklist';

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:reconciliation:list')
WHERE perm_code = 'ops:reconciliation:run';

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:replenishment:list')
WHERE perm_code = 'ops:replenishment:edit';

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:merchant:list')
WHERE perm_code = 'ops:merchant:edit';

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:dict:list')
WHERE perm_code = 'ops:dict:edit';

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:config:list')
WHERE perm_code = 'ops:config:edit';

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:announcement:list')
WHERE perm_code IN ('ops:announcement:create', 'ops:announcement:edit', 'ops:announcement:publish');

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:promotion:list')
WHERE perm_code IN ('ops:promotion:create', 'ops:promotion:edit', 'ops:promotion:stop');

UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:coupon:list')
WHERE perm_code IN ('ops:coupon:create', 'ops:coupon:edit', 'ops:coupon:export');
