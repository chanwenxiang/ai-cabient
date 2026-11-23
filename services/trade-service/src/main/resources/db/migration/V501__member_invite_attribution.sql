-- V501: 会员邀请归因（营销 2）—— 邀请码唯一性、归因时间、发奖状态、两张专用券定义
--
-- 背景：V128 删掉了 member.invite_code 与 idx_member_invite_code，V163 只把**列**加回来，
-- 唯一索引至今没有恢复（全仓迁移里再无 idx_member_invite_code）。在缺唯一索引时上邀请归因，
-- 会出现「一个码挂多人」⇒ 归因落到错的邀请人。故本迁移先补唯一性。
--
-- 为什么不开新表：邀请关系与会员是 **1:1**（一个会员只可能被邀请一次，绑定要求零消费记录），
-- 发奖状态天然挂在 member 行上；开独立台账只有在「同一人被多次邀请」或「要留存历史流水」时才划算。
-- 需要邀请流水时再单开表，不在本期先建一张只放 1 行的表。

-- ① 归因时间（invited_by 由 V163 补列，这里补「何时绑的」——没有它就回答不了
--    「是不是活动期内绑的」，做不了活动期校验）
ALTER TABLE member
    ADD COLUMN IF NOT EXISTS invited_at TIMESTAMPTZ;
COMMENT ON COLUMN member.invited_at IS '被邀请绑定时间（NULL=未绑定邀请人）';

-- ② 发奖状态（按被邀请人记：他绑定时领新人券、他首单支付后邀请人才拿券）
ALTER TABLE member
    ADD COLUMN IF NOT EXISTS invitee_coupon_id BIGINT;
COMMENT ON COLUMN member.invitee_coupon_id IS '邀请专享的新人首单券（user_coupon.coupon_id；无券模板时为 NULL）';

ALTER TABLE member
    ADD COLUMN IF NOT EXISTS invite_settled_at TIMESTAMPTZ;
COMMENT ON COLUMN member.invite_settled_at IS '该会员首单已把奖励发给邀请人的时间（NULL=未结算；重复支付回调靠它幂等）';

ALTER TABLE member
    ADD COLUMN IF NOT EXISTS inviter_reward_coupon_id BIGINT;
COMMENT ON COLUMN member.inviter_reward_coupon_id IS '发给邀请人的奖励券（user_coupon.coupon_id；NULL=未发放）';

-- ③ 邀请码唯一性：partial unique（NULL 不参与，存量 NULL 行不受影响）
CREATE UNIQUE INDEX IF NOT EXISTS idx_member_invite_code
    ON member (invite_code) WHERE invite_code IS NOT NULL;

-- ④ 按邀请人聚合（「我邀请了几人 / 几人已完成首单」两个计数都走它，避免全表扫）
CREATE INDEX IF NOT EXISTS idx_member_invited_by
    ON member (invited_by) WHERE invited_by IS NOT NULL;

-- ⑤ 两张专用券模板：用 system_code 作稳定锚点（券名可被运营改，改完不能找不到）
ALTER TABLE coupon_definition
    ADD COLUMN IF NOT EXISTS system_code VARCHAR(32);
COMMENT ON COLUMN coupon_definition.system_code IS '系统固定券标识（如 INVITE_NEWCOMER）；非空表示由代码按语义取用，勿改';
CREATE UNIQUE INDEX IF NOT EXISTS uk_coupon_definition_system_code
    ON coupon_definition (system_code) WHERE system_code IS NOT NULL;

-- 面额/门槛/有效期都留在券模板里 ⇒ 运营在「券模板」页直接改，不走代码发版
INSERT INTO coupon_definition (
    coupon_name, coupon_type, denomination_cents, min_spend_cents, discount_percent,
    validity_days, max_issue_count, issued_count, device_scope, scope_type, status,
    description, system_code, created_at
)
SELECT v.coupon_name, 'AMOUNT_OFF', v.denomination_cents, v.min_spend_cents, NULL,
       v.validity_days, v.max_issue_count, 0, 'ALL', 'ALL', 'ACTIVE',
       v.description, v.system_code, NOW()
FROM (VALUES
    ('新人首单券 ¥5', 500, 1900, 7, 20000, '邀请专享：被邀请人绑定后自动发放，满 ¥19 可用、7 天有效', 'INVITE_NEWCOMER'),
    ('邀请人奖励券 ¥5', 500, 0, 30, 20000, '邀请好友首单完成后自动发放，无门槛、30 天有效', 'INVITE_INVITER')
) AS v(coupon_name, denomination_cents, min_spend_cents, validity_days, max_issue_count, description, system_code)
WHERE NOT EXISTS (
    SELECT 1 FROM coupon_definition d WHERE d.system_code = v.system_code
);
