-- OBS-008：viewer 去掉根目录权限 ops（cascade 树会误显「全勾」）
DELETE FROM ops_role_permission rp
USING ops_role r, ops_permission p
WHERE rp.role_id = r.role_id
  AND rp.permission_id = p.permission_id
  AND r.role_key = 'viewer'
  AND p.perm_code = 'ops';

-- 再保险：写/导出类若被后续迁移挂回，再次收回
DELETE FROM ops_role_permission rp
USING ops_role r, ops_permission p
WHERE rp.role_id = r.role_id
  AND rp.permission_id = p.permission_id
  AND r.role_key = 'viewer'
  AND (
    p.perm_code LIKE '%:edit'
    OR p.perm_code LIKE '%:export'
    OR p.perm_code LIKE '%:run'
    OR p.perm_code LIKE '%:fix'
    OR p.perm_code LIKE '%:handle'
    OR p.perm_code IN (
      'ops:repair:edit',
      'ops:consistency:run',
      'ops:consistency:fix',
      'ops:order:refund',
      'ops:session:cancel',
      'ops:dispute:approve',
      'ops:dispute:reject'
    )
  );

-- OBS-014：CAB-OTHER 与 CAB-001 同坐标导致地图聚成一点
UPDATE device_info
SET latitude = 22.5650,
    longitude = 114.1200,
    address = CASE
      WHEN address IS NULL OR btrim(address) = '' OR address = (
        SELECT d2.address FROM device_info d2 WHERE d2.device_id = 'CAB-001'
      ) THEN '深圳市罗湖区演示点位 B（CAB-OTHER）'
      ELSE address
    END,
    updated_at = NOW()
WHERE device_id = 'CAB-OTHER';

-- OBS-021：历史脏数据（默认上海起点→深圳柜 ≈ 1209196m）
UPDATE replenishment_task
SET notes = NULLIF(btrim(regexp_replace(COALESCE(notes, ''), '\s*dist=\d+m?', '', 'gi')), '')
WHERE notes ~* 'dist=1209196';

UPDATE replenishment_route
SET total_distance_m = NULL
WHERE total_distance_m BETWEEN 1200000 AND 1215000;
