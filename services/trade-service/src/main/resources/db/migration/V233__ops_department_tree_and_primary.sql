-- V233: Department tree (parent_id) + primary department membership (RuoYi-aligned org).

ALTER TABLE ops_department
    ADD COLUMN IF NOT EXISTS parent_id BIGINT NULL REFERENCES ops_department (dept_id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_ops_department_parent ON ops_department (parent_id);

COMMENT ON COLUMN ops_department.parent_id IS '上级部门；空=根节点（靠近若依 sys_dept 树）';

ALTER TABLE ops_user_department
    ADD COLUMN IF NOT EXISTS is_primary BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN ops_user_department.is_primary IS '用户主部门（一人至多一个）；兼任部门为 false，审批仍可用全部部门';

-- One primary dept per user
CREATE UNIQUE INDEX IF NOT EXISTS uk_ops_user_department_primary
    ON ops_user_department (user_id)
    WHERE is_primary = TRUE;

-- Seed: each existing membership without primary → mark lowest dept_id as primary
UPDATE ops_user_department ud
SET is_primary = TRUE
WHERE ud.is_primary = FALSE
  AND NOT EXISTS (
      SELECT 1 FROM ops_user_department x
      WHERE x.user_id = ud.user_id AND x.is_primary = TRUE
  )
  AND ud.dept_id = (
      SELECT MIN(ud2.dept_id) FROM ops_user_department ud2 WHERE ud2.user_id = ud.user_id
  );

-- Optional light tree: PROCUREMENT / FINANCE / MANAGER under HQ when HQ exists
UPDATE ops_department d
SET parent_id = (SELECT dept_id FROM ops_department WHERE dept_key = 'HQ' LIMIT 1)
WHERE d.dept_key IN ('FINANCE', 'PROCUREMENT', 'MANAGER')
  AND d.parent_id IS NULL
  AND EXISTS (SELECT 1 FROM ops_department WHERE dept_key = 'HQ');
