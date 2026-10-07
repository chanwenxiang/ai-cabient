-- MIGRATION_KIND: backfill
-- MIGRATION_BACKFILL_REASON: 含幂等数据写语句（INSERT INTO notification_template ... ON CONFLICT DO NOTHING，
--   催缴模板 CB-003，channels 必须含 SMS 否则短信发不出）。DDL 主体仍是 schema，故声明 backfill 而非 seed。
-- MIGRATION_REVIEWED: yes
-- TABLES: user_info (-verify_channel 列) / wechat_realname_preorder (-全表) / identity_verify_attempt (-全表) / notification_template (+3 行) / unpaid_dunning_record (+新表 ~0 行)
-- LOCK_RISK: low
-- ROLLBACK: 不可逆删除 V323 三件套；如需恢复实名能力须另写新迁移重新建表
-- NOTES: ⓿ 本脚本同时含 DDL 与**幂等 backfill**（催缴模板 INSERT ... ON CONFLICT DO NOTHING），故 MIGRATION_KIND=backfill；① 撤销 V323（实名 A 方案）—— CB-002 已定「不做 C 端实名开门前置」；② 催缴模板 channels 含 SMS（CB-003：订阅消息需用户主动订阅，最该被催的逃单者收不到）；③ 欠款催缴阶梯记录表（CB-011 抄共享充电宝阶梯）。本脚本可重入（IF EXISTS / ON CONFLICT）。
-- COMPETITOR_REF: CB-002, CB-003, CB-011

-- ===================================================================
-- ① 撤销 V323：实名核验通道（微信官方 A + 第三方人脸 B）
-- ===================================================================
-- 为什么撤销而不是「留着不启用」（铁律 34：区分真缺失 / 有意简化 / 默认关的开关）：
--   V323 不是「默认关闭的开关」，而是**建了两张业务表 + 给 user_info 加了一列**，
--   却**没有任何 Java 代码读写它们** ⇒ 属于「能力已建、链路未通」的死结构，
--   会让后来人误以为实名核验已经实现（比「没做」更危险）。
--
-- 取证（2026-10-07）：
--   `git show --stat 2b4172c6` —— 该提交含 V323 的 110 行 SQL，但 **零 Java 文件**。
--   `ls services/.../service/ | grep -i identity` —— **零命中**，确实无配套实现。
--
-- 竞品依据 CB-002：友宝（刷脸即核验）、美智微（未实名用户直接无先享后付）、
--   哈哈零兽（扫码→免密→关门结算）**三家同行全链路都没有独立实名环节** ⇒ 我们也不做。
--
-- ⚠️ forward-only：**不改写 V323 文件本身**（改了会 checksum 漂移），
--    而是用本迁移显式撤销 —— 这是唯一符合 Flyway 语义的做法。
ALTER TABLE user_info
    DROP COLUMN IF EXISTS verify_channel;

DROP TABLE IF EXISTS identity_verify_attempt;
DROP TABLE IF EXISTS wechat_realname_preorder;

-- ===================================================================
-- ② 欠款催缴阶梯（CB-011）
-- ===================================================================
-- 为什么需要独立表：催缴频次/阶梯状态必须**跨订单累计**，
-- 而 `cabinet_order` 只记单笔 —— 没有载体就只能在内存里数，重启即丢。
--
-- 🔴 与 `user_blacklist` 的分工（别混）：
--   `user_blacklist`      = 硬拦截（开门直接拒），有/无 + 到期时间
--   `unpaid_dunning_record` = 软阶梯（累计欠款次数 → 催缴强度/限制等级），**到期可自愈**
-- 阶梯不做征信上报（CB-011：无持牌资质，「无权单方拉黑」）。
CREATE TABLE IF NOT EXISTS unpaid_dunning_record (
    user_id             BIGINT      NOT NULL,
    -- 累计欠款关单次数（阶梯判据，见 DUNNING_TIER_* 常量）
    unpaid_count        INT         NOT NULL DEFAULT 0,
    -- 当前阶梯：0 无 / 1 提醒 / 2 限制额度 / 3 限制免密先享
    tier                SMALLINT    NOT NULL DEFAULT 0,
    last_reminded_at    TIMESTAMPTZ,
    last_dunning_at     TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id)
);

