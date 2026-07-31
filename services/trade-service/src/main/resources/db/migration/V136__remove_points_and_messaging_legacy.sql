-- V136: remove member points, messaging templates, and orphan legacy tables

-- Member points
DROP TABLE IF EXISTS member_points_log;
DROP TABLE IF EXISTS points_redeem_item;
DROP TABLE IF EXISTS points_ledger;

ALTER TABLE member
    DROP COLUMN IF EXISTS total_points,
    DROP COLUMN IF EXISTS available_points,
    DROP COLUMN IF EXISTS used_points,
    DROP COLUMN IF EXISTS expired_points,
    DROP COLUMN IF EXISTS invited_by;

DROP INDEX IF EXISTS idx_member_invited_by;

ALTER TABLE member_level_rule
    DROP COLUMN IF EXISTS min_points,
    DROP COLUMN IF EXISTS max_points,
    DROP COLUMN IF EXISTS points_rate;

UPDATE promotion_activity
SET status = 'ARCHIVED'
WHERE activity_type = 'POINTS' AND status = 'ACTIVE';

-- Messaging templates (V128 only marked INACTIVE)
DELETE FROM ops_role_permission
WHERE permission_id IN (
    SELECT permission_id FROM ops_permission
    WHERE perm_code IN ('ops:message:templates', 'ops:message:templates:edit')
);
DELETE FROM ops_permission
WHERE perm_code IN ('ops:message:templates', 'ops:message:templates:edit');

DROP TABLE IF EXISTS push_record;
DROP TABLE IF EXISTS message_template;

-- Orphan legacy / social (drop FK column before dependent table)
DROP TABLE IF EXISTS share_reward;
DROP TABLE IF EXISTS user_sign_in;

ALTER TABLE user_info
    DROP COLUMN IF EXISTS member_level_id,
    DROP COLUMN IF EXISTS experience_points;

DROP TABLE IF EXISTS member_level;
