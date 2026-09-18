-- =====================================================================
-- v2 修订（C01+H15）：本迁移为 UAT/本地种子数据，现按 Flyway placeholders 环境守卫收窄：
--   仅当 seed-env ∈ (local, dev, uat) 时执行写语句；生产（application-prod.yml 固定 seed-env: none）跳过。
-- 注意 Flyway checksum：已应用过 v1 本迁移的环境升级后需执行 `flyway repair` 对齐校验和。
-- =====================================================================
-- 商户进件「财务复核」节点按 DEPT=FINANCE 派发；种子环境财务部常空导致推进后无待办卡死。
-- 将运营超管兼任财务部，并补建已卡死实例的当前节点待办。

INSERT INTO ops_user_department (user_id, dept_id, is_primary)
SELECT 100000001, d.dept_id, false
FROM ops_department d
WHERE d.dept_key = 'FINANCE'
  AND COALESCE(d.is_deleted, false) = false
  AND '${seed_env}' IN ('local', 'dev', 'uat')
  AND NOT EXISTS (
      SELECT 1
      FROM ops_user_department ud
      WHERE ud.user_id = 100000001
        AND ud.dept_id = d.dept_id
  );

INSERT INTO approval_task (instance_id, node_seq, node_name, assignee_user_id, status, created_at)
SELECT i.instance_id,
       i.current_node_seq,
       n.node_name,
       100000001,
       'PENDING',
       now()
FROM approval_instance i
JOIN approval_node n
  ON n.def_id = i.def_id
 AND n.seq = i.current_node_seq
WHERE i.status = 'PENDING'
  AND '${seed_env}' IN ('local', 'dev', 'uat')
  AND NOT EXISTS (
      SELECT 1
      FROM approval_task t
      WHERE t.instance_id = i.instance_id
        AND t.node_seq = i.current_node_seq
        AND t.status = 'PENDING'
  );