COMMENT ON TABLE unpaid_dunning_record IS
    'CB-011 欠款催缴阶梯（抄共享充电宝范式：累计 3 次进谨慎名单）。'
    '只做平台内行为限制，严禁对外宣称上报征信 —— 非持牌主体无权报送。';

-- ===================================================================
-- ③ 催缴通知模板（CB-003）
-- ===================================================================
-- 🔴 channels 必须含 SMS：微信订阅消息**需用户主动订阅**，
--    而最该被催的逃单者恰恰不会订阅 ⇒ 只配 WECHAT 等于催不到人。
--    模板缺失时 `ExternalNotificationDispatcher.smsChannelConfigured` 返回 false 并跳过，
--    所以模板是短信能否发出的**决定性开关**（不是可选项）。
INSERT INTO notification_template
    (template_code, template_name, channel, channels, title_template, body_template, audience)
VALUES
    ('unpaid_order_dunning', '欠款订单催缴', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE,SMS',
     '待支付订单提醒',
     '您的订单 {orderId} 尚未支付 {amount} 元，请尽快补缴。逾期未支付将限制再次开柜使用，可在「我的-订单」中查看。',
     'CONSUMER'),
    ('unpaid_order_blacklisted', '欠款限制生效通知', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE,SMS',
     '账户使用受限',
     '因存在多笔未支付订单（{unpaidCount} 笔），您的账户已限制开柜与免密先享功能。请结清欠款后恢复。',
     'CONSUMER')
ON CONFLICT (template_code) DO NOTHING;

-- 迁移后必须验的
-- 1. SELECT column_name FROM information_schema.columns
--    WHERE table_name='user_info' AND column_name='verify_channel';  -- 期望 0 行
-- 2. \d unpaid_dunning_record
-- 3. SELECT template_code, channels FROM notification_template
--    WHERE template_code LIKE 'unpaid_order%';                        -- 期望 2 行且含 SMS
-- 4. SELECT COUNT(*) FROM wechat_realname_preorder;                 -- 期望报错（表已不存在）

-- TABLES: user_info (-verify_channel 列) / wechat_realname_preorder (-全表) / identity_verify_attempt (-全表) / notification_template (+3 行) / unpaid_dunning_record (+新表 ~0 行)
-- LOCK_RISK: low
-- ROLLBACK: 不可逆删除 V323 三件套；如需恢复实名能力须另写新迁移重新建表
-- NOTES: ⓿ 本脚本同时含 DDL 与**幂等 backfill**（催缴模板 INSERT ... ON CONFLICT DO NOTHING），故 MIGRATION_KIND=backfill；① 撤销 V323（实名 A 方案）—— CB-002 已定「不做 C 端实名开门前置」；② 催缴模板 channels 含 SMS（CB-003：订阅消息需用户主动订阅，最该被催的逃单者收不到）；③ 欠款催缴阶梯记录表（CB-011 抄共享充电宝阶梯）。本脚本可重入（IF EXISTS / ON CONFLICT）。
-- COMPETITOR_REF: CB-002, CB-003, CB-011

-- ===================================================================
-- ① 撤销 V323：实名核验通道（微信官方 A + 第三方人脸 B）
-- ===================================================================
-- 为什么撤销而不是「留着不启用」（铁律 34：区分真缺失 / 有意简化 / 默认关的开关）：
--   V323 不是「默认关闭的开关」，而是**建了两张业务表 + 给 user_info 加了一列**，
--   却**没有任何 Java 代码读写它们** ⇒ 属于「能力已建、链路未通」的死结构，
--   会让后来人误以为实名核验已经实现（比「没做」更危险）。
--
-- 取证（2026-10-07）：
--   `git show --stat 2b4172c6` —— 该提交含 V323 的 110 行 SQL，但 **零 Java 文件**。
--   `ls services/.../service/ | grep -i identity` —— **零命中**，确实无配套实现。
--
-- 竞品依据 CB-002：友宝（刷脸即核验）、美智微（未实名用户直接无先享后付）、
--   哈哈零兽（扫码→免密→关门结算）**三家同行全链路都没有独立实名环节** ⇒ 我们也不做。
--
-- ⚠️ forward-only：**不改写 V323 文件本身**（改了会 checksum 漂移），
--    而是用本迁移显式撤销 —— 这是唯一符合 Flyway 语义的做法。
ALTER TABLE user_info
    DROP COLUMN IF EXISTS verify_channel;

