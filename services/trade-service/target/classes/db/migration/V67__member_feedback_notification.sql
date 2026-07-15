-- P0: 会员体系 + 用户反馈 + 消息推送系统

CREATE TABLE IF NOT EXISTS member_level (
    level_id        SMALLSERIAL     PRIMARY KEY,
    level_name      VARCHAR(32)     NOT NULL,
    min_exp         INT             NOT NULL DEFAULT 0,
    discount_rate   INT             NOT NULL DEFAULT 100,  -- 百分比 100=全价 90=九折
    monthly_coupon_count INT       NOT NULL DEFAULT 0,     -- 每月自动发券数量
    icon_url        VARCHAR(256),
    benefits_desc   TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

INSERT INTO member_level (level_name, min_exp, discount_rate, monthly_coupon_count, benefits_desc) VALUES
    ('普通会员', 0, 100, 0, '基础购物体验'),
    ('银卡会员', 1000, 98, 1, '全场98折，每月1张优惠券'),
    ('金卡会员', 5000, 95, 2, '全场95折，每月2张优惠券，优先客服'),
    ('钻石会员', 20000, 90, 4, '全场9折，每月4张优惠券，专属客服')
ON CONFLICT (level_id) DO NOTHING;

ALTER TABLE user_info
    ADD COLUMN IF NOT EXISTS member_level_id SMALLINT REFERENCES member_level(level_id),
    ADD COLUMN IF NOT EXISTS experience_points INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_spent_cents BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_orders INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(512),
    ADD COLUMN IF NOT EXISTS gender VARCHAR(8),
    ADD COLUMN IF NOT EXISTS city VARCHAR(64),
    ADD COLUMN IF NOT EXISTS province VARCHAR(64),
    ADD COLUMN IF NOT EXISTS register_channel VARCHAR(32),
    ADD COLUMN IF NOT EXISTS register_device_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_user_info_member ON user_info (member_level_id) WHERE member_level_id IS NOT NULL;

-- 用户签到
CREATE TABLE IF NOT EXISTS user_sign_in (
    sign_in_id      BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES user_info(user_id),
    sign_in_date    DATE            NOT NULL,
    consecutive_days INT            NOT NULL DEFAULT 1,
    reward_points   INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, sign_in_date)
);

CREATE INDEX IF NOT EXISTS idx_sign_in_user_date ON user_sign_in (user_id, sign_in_date DESC);

-- 积分明细
CREATE TABLE IF NOT EXISTS points_ledger (
    ledger_id       BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES user_info(user_id),
    points_change   INT             NOT NULL,
    balance_after   INT             NOT NULL DEFAULT 0,
    reason          VARCHAR(64)     NOT NULL,  -- SIGN_IN / ORDER / COUPON / REFUND / ADMIN
    ref_id          VARCHAR(64),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_points_ledger_user ON points_ledger (user_id, created_at DESC);

-- 用户反馈/评价
CREATE TABLE IF NOT EXISTS user_feedback (
    feedback_id     BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES user_info(user_id),
    feedback_type   VARCHAR(32)     NOT NULL,  -- COMPLAINT / SUGGESTION / BUG / PRAISE
    content         TEXT            NOT NULL,
    contact_info    VARCHAR(128),
    device_id       VARCHAR(64),
    session_id      VARCHAR(32),
    images          TEXT,                       -- JSON 图片URL数组
    rating          SMALLINT,                   -- 1-5 星
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    handler_id      BIGINT,
    reply           TEXT,
    handled_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_feedback_status ON user_feedback (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_feedback_user ON user_feedback (user_id, created_at DESC);

-- 消息模板（微信订阅消息/短信）
CREATE TABLE IF NOT EXISTS message_template (
    template_id     VARCHAR(64)     PRIMARY KEY,
    template_name   VARCHAR(128)    NOT NULL,
    channel         VARCHAR(16)     NOT NULL,  -- WECHAT_SUBSCRIBE / SMS / IN_APP
    template_code   VARCHAR(64),
    content_tpl     TEXT            NOT NULL,   -- 模板内容，含 $${var} 占位符
    variables       TEXT,                       -- JSON 变量定义
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- 推送记录
CREATE TABLE IF NOT EXISTS push_record (
    push_id         BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES user_info(user_id),
    template_id     VARCHAR(64)     REFERENCES message_template(template_id),
    channel         VARCHAR(16)     NOT NULL,
    title           VARCHAR(256),
    body            TEXT,
    ref_type        VARCHAR(32),    -- ORDER / SESSION / DISPUTE / SYSTEM
    ref_id          VARCHAR(64),
    status          VARCHAR(16)     NOT NULL DEFAULT 'SENT',
    send_result     TEXT,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_push_record_user ON push_record (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_push_record_status ON push_record (status, created_at DESC);

-- 运营公告
CREATE TABLE IF NOT EXISTS announcement (
    announce_id     BIGSERIAL       PRIMARY KEY,
    title           VARCHAR(256)    NOT NULL,
    content         TEXT            NOT NULL,
    announce_type   VARCHAR(16)     NOT NULL DEFAULT 'SYSTEM',  -- SYSTEM / MAINTENANCE / PROMOTION
    target_scope    VARCHAR(32)     NOT NULL DEFAULT 'ALL',     -- ALL / MERCHANT / CONSUMER
    target_device   VARCHAR(64),                                 -- NULL=全部设备
    priority        VARCHAR(8)      NOT NULL DEFAULT 'NORMAL',  -- LOW / NORMAL / HIGH / URGENT
    status          VARCHAR(16)     NOT NULL DEFAULT 'DRAFT',   -- DRAFT / PUBLISHED / ARCHIVED
    publish_at      TIMESTAMPTZ,
    expire_at       TIMESTAMPTZ,
    operator_id     BIGINT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_announce_status ON announcement (status, publish_at DESC);

-- 权限
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (260, 1, 'ops:announcement',          '公告管理',   'M', NULL,                 20),
    (261, 260, 'ops:announcement:list',   '公告列表',   'C', '/admin/announcements', 1),
    (262, 260, 'ops:announcement:create', '新建公告',   'F', NULL,                 2),
    (263, 260, 'ops:announcement:edit',   '编辑公告',   'F', NULL,                 3),
    (264, 260, 'ops:announcement:publish','发布公告',   'F', NULL,                 4),
    (270, 1, 'ops:feedback',              '用户反馈',   'C', '/admin/feedback',    21),
    (271, 270, 'ops:feedback:reply',      '回复反馈',   'F', NULL,                 1),
    (280, 1, 'ops:message:templates',     '消息模板',   'C', '/admin/message-templates', 22),
    (281, 280, 'ops:message:templates:edit', '编辑模板', 'F', NULL,                 1)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission WHERE perm_code LIKE 'ops:announcement%' OR perm_code LIKE 'ops:feedback%' OR perm_code LIKE 'ops:message%'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission WHERE perm_code IN ('ops:feedback', 'ops:feedback:reply')
ON CONFLICT DO NOTHING;