DROP TABLE IF EXISTS identity_verify_attempt;
DROP TABLE IF EXISTS wechat_realname_preorder;

-- ===================================================================
-- ② 欠款催缴阶梯（CB-011）
-- ===================================================================
-- 为什么需要独立表：催缴频次/阶梯状态必须**跨订单累计**，
-- 而 `cabinet_order` 只记单笔 —— 没有载体就只能在内存里数，重启即丢。
--
-- 🔴 与 `user_blacklist` 的分工（别混）：
--   `user_blacklist`      = 硬拦截（开门直接拒），有/无 + 到期时间
--   `unpaid_dunning_record` = 软阶梯（累计欠款次数 → 催缴强度/限制等级），**到期可自愈**
-- 阶梯不做征信上报（CB-011：无持牌资质，「无权单方拉黑」）。
CREATE TABLE IF NOT EXISTS unpaid_dunning_record (
    user_id             BIGINT      NOT NULL,
    -- 累计欠款关单次数（阶梯判据，见 DUNNING_TIER_* 常量）
    unpaid_count        INT         NOT NULL DEFAULT 0,
    -- 当前阶梯：0 无 / 1 提醒 / 2 限制额度 / 3 限制免密先享
    tier                SMALLINT    NOT NULL DEFAULT 0,
    last_reminded_at    TIMESTAMPTZ,
    last_dunning_at     TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id)
);

COMMENT ON TABLE unpaid_dunning_record IS
    'CB-011 欠款催缴阶梯（抄共享充电宝范式：累计 3 次进谨慎名单）。'
    '只做平台内行为限制，严禁对外宣称上报征信 —— 非持牌主体无权报送。';

-- ===================================================================
-- ③ 催缴通知模板（CB-003）
-- ===================================================================
-- 🔴 channels 必须含 SMS：微信订阅消息**需用户主动订阅**，
--    而最该被催的逃单者恰恰不会订阅 ⇒ 只配 WECHAT 等于催不到人。
--    模板缺失时 `ExternalNotificationDispatcher.smsChannelConfigured` 返回 false 并跳过，
--    所以模板是短信能否发出的**决定性开关**（不是可选项）。
INSERT INTO notification_template
    (template_code, template_name, channel, channels, title_template, body_template, audience)
VALUES
    ('unpaid_order_dunning', '欠款订单催缴', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE,SMS',
     '待支付订单提醒',
     '您的订单 {orderId} 尚未支付 {amount} 元，请尽快补缴。逾期未支付将限制再次开柜使用，可在「我的-订单」中查看。',
     'CONSUMER'),
    ('unpaid_order_blacklisted', '欠款限制生效通知', 'IN_APP', 'IN_APP,WECHAT_SUBSCRIBE,SMS',
     '账户使用受限',
     '因存在多笔未支付订单（{unpaidCount} 笔），您的账户已限制开柜与免密先享功能。请结清欠款后恢复。',
     'CONSUMER')
ON CONFLICT (template_code) DO NOTHING;

-- 迁移后必须验的
-- 1. SELECT column_name FROM information_schema.columns
--    WHERE table_name='user_info' AND column_name='verify_channel';  -- 期望 0 行
-- 2. \d unpaid_dunning_record
-- 3. SELECT template_code, channels FROM notification_template
--    WHERE template_code LIKE 'unpaid_order%';                        -- 期望 2 行且含 SMS
-- 4. SELECT COUNT(*) FROM wechat_realname_preorder;                 -- 期望报错（表已不存在）
